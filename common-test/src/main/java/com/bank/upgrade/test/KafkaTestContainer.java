package com.bank.upgrade.test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One throw-away single-node Kafka per test JVM (started on first use, removed when the JVM exits).
 * It is a plain container on a fixed host port that is also the advertised address, the same way the
 * docker-compose broker works, so it does not depend on Testcontainers' own listener rewriting.
 */
public final class KafkaTestContainer {

    private static final int HOST_PORT = freePort();
    private static final GenericContainer<?> KAFKA = new GenericContainer<>(DockerImageName.parse("apache/kafka:3.9.0"))
        .withEnv("KAFKA_NODE_ID", "1")
        .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
        .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
        .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + HOST_PORT)
        .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
        .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
        .withEnv("KAFKA_HEAP_OPTS", "-Xmx512m")
        .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1));

    static {
        KAFKA.setPortBindings(List.of(HOST_PORT + ":9092"));
        KAFKA.start();
    }

    private KafkaTestContainer() {}

    public static String bootstrapServers() {
        return "localhost:" + HOST_PORT;
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port for the Kafka test container", e);
        }
    }
}
