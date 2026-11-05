package com.bank.upgrade.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AutoConfigurationSwitchesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class, SchedulerLockAutoConfiguration.class));

    @Test
    void sharedEntitiesAreNotScannedUnlessEnabled() {
        runner.run(ctx -> assertThat(AutoConfigurationPackages.has(ctx)).isFalse());
    }

    @Test
    void sharedEntitiesAreScannedWhenEnabled() {
        runner.withPropertyValues("bank.outbox.enabled=true").run(ctx ->
            assertThat(AutoConfigurationPackages.get(ctx)).contains("com.bank.upgrade.common.persistence.outbox"));
    }

    @Test
    void schedulerLockIsOffUnlessEnabled() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LockProvider.class));
    }
}
