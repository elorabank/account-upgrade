package com.bank.upgrade.common.correlation;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * One id that follows a business action across HTTP calls, Kafka messages and log lines.
 * Reactive code carries it in the Reactor context; blocking code (JPA) reads it from the logging MDC.
 */
public final class CorrelationId {

    public static final String HTTP_HEADER = "X-Correlation-Id";
    public static final String KAFKA_HEADER = "correlationId";
    public static final String CONTEXT_KEY = "correlationId";
    public static final String MDC_KEY = "correlationId";

    // Ids come from outside, so only plain characters are accepted (no log injection)
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }

    /** The candidate if it is a usable id, otherwise a fresh one. */
    public static String sanitize(String candidate) {
        return isValid(candidate) ? candidate : newId();
    }

    /** The id of the current blocking section, or null when none is open. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String currentOrNew() {
        String id = current();
        return id != null ? id : newId();
    }

    /** Makes {@code id} the current id until the returned scope is closed. */
    public static Scope open(String id) {
        String previous = MDC.get(MDC_KEY);
        if (id != null) {
            MDC.put(MDC_KEY, id);
        }
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
