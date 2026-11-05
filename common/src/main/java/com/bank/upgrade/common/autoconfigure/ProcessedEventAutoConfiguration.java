package com.bank.upgrade.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Adds the shared PROCESSED_EVENT entity and repository (consumer de-duplication) to the service's JPA scan.
 * Opt in with {@code bank.processed-events.enabled=true}. Same ordering reason as {@link OutboxAutoConfiguration}.
 */
@AutoConfiguration(beforeName = {
    "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"})
@ConditionalOnProperty(prefix = "bank.processed-events", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
@AutoConfigurationPackage(basePackages = "com.bank.upgrade.common.persistence.processed")
public class ProcessedEventAutoConfiguration {}
