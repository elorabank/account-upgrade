package com.bank.upgrade.common.autoconfigure;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cluster-wide scheduler locks in the SHEDLOCK table, using the database clock. Opt in with {@code bank.scheduler-lock.enabled=true}. */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnProperty(prefix = "bank.scheduler-lock", name = "enabled", havingValue = "true")
@ConditionalOnClass(LockProvider.class)
@EnableSchedulerLock(defaultLockAtMostFor = "PT2H")
public class SchedulerLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
