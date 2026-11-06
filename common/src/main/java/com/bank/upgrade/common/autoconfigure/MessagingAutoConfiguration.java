package com.bank.upgrade.common.autoconfigure;

import com.bank.upgrade.common.messaging.ConsumerProperties;
import com.bank.upgrade.common.messaging.EventConsumer;
import com.bank.upgrade.common.messaging.EventHandler;
import com.bank.upgrade.common.messaging.EventSubscription;
import com.bank.upgrade.common.messaging.KafkaOutboxSender;
import com.bank.upgrade.common.messaging.KafkaClientProperties;
import com.bank.upgrade.common.messaging.OutboxPoller;
import com.bank.upgrade.common.messaging.OutboxProperties;
import com.bank.upgrade.common.messaging.OutboxSender;
import com.bank.upgrade.common.messaging.OutboxService;
import com.bank.upgrade.common.persistence.BlockingJpa;
import com.bank.upgrade.common.persistence.outbox.OutboxEventRepository;
import com.bank.upgrade.common.persistence.processed.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.MicrometerProducerListener;
import reactor.kafka.sender.SenderOptions;

/**
 * Kafka plumbing for the services, each part switched on by its own property:
 * <ul>
 *   <li>always (when {@code bank.kafka.bootstrap-servers} is set): a {@code KafkaSender} and {@link BlockingJpa};</li>
 *   <li>{@code bank.outbox.enabled}: {@link OutboxService} and the {@link OutboxPoller};</li>
 *   <li>{@code bank.processed-events.enabled}: the {@link EventConsumer} for every {@link EventSubscription} bean.</li>
 * </ul>
 * Their metrics go to the service's {@link MeterRegistry} (Prometheus), or nowhere when there is none.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration",
    "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"})
@ConditionalOnClass(KafkaSender.class)
@EnableConfigurationProperties({KafkaClientProperties.class, OutboxProperties.class, ConsumerProperties.class})
public class MessagingAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty("bank.kafka.bootstrap-servers")
    KafkaSender<String, String> bankKafkaSender(KafkaClientProperties kafka, ObjectProvider<MeterRegistry> meters) {
        return KafkaSender.create(SenderOptions.<String, String>create(kafka.producerConfig())
            .producerListener(new MicrometerProducerListener(registry(meters))));
    }

    static MeterRegistry registry(ObjectProvider<MeterRegistry> meters) {
        return meters.getIfAvailable(SimpleMeterRegistry::new);
    }

    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    @ConditionalOnMissingBean
    BlockingJpa blockingJpa(PlatformTransactionManager transactionManager) {
        return new BlockingJpa(transactionManager);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bank.outbox", name = "enabled", havingValue = "true")
    static class Outbox {

        @Bean
        OutboxService outboxService(OutboxEventRepository repository, ObjectMapper mapper, Environment env) {
            return new OutboxService(repository, mapper, env.getProperty("spring.application.name", "service"));
        }

        @Bean
        @ConditionalOnMissingBean
        OutboxSender outboxSender(KafkaSender<String, String> kafkaSender, OutboxProperties properties) {
            return new KafkaOutboxSender(kafkaSender, properties.sendTimeout());
        }

        @Bean
        OutboxPoller outboxPoller(OutboxEventRepository repository, OutboxSender sender,
                PlatformTransactionManager transactionManager, OutboxProperties properties, ObjectProvider<MeterRegistry> meters) {
            return new OutboxPoller(repository, sender, new TransactionTemplate(transactionManager), properties, registry(meters));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "bank.processed-events", name = "enabled", havingValue = "true")
    static class Consumer {

        @Bean
        EventConsumer eventConsumer(
                ObjectProvider<EventSubscription> subscriptions,
                KafkaClientProperties kafka,
                KafkaSender<String, String> kafkaSender,
                ProcessedEventRepository processed,
                PlatformTransactionManager transactionManager,
                ObjectMapper mapper,
                ConsumerProperties properties,
                ObjectProvider<MeterRegistry> meters) {
            Map<String, EventHandler> handlers = new LinkedHashMap<>();
            subscriptions.orderedStream().forEach(s -> {
                if (handlers.put(s.topic(), s.handler()) != null) {
                    throw new IllegalStateException("Two handlers subscribe to topic " + s.topic());
                }
            });
            return new EventConsumer(handlers, kafka.consumerConfig(), kafkaSender, processed,
                new TransactionTemplate(transactionManager), mapper, properties, registry(meters));
        }
    }
}
