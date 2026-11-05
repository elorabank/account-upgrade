package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.persistence.processed.ProcessedEvent;
import com.bank.upgrade.common.persistence.processed.ProcessedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.util.context.Context;
import reactor.util.retry.Retry;

/**
 * Reads the subscribed topics and, for each message:
 * <ol>
 *   <li>skips it if its event id was already processed;</li>
 *   <li>in ONE database transaction records the event id and runs the handler;</li>
 *   <li>only then acknowledges the Kafka offset, so a crash replays the message and step 1 skips it;</li>
 *   <li>on failure retries with backoff, and after the last attempt parks the message on {@code <topic>.DLT}.</li>
 * </ol>
 * Messages are handled one at a time, which keeps order. If the dead-letter topic cannot be written the
 * consumer restarts from the last acknowledged offset rather than skipping the message.
 */
public class EventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final Map<String, EventHandler> handlers;
    private final Map<String, Object> consumerProperties;
    private final KafkaSender<String, String> deadLetterSender;
    private final ProcessedEventRepository processed;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ConsumerProperties properties;
    private volatile Disposable running;

    public EventConsumer(
            Map<String, EventHandler> handlers,
            Map<String, Object> consumerProperties,
            KafkaSender<String, String> deadLetterSender,
            ProcessedEventRepository processed,
            TransactionTemplate transaction,
            ObjectMapper mapper,
            ConsumerProperties properties) {
        this.handlers = Map.copyOf(handlers);
        this.consumerProperties = consumerProperties;
        this.deadLetterSender = deadLetterSender;
        this.processed = processed;
        this.transaction = transaction;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (handlers.isEmpty()) {
            return;
        }
        ReceiverOptions<String, String> options = ReceiverOptions.<String, String>create(consumerProperties)
            .subscription(handlers.keySet())
            .commitInterval(Duration.ofSeconds(1))
            .commitBatchSize(100);
        log.info("Consuming {}", handlers.keySet());
        running = Flux.defer(() -> KafkaReceiver.create(options).receive())
            .concatMap(this::process, 1)
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(30))
                .doBeforeRetry(signal -> log.warn("Event consumer restarting after: {}", signal.failure().toString())))
            .subscribe();
    }

    private Mono<Void> process(ReceiverRecord<String, String> record) {
        String headerId = CorrelationId.sanitize(headerValue(record.headers().toArray(), CorrelationId.KAFKA_HEADER));
        return Mono.fromRunnable(() -> handleOnce(record, headerId))
            .subscribeOn(Schedulers.boundedElastic())
            .retryWhen(Retry.backoff(Math.max(0, properties.maxAttempts() - 1), properties.backoffInitial())
                .maxBackoff(properties.backoffMax())
                .filter(e -> !(e instanceof NonRetryableEventException))
                .doBeforeRetry(signal -> log.warn("Retrying {}-{}@{} after: {}", record.topic(), record.partition(), record.offset(), signal.failure().toString()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
            .onErrorResume(error -> deadLetter(record, error))
            .doOnSuccess(done -> record.receiverOffset().acknowledge())
            .contextWrite(Context.of(CorrelationId.CONTEXT_KEY, headerId))
            .then();
    }

    /** One attempt. Blocking: runs on a boundedElastic thread. */
    private void handleOnce(ReceiverRecord<String, String> record, String headerCorrelationId) {
        EventEnvelope event = parse(record.value());
        EventHandler handler = handlers.get(record.topic());
        String correlationId = CorrelationId.isValid(event.correlationId()) ? event.correlationId() : headerCorrelationId;
        try (var scope = CorrelationId.open(correlationId)) {
            if (processed.existsById(event.eventId())) {
                log.info("Skipping already processed event {} ({})", event.eventId(), event.eventType());
                return;
            }
            try {
                transaction.executeWithoutResult(status -> {
                    // flushed first: a concurrent duplicate fails here, before the handler does any work
                    processed.saveAndFlush(new ProcessedEvent(event.eventId(), record.topic()));
                    invoke(handler, event);
                });
            } catch (DataIntegrityViolationException e) {
                if (processed.existsById(event.eventId())) {
                    log.info("Skipping event {} handled concurrently elsewhere", event.eventId());
                    return;
                }
                throw e;
            }
        }
    }

    private EventEnvelope parse(String json) {
        try {
            EventEnvelope event = mapper.readValue(json, EventEnvelope.class);
            if (event.eventId() == null || event.eventId().isBlank()) {
                throw new NonRetryableEventException("Message has no eventId");
            }
            return event;
        } catch (JsonProcessingException | RuntimeException e) {
            if (e instanceof NonRetryableEventException nre) {
                throw nre;
            }
            throw new NonRetryableEventException("Message is not a valid event envelope", e);
        }
    }

    private static void invoke(EventHandler handler, EventEnvelope event) {
        try {
            handler.handle(event);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Handler failed for event " + event.eventId(), e);
        }
    }

    private Mono<Void> deadLetter(ReceiverRecord<String, String> record, Throwable error) {
        String topic = Topics.dlt(record.topic());
        log.error("Parking {}-{}@{} on {}: {}", record.topic(), record.partition(), record.offset(), topic, error.toString());
        List<Header> headers = new ArrayList<>(List.of(record.headers().toArray()));
        headers.add(text("dlt-original-topic", record.topic()));
        headers.add(text("dlt-original-partition", String.valueOf(record.partition())));
        headers.add(text("dlt-original-offset", String.valueOf(record.offset())));
        headers.add(text("dlt-exception-class", error.getClass().getName()));
        headers.add(text("dlt-exception-message", truncate(String.valueOf(error.getMessage()), 500)));
        var parked = new ProducerRecord<String, String>(topic, null, record.key(), record.value(), headers);
        return deadLetterSender.send(Mono.just(SenderRecord.create(parked, record.offset())))
            .next()
            .flatMap(result -> result.exception() != null ? Mono.<Void>error(result.exception()) : Mono.<Void>empty());
    }

    private static String headerValue(Header[] headers, String name) {
        for (Header header : headers) {
            if (header.key().equals(name) && header.value() != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Header text(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void stop() {
        if (running != null) {
            running.dispose();
            running = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
