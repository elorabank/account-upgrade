package com.bank.upgrade.upgrade.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * At most {@code perSecond} core-system calls per second across ALL copies of the service, counted in Redis: each
 * call adds one to the counter for the current second ({@code INCR}); over the limit, it waits for the next second.
 * This is a fixed-window limiter: simple and exact per second, though a burst can straddle two seconds.
 * <p>
 * If Redis cannot be reached the call goes ahead (fail open): each copy is still held back by its own
 * {@code concurrency}, and the run is not stopped by a problem in a helper system.
 */
public class RedisCoreCallLimiter implements CoreCallLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisCoreCallLimiter.class);
    private static final String KEY = "account-upgrade:core-calls:";

    private final ReactiveStringRedisTemplate redis;
    private final int perSecond;
    private final Clock clock;
    private final AtomicBoolean warned = new AtomicBoolean();

    public RedisCoreCallLimiter(ReactiveStringRedisTemplate redis, int perSecond, Clock clock) {
        this.redis = redis;
        this.perSecond = perSecond;
        this.clock = clock;
    }

    @Override
    public Mono<Void> acquire() {
        return Mono.defer(() -> {
            long now = clock.millis();
            long second = now / 1000;
            String key = KEY + second;
            return redis.opsForValue().increment(key)
                .flatMap(count -> {
                    Mono<Boolean> expiry = count == 1 ? redis.expire(key, Duration.ofSeconds(10)) : Mono.just(true);
                    if (count <= perSecond) {
                        return expiry.then();
                    }
                    // Over the limit: wait for the next second (with a little jitter so copies do not all wake together)
                    long wait = (second + 1) * 1000 - now + ThreadLocalRandom.current().nextInt(20);
                    return expiry.then(Mono.delay(Duration.ofMillis(wait))).then(acquire());
                });
        }).onErrorResume(e -> {
            if (warned.compareAndSet(false, true)) {
                log.warn("Shared core-call limit unavailable (Redis), going ahead without it: {}", e.toString());
            }
            return Mono.empty();
        });
    }
}
