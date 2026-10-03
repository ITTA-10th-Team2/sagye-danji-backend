package com.gyejoldanji.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.gyejoldanji.domain.auth.service.ServiceTokenService.IssuedTokens;
import com.gyejoldanji.global.config.TestJwtKeys;
import com.gyejoldanji.global.config.properties.AuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** 실제 RSA 키쌍으로 Access JWT 서명·claim·TTL과 Refresh 형식·해시를 검증한다. */
class ServiceTokenServiceTest {

    /** 소수 초가 있는 발급 기준 시각(잠금 이후 Clock 값을 마이크로초로 자른 값). */
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00.500000Z");
    private static final Instant SESSION_END = NOW.plusSeconds(1_209_600);
    private static final String SESSION_KEY = UUID.randomUUID().toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static KeyPair keyPair;
    private static ServiceTokenService service;

    @BeforeAll
    static void setUp() throws Exception {
        keyPair = TestJwtKeys.generate("RSA", 2048);
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setIssuer("test-issuer");
        properties.getJwt().setAudience("test-audience");
        properties.getJwt().setKeyId("test-key");
        service = new ServiceTokenService(properties, keyPair);
    }

    /** RS256·kid 헤더와 계약 claim을 담고, 실제 공개키로만 검증된다. 시각 claim은 정수 epoch seconds다. */
    @Test
    void signsAccessTokenWithContractClaims() throws Exception {
        IssuedTokens tokens = service.issue(42L, SESSION_KEY, NOW, SESSION_END);

        SignedJWT jwt = SignedJWT.parse(tokens.accessToken());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("test-key");
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
        RSAPublicKey otherKey = (RSAPublicKey) TestJwtKeys.generate("RSA", 2048).getPublic();
        assertThat(jwt.verify(new RSASSAVerifier(otherKey))).isFalse();

        JsonNode claims = JSON.readTree(jwt.getPayload().toString());
        assertThat(claims.path("iss").stringValue()).isEqualTo("test-issuer");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("test-audience");
        assertThat(claims.path("sub").stringValue()).isEqualTo("42");
        assertThat(claims.path("sid").stringValue()).isEqualTo(SESSION_KEY);
        assertThat(claims.path("token_use").stringValue()).isEqualTo("access");
        assertThat(UUID.fromString(claims.path("jti").stringValue())).isNotNull();
        for (String name : new String[] {"iat", "nbf", "exp"}) {
            assertThat(claims.path(name).isIntegralNumber()).as(name).isTrue();
        }
        assertThat(claims.path("iat").longValue()).isEqualTo(Instant.parse("2026-10-03T12:00:00Z").getEpochSecond());
        assertThat(claims.path("nbf").longValue()).isEqualTo(claims.path("iat").longValue());
        assertThat(claims.path("exp").longValue()).isEqualTo(Instant.parse("2026-10-03T12:15:00Z").getEpochSecond());
    }

    /** now가 12:00:00.5이고 Access 900초면 exp는 12:15:00, expiresIn은 899다. Refresh TTL은 세션 끝까지다. */
    @Test
    void truncatesFractionalSecondsInResponseTtl() {
        IssuedTokens tokens = service.issue(1L, SESSION_KEY, NOW, SESSION_END);

        assertThat(tokens.expiresIn()).isEqualTo(899);
        assertThat(tokens.refreshExpiresIn()).isEqualTo(1_209_600);
    }

    /** 세션이 Access보다 먼저 끝나면 Access도 세션 만료(초 내림)에서 끝난다. 세션 만료는 연장하지 않는다. */
    @Test
    void capsAccessAtSessionExpiry() throws Exception {
        Instant sessionEnd = Instant.parse("2026-10-03T12:05:00.700000Z");

        IssuedTokens tokens = service.issue(1L, SESSION_KEY, NOW, sessionEnd);

        assertThat(SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(Instant.parse("2026-10-03T12:05:00Z"));
        assertThat(tokens.expiresIn()).isEqualTo(299);
        assertThat(tokens.refreshExpiresIn()).isEqualTo(300);
    }

    /** 내림 결과 응답 TTL이 1초 미만이면 발급하지 않는다. */
    @Test
    void refusesWhenResponseTtlIsBelowOneSecond() {
        assertThatIllegalStateException()
                .isThrownBy(() -> service.issue(1L, SESSION_KEY, NOW, Instant.parse("2026-10-03T12:00:01.400000Z")));
        assertThatIllegalStateException().isThrownBy(() -> service.issue(1L, SESSION_KEY, NOW, NOW));
    }

    /** Refresh는 43자 Base64url(32바이트)이고, 해시는 응답 원문 문자열 UTF-8의 SHA-256이다. */
    @Test
    void createsRefreshTokenAndHashOfResponseString() throws Exception {
        IssuedTokens tokens = service.issue(1L, SESSION_KEY, NOW, SESSION_END);

        assertThat(tokens.refreshToken()).matches("[A-Za-z0-9_-]{43}");
        byte[] randomBytes = Base64.getUrlDecoder().decode(tokens.refreshToken());
        assertThat(randomBytes).hasSize(32);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        assertThat(tokens.refreshTokenHash())
                .hasSize(32)
                .isEqualTo(sha256.digest(tokens.refreshToken().getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(sha256.digest(randomBytes));
    }

    /** 발급마다 Access(jti 포함)·Refresh·해시가 모두 다르다. */
    @Test
    void issuesDistinctValuesEachTime() throws Exception {
        IssuedTokens first = service.issue(1L, SESSION_KEY, NOW, SESSION_END);
        IssuedTokens second = service.issue(1L, SESSION_KEY, NOW, SESSION_END);

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(SignedJWT.parse(second.accessToken()).getJWTClaimsSet().getJWTID())
                .isNotEqualTo(SignedJWT.parse(first.accessToken()).getJWTClaimsSet().getJWTID());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.refreshTokenHash()).isNotEqualTo(first.refreshTokenHash());
    }

    /** 결과 객체의 문자열 표현에 토큰이 남지 않는다. */
    @Test
    void hidesTokensInToString() {
        IssuedTokens tokens = service.issue(1L, SESSION_KEY, NOW, SESSION_END);

        assertThat(tokens.toString()).doesNotContain(tokens.accessToken(), tokens.refreshToken());
    }
}
