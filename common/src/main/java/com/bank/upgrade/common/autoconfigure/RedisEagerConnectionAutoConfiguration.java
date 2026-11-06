package com.bank.upgrade.common.autoconfigure;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * For any service that uses Redis: connect at startup. Lettuce otherwise opens its shared connection lazily, with a
 * blocking wait, on first use, and in a WebFlux service that first use is on an event-loop thread, which must never
 * block. Connecting eagerly also means a service that cannot reach Redis fails to start, instead of failing later.
 */
@AutoConfiguration
@ConditionalOnClass(LettuceConnectionFactory.class)
public class RedisEagerConnectionAutoConfiguration {

    @Bean
    static BeanPostProcessor eagerRedisConnection() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LettuceConnectionFactory factory) {
                    factory.setEagerInitialization(true);
                }
                return bean;
            }
        };
    }
}
