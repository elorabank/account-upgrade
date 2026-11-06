package com.bank.upgrade.audit.config;

import com.bank.upgrade.audit.archive.AuditArchiveProperties;
import com.bank.upgrade.audit.archive.AuditArchiveRepository;
import com.bank.upgrade.audit.archive.AuditArchiver;
import com.bank.upgrade.audit.archive.FileSystemArchiveStore;
import com.bank.upgrade.audit.archive.JdbcAuditArchiveRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Moving months older than {@code bank.audit.archive.keep} out of the database into archive files. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AuditArchiveProperties.class)
public class AuditArchiveConfig {

    @Bean
    AuditArchiveRepository auditArchiveRepository(JdbcTemplate jdbc) {
        return new JdbcAuditArchiveRepository(jdbc);
    }

    /** Off with {@code bank.audit.archive.enabled=false}: the whole trail then stays in the database. */
    @Bean
    @ConditionalOnProperty(prefix = "bank.audit.archive", name = "enabled", havingValue = "true", matchIfMissing = true)
    AuditArchiver auditArchiver(AuditArchiveRepository db, AuditArchiveProperties properties, Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        return new AuditArchiver(db, new FileSystemArchiveStore(Path.of(properties.dir())), properties, clock,
            meters.getIfAvailable(SimpleMeterRegistry::new));
    }
}
