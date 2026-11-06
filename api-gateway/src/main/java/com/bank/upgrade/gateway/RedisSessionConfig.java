package com.bank.upgrade.gateway;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Login sessions are stored in Redis by Spring Session (see spring.session.* in application.yml).
 * <p>
 * Lettuce opens its shared Redis connection lazily, with a blocking wait, the first time it is used. In a WebFlux app
 * that first use is a request on an event-loop thread, which must never block. Connecting eagerly moves that wait
 * to startup, and also means a gateway that cannot reach Redis fails to start instead of failing its first login.
 */
@Configuration(proxyBeanMethods = false)
class RedisSessionConfig {

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
