package com.bank.upgrade.test;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * Spring Security draws OAuth2 state / nonce / PKCE values from SecureRandom, which reads /dev/urandom.
 * That read never waits for entropy, so it is allowed; every other blocking call still fails the test.
 */
public class SecureRandomBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("java.security.SecureRandom", "nextBytes");
    }
}
