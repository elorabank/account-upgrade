package com.bank.upgrade.upgrade.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The shared per-second limit, with Redis replaced by a map of counters. */
class RedisCoreCallLimiterTest {

    final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    /** 12:00:00.900, so the next second starts in 100 ms */
    final Clock clock = Clock.fixed(Instant.parse("2027-01-15T12:00:00.900Z"), ZoneOffset.UTC);

    @BeforeEach
    void redisAsAMap() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(inv ->
            Mono.just(counters.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong()).incrementAndGet()));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    }

    @Test
    void callsWithinTheLimitGoAheadAtOnce() {
        var limiter = new RedisCoreCallLimiter(redis, 3, clock);
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(limiter.acquire()).expectComplete().verify(Duration.ofMillis(200));
        }
    }

    @Test
    void aCallOverTheLimitWaitsForTheNextSecond() {
        var limiter = new RedisCoreCallLimiter(redis, 1, clock);
        StepVerifier.create(limiter.acquire()).expectComplete().verify(Duration.ofMillis(200));

        // The clock stands still at .900, so this second stays full; the call must wait about 100 ms and ask again
        StepVerifier.withVirtualTime(limiter::acquire)
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(90))
            .thenCancel()
            .verify();
    }

    @Test
    void theCountersAreSharedThroughRedisSoTwoCopiesTogetherStayUnderTheLimit() {
        var copyA = new RedisCoreCallLimiter(redis, 2, clock);
        var copyB = new RedisCoreCallLimiter(redis, 2, clock);
        StepVerifier.create(copyA.acquire()).expectComplete().verify(Duration.ofMillis(200));
        StepVerifier.create(copyB.acquire()).expectComplete().verify(Duration.ofMillis(200));

        StepVerifier.withVirtualTime(copyA::acquire).expectSubscription().expectNoEvent(Duration.ofMillis(90)).thenCancel().verify();
    }

    @Test
    void withoutRedisTheCopiesGoAheadRatherThanStop() {
        when(values.increment(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("refused")));
        var limiter = new RedisCoreCallLimiter(redis, 1, clock);
        StepVerifier.create(limiter.acquire()).expectComplete().verify(Duration.ofMillis(200));
        StepVerifier.create(limiter.acquire()).expectComplete().verify(Duration.ofMillis(200));
    }
}
