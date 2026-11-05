package com.bank.upgrade.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Adds the shared OUTBOX_EVENT entity and repository to the service's JPA scan.
 * Opt in with {@code bank.outbox.enabled=true}; services without that table leave it off.
 * Runs before the JPA repository setup because that reads the scan packages while the context loads.
 */
@AutoConfiguration(beforeName = {
    "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"})
@ConditionalOnProperty(prefix = "bank.outbox", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
@AutoConfigurationPackage(basePackages = "com.bank.upgrade.common.persistence.outbox")
public class OutboxAutoConfiguration {}
