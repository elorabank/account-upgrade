package com.bank.upgrade.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.TestPropertySource;

/**
 * A test of a service's REST API without a database, Kafka or Redis: the named controllers, the web layer and the real
 * security chain with its access rules. Tokens come from {@link TestJwt#shared()} and are checked for signature,
 * issuer, audience and expiry as in production. Everything behind the controllers is mocked by the test
 * ({@code @MockitoBean}); {@code BlockingJpa} runs on a transaction manager without a database.
 * <pre>
 *   &#64;ApiTest(UpgradesController.class)
 *   class UpgradeApiTest {
 *       &#64;MockitoBean UpgradeRequestRepository requests;
 *       &#64;Autowired WebTestClient client;
 *       ...
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@WebFluxTest
@ImportAutoConfiguration   // the list is in META-INF/spring/com.bank.upgrade.test.ApiTest.imports
@Import(ApiTestConfiguration.class)
@TestPropertySource(properties = {
    // The API alone: no outbox poller or Kafka consumer (they need the database)
    "bank.outbox.enabled=false",
    "bank.processed-events.enabled=false",
    "bank.scheduler-lock.enabled=false"
})
public @interface ApiTest {

    /** The controllers to test. */
    @AliasFor(annotation = WebFluxTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
