package com.bank.upgrade.common.messaging;

import com.bank.upgrade.common.correlation.CorrelationId;
import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.persistence.processed.ProcessedEvent;
import com.bank.upgrade.common.persistence.processed.ProcessedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import reactor.kafka.receiver.MicrometerConsumerListener;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.util.context.Context;
import reactor.util.retry.Retry;

/**
 * Reads the subscribed topics and, for each batch of messages from one partition:
 * <ol>
 *   <li>skips events whose id was already processed (also a repeat inside the batch);</li>
 *   <li>in ONE database transaction records the new event ids and runs the handler for each, in order;</li>
 *   <li>only then acknowledges the Kafka offsets, so a crash replays the batch and step 1 skips what was done.</li>
 * </ol>
 * One commit per batch instead of one per event is what lets a busy topic go fast; a quiet topic still gets each
 * message within {@code batchWait}. If a batch fails it is rolled back and handled again one event at a time: each
 * event is retried with backoff, and after the last attempt parked on {@code <topic>.DLT}, so one bad event never
 * holds back the others. (An event in a failed batch is therefore tried once more than {@code maxAttempts}.)
 * <p>
 * Each partition is handled in order, one message at a time; different partitions are handled in parallel. Events
 * are keyed by what they are about (for example the account), so everything about one account stays in order while
 * a topic with N partitions is worked on N at a time. If the dead-letter topic cannot be written the consumer
 * restarts from the last acknowledged offset rather than skipping the message.
 * <p>
 * Metrics: {@code bank.events.consumed} (by topic and result: processed, duplicate, dead-lettered),
 * {@code bank.events.handling} (time per batch), and the Kafka consumer's own metrics, among them the lag per partition.
 */
public class EventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    /** groupBy needs a slot per partition this consumer is assigned; far more than any topic here has. */
    private static final int MAX_PARTITIONS = 1024;

    private final Map<String, EventHandler> handlers;
    private final Map<String, Object> consumerProperties;
    private final KafkaSender<String, String> deadLetterSender;
    private final ProcessedEventRepository processed;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ConsumerProperties properties;
    private final MeterRegistry meters;
    private volatile Disposable running;

    public EventConsumer(
            Map<String, EventHandler> handlers,
            Map<String, Object> consumerProperties,
            KafkaSender<String, String> deadLetterSender,
            ProcessedEventRepository processed,
            TransactionTemplate transaction,
            ObjectMapper mapper,
            ConsumerProperties properties,
            MeterRegistry meters) {
        this.handlers = Map.copyOf(handlers);
        this.consumerProperties = consumerProperties;
        this.deadLetterSender = deadLetterSender;
        this.processed = processed;
        this.transaction = transaction;
        this.mapper = mapper;
        this.properties = properties;
        this.meters = meters;
    }

    @Override
    public void start() {
        if (handlers.isEmpty()) {
            return;
        }
        ReceiverOptions<String, String> options = ReceiverOptions.<String, String>create(consumerProperties)
            .subscription(handlers.keySet())
            .commitInterval(Duration.ofSeconds(1))
            .commitBatchSize(100)
            .consumerListener(new MicrometerConsumerListener(meters));
        log.info("Consuming {}", handlers.keySet());
        running = Flux.defer(() -> KafkaReceiver.create(options).receive())
            .groupBy(record -> record.receiverOffset().topicPartition())
            .flatMap(partition -> partition
                    .bufferTimeout(Math.max(1, properties.batchSize()), properties.batchWait(), true)
                    .concatMap(this::processBatch, 1),
                MAX_PARTITIONS)
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(30))
                .doBeforeRetry(signal -> log.warn("Event consumer restarting after: {}", signal.failure().toString())))
            .subscribe();
    }

    /** A batch in one transaction; if that fails, the same records one by one with retries and dead-lettering. */
    Mono<Void> processBatch(List<ReceiverRecord<String, String>> records) {
        if (records.size() == 1) {
            return process(records.get(0));
        }
        return Mono.fromRunnable(() -> handleBatch(records))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess(done -> records.forEach(r -> r.receiverOffset().acknowledge()))
            .onErrorResume(error -> {
                log.warn("Batch of {} from {}-{} failed ({}), handling them one by one",
                    records.size(), records.get(0).topic(), records.get(0).partition(), error.toString());
                return Flux.fromIterable(records).concatMap(this::process, 1).then();
            })
            .then();
    }

    /** One attempt at a whole batch. Blocking: runs on a boundedElastic thread. Any failure rolls back all of it. */
    private void handleBatch(List<ReceiverRecord<String, String>> records) {
        String batchTopic = records.get(0).topic();
        long started = System.nanoTime();
        Map<String, EventEnvelope> events = new LinkedHashMap<>();
        Map<String, String> topics = new HashMap<>();
        Map<String, String> headerIds = new HashMap<>();
        for (ReceiverRecord<String, String> record : records) {
            EventEnvelope event = parse(record.value());   // a bad message fails the batch; the one-by-one pass parks it
            events.putIfAbsent(event.eventId(), event);
            topics.putIfAbsent(event.eventId(), record.topic());
            headerIds.putIfAbsent(event.eventId(), CorrelationId.sanitize(headerValue(record.headers().toArray(), CorrelationId.KAFKA_HEADER)));
        }
        Integer handled = transaction.execute(status -> {
            Set<String> done = new HashSet<>();
            processed.findAllById(events.keySet()).forEach(p -> done.add(p.getEventId()));
            var fresh = events.values().stream().filter(e -> !done.contains(e.eventId())).toList();
            // flushed first: a concurrent duplicate fails here, before any handler does work
            processed.saveAllAndFlush(fresh.stream().map(e -> new ProcessedEvent(e.eventId(), topics.get(e.eventId()))).toList());
            for (EventEnvelope event : fresh) {
                String topic = topics.get(event.eventId());
                String correlationId = CorrelationId.isValid(event.correlationId()) ? event.correlationId() : headerIds.get(event.eventId());
                try (var scope = CorrelationId.open(correlationId)) {
                    invoke(handlers.get(topic), event);
                }
            }
            return fresh.size();
        });
        int fresh = handled == null ? 0 : handled;
        handlingTimer(batchTopic).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        count(batchTopic, "processed", fresh);
        count(batchTopic, "duplicate", records.size() - fresh);
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
                count(record.topic(), "duplicate", 1);
                return;
            }
            long started = System.nanoTime();
            try {
                transaction.executeWithoutResult(status -> {
                    // flushed first: a concurrent duplicate fails here, before the handler does any work
                    processed.saveAndFlush(new ProcessedEvent(event.eventId(), record.topic()));
                    invoke(handler, event);
                });
            } catch (DataIntegrityViolationException e) {
                if (processed.existsById(event.eventId())) {
                    log.info("Skipping event {} handled concurrently elsewhere", event.eventId());
                    count(record.topic(), "duplicate", 1);
                    return;
                }
                throw e;
            }
            handlingTimer(record.topic()).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            count(record.topic(), "processed", 1);
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
            .flatMap(result -> result.exception() != null ? Mono.<Void>error(result.exception()) : Mono.<Void>empty())
            .doOnSuccess(done -> count(record.topic(), "dead-lettered", 1));
    }

    private void count(String topic, String result, int n) {
        if (n > 0) {
            Counter.builder("bank.events.consumed").description("Events read from Kafka, by what happened to them")
                .tag("topic", topic).tag("result", result).register(meters).increment(n);
        }
    }

    private Timer handlingTimer(String topic) {
        return Timer.builder("bank.events.handling").description("Time to handle events in one database transaction")
            .tag("topic", topic).register(meters);
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
