package com.bank.upgrade.test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Makes Okta-shaped access tokens signed with a throw-away key, and serves the matching JWKS over local HTTP. */
public final class TestJwt {

    public static final String ISSUER = "https://issuer.test/oauth2/default";
    public static final String AUDIENCE = "api://account-upgrade";

    private final RSAKey signingKey;
    private final HttpServer jwks;

    public TestJwt() {
        this(generate("key-1"));
    }

    private TestJwt(RSAKey key) {
        try {
            this.signingKey = key;
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/jwks", ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            jwks.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String jwkSetUri() {
        return "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks";
    }

    public void stop() {
        jwks.stop(0);
    }

    /** A valid token for a signed-in user in the given Okta groups. */
    public String user(String... groups) {
        return sign(signingKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
            Map.of("groups", List.of(groups), "uid", "00u1", "email", "pat@bank.test"));
    }

    /** A valid client-credentials token carrying the given scopes (no groups, no uid). */
    public String service(String clientId, String... scopes) {
        return sign(signingKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
            Map.of("scp", List.of(scopes), "cid", clientId));
    }

    public String custom(String issuer, String audience, Instant expires) {
        return sign(signingKey, issuer, audience, expires, Map.of("groups", List.of("AU_OPS_ADMIN")));
    }

    /** Signed with a different key than the one the JWKS publishes. */
    public String signedByStranger() {
        return sign(generate("key-1"), ISSUER, AUDIENCE, Instant.now().plusSeconds(300), Map.of("groups", List.of("AU_OPS_ADMIN")));
    }

    private static String sign(RSAKey key, String issuer, String audience, Instant expires, Map<String, Object> claims) {
        try {
            var b = new JWTClaimsSet.Builder().issuer(issuer).subject("sub-1").audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expires));
            claims.forEach(b::claim);
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), b.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate(String kid) {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) kp.getPublic()).privateKey((RSAPrivateKey) kp.getPrivate()).keyID(kid).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
