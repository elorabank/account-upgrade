package com.bank.upgrade.common.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka client settings for every service, under {@code bank.kafka}.
 * <pre>
 * bank.kafka.bootstrap-servers   brokers
 * bank.kafka.group-id            consumer group (defaults to the application name)
 * bank.kafka.properties.*        any Kafka client setting, for producer and consumer, e.g. security.protocol
 * bank.kafka.producer.*          producer-only settings, e.g. linger.ms
 * bank.kafka.consumer.*          consumer-only settings, e.g. max.poll.records
 * bank.kafka.sasl.*              this service's own login: username, password, mechanism
 * </pre>
 * Safe defaults are built in: producers use acks=all with idempotence, consumers never auto-commit
 * (the consumer template acknowledges offsets after the database commit) and start from the earliest offset.
 */
@ConfigurationProperties("bank.kafka")
public record KafkaClientProperties(
        @DefaultValue("localhost:9092") String bootstrapServers,
        String groupId,
        @DefaultValue Map<String, String> properties,
        @DefaultValue Map<String, String> producer,
        @DefaultValue Map<String, String> consumer,
        @DefaultValue Sasl sasl) {

    public record Sasl(String username, String password, @DefaultValue("SCRAM-SHA-512") String mechanism) {

        void applyTo(Map<String, Object> config) {
            if (username == null || username.isBlank()) {
                return;
            }
            String module = mechanism.startsWith("SCRAM")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
            config.put("sasl.mechanism", mechanism);
            config.put("sasl.jaas.config",
                module + " required username=\"" + escape(username) + "\" password=\"" + escape(password == null ? "" : password) + "\";");
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public Map<String, Object> producerConfig() {
        Map<String, Object> config = common();
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("acks", "all");
        config.put("enable.idempotence", "true");
        config.putAll(producer);
        return config;
    }

    public Map<String, Object> consumerConfig() {
        Map<String, Object> config = common();
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("group.id", groupId == null || groupId.isBlank() ? "default-group" : groupId);
        config.put("auto.offset.reset", "earliest");
        config.put("enable.auto.commit", "false");
        config.putAll(consumer);
        return config;
    }

    private Map<String, Object> common() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("bootstrap.servers", bootstrapServers);
        config.putAll(properties);
        sasl.applyTo(config);
        return config;
    }
}
