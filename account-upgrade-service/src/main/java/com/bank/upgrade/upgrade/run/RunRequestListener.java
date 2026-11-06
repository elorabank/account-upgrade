package com.bank.upgrade.upgrade.run;

import com.bank.upgrade.common.events.EventEnvelope;
import com.bank.upgrade.common.events.Topics;
import com.bank.upgrade.common.events.UpgradeRunRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

/**
 * Hears "run the upgrade now" on {@link Topics#UPGRADE_RUN_REQUESTED}. Unlike normal consumers, every copy reads in
 * its OWN consumer group (service name + instance id), so every copy gets every request: a broadcast, not a queue.
 * It starts at the end of the topic, so a copy that starts later does not replay old requests.
 */
public class RunRequestListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunRequestListener.class);

    private final Map<String, Object> consumerConfig;
    private final ObjectMapper mapper;
    private final Consumer<String> onRunRequested;
    private volatile Disposable running;

    /** @param baseConsumerConfig the service's normal Kafka consumer settings; group id and offset reset are replaced */
    public RunRequestListener(Map<String, Object> baseConsumerConfig, String groupId, ObjectMapper mapper, Consumer<String> onRunRequested) {
        var config = new HashMap<>(baseConsumerConfig);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        this.consumerConfig = config;
        this.mapper = mapper;
        this.onRunRequested = onRunRequested;
    }

    @Override
    public void start() {
        var options = ReceiverOptions.<String, String>create(consumerConfig)
            .subscription(List.of(Topics.UPGRADE_RUN_REQUESTED))
            .commitInterval(Duration.ofSeconds(1));
        running = Flux.defer(() -> KafkaReceiver.create(options).receiveAutoAck())
            .concatMap(batch -> batch)
            .doOnNext(record -> onMessage(record.value()))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
            .subscribe();
    }

    /** One "run now" message: start (or join) that run. Anything unreadable is logged and skipped. */
    void onMessage(String value) {
        try {
            var event = mapper.readValue(value, EventEnvelope.class);
            String runId = event.dataAs(mapper, UpgradeRunRequested.class).runId();
            log.info("Run requested: {}", runId);
            onRunRequested.accept(runId);
        } catch (Exception e) {
            log.warn("Ignoring an unreadable run request: {}", e.toString());
        }
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
}
