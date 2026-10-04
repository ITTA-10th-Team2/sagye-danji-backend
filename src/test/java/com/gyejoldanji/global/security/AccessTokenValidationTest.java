package com.gyejoldanji.global.security;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.global.config.JwtKeyConfig;
import com.gyejoldanji.global.config.TestJwtKeys;
import com.gyejoldanji.global.config.properties.AuthProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 JwtDecoder Bean(현재 공개키·RS256·{@link AccessTokenValidator})과 {@link JsonSecurityErrorHandler}를 실제 RSA 키로
 * 검증한다(T09). 정상·변조·누락·타입 오류·시간 경계·복합 오류의 응답 코드를 확인한다.
 *
 * <p>오류 분류는 JwtAuthenticationProvider처럼 디코더 예외를 InvalidBearerTokenException의 원인으로 감싸 확인한다.
 */
class AccessTokenValidationTest {

    /** 소수 초가 있는 검증 기준 시각. */
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00.250Z");
    private static final long NOW_S = NOW.getEpochSecond();
    private static final String SID = UUID.randomUUID().toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Object ABSENT = new Object();

    private static KeyPair keyPair;
    private static AuthProperties properties;
    private static JsonSecurityErrorHandler handler;

    @BeforeAll
    static void setUp() throws Exception {
        keyPair = TestJwtKeys.generate("RSA", 2048);
        properties = new AuthProperties();
        properties.getJwt().setIssuer("test-issuer");
        properties.getJwt().setAudience("test-audience");
        properties.getJwt().setKeyId("test-key");
        handler = new JsonSecurityErrorHandler(JSON);
    }

    /** 실제 발급 서비스의 토큰은 그대로 통과하고 claim이 문자열·Instant로 보인다. */
    @Test
    void acceptsTokenIssuedByServiceTokenService() throws Exception {
        String token = new ServiceTokenService(properties, keyPair).issue(42L, SID, NOW, NOW.plusSeconds(1_209_600))
                .accessToken();

        Jwt jwt = decoderAt(NOW).decode(token);

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(SID);
        assertThat(jwt.getExpiresAt()).isEqualTo(Instant.ofEpochSecond(NOW_S + 900));
        assertThat(outcome(token, NOW)).isEqualTo("OK");
    }

    /** now >= exp부터 만료이며 유예가 없다. 만료만 문제면 AUTH_001이다. */
    @Test
    void expiresExactlyAtExpWithoutSkew() throws Exception {
        String token = signed(claims());
        Instant exp = Instant.ofEpochSecond(NOW_S + 900);

        assertThat(outcome(token, exp.minusNanos(1))).isEqualTo("OK");
        assertThat(outcome(token, exp)).isEqualTo("AUTH_001");
        assertThat(outcome(token, exp.plusSeconds(60))).isEqualTo("AUTH_001");
        assertThat(outcome(token, exp.plusSeconds(86_400))).isEqualTo("AUTH_001");
    }

    /** 만료와 다른 오류가 겹치면 AUTH_002가 우선한다. */
    @Test
    void invalidTakesPrecedenceOverExpiry() throws Exception {
        Instant afterExp = Instant.ofEpochSecond(NOW_S + 901);

        assertThat(outcome(tamperSignature(signed(claims())), afterExp)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(claims("iss", "other-issuer")), afterExp)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(claims("token_use", "refresh")), afterExp)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(claims("iat", new BigDecimal(NOW_S + ".5"))), afterExp)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(header("other-key"), claims()), afterExp)).isEqualTo("AUTH_002");
    }

    /** 다른 키 서명·서명 변조·payload 변조는 AUTH_002다. */
    @Test
    void rejectsWrongOrTamperedSignature() throws Exception {
        KeyPair other = TestJwtKeys.generate("RSA", 2048);
        assertThat(outcome(sign(header("test-key"), claims(), new RSASSASigner(other.getPrivate())), NOW))
                .isEqualTo("AUTH_002");
        assertThat(outcome(tamperSignature(signed(claims())), NOW)).isEqualTo("AUTH_002");

        String[] parts = signed(claims()).split("\\.");
        String forgedPayload = base64(JSON.writeValueAsString(claims("sub", "43")));
        assertThat(outcome(parts[0] + "." + forgedPayload + "." + parts[2], NOW)).isEqualTo("AUTH_002");
    }

    /** RS256 외 알고리즘·alg none·공개키를 HMAC 비밀로 쓴 토큰은 AUTH_002다. */
    @Test
    void rejectsOtherAlgorithms() throws Exception {
        String payload = JSON.writeValueAsString(claims());
        assertThat(outcome(sign(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID("test-key").build(), claims(),
                new RSASSASigner(keyPair.getPrivate())), NOW)).isEqualTo("AUTH_002");
        assertThat(outcome(sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-key").build(), claims(),
                new MACSigner(keyPair.getPublic().getEncoded())), NOW)).isEqualTo("AUTH_002");
        String none = base64("{\"alg\":\"none\",\"kid\":\"test-key\"}") + "." + base64(payload) + ".";
        assertThat(outcome(none, NOW)).isEqualTo("AUTH_002");
    }

    /** kid 누락·미등록은 AUTH_002다. 같은 키로 서명했더라도 현재 설정 kid만 받는다. */
    @Test
    void requiresConfiguredKid() throws Exception {
        assertThat(outcome(signed(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims()), NOW))
                .isEqualTo("AUTH_002");
        assertThat(outcome(signed(header("old-key"), claims()), NOW)).isEqualTo("AUTH_002");
    }

    /** 토큰 헤더의 jwk·jku로 공격자 키를 가져오지 않는다. 공격자 키 서명은 현재 공개키로 실패한다. */
    @Test
    void ignoresEmbeddedOrRemoteKeys() throws Exception {
        KeyPair attacker = TestJwtKeys.generate("RSA", 2048);
        JWSHeader embedded = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key")
                .jwk(new RSAKey.Builder((RSAPublicKey) attacker.getPublic()).keyID("test-key").build())
                .jwkURL(java.net.URI.create("https://attacker.invalid/jwks.json"))
                .build();

        assertThat(outcome(sign(embedded, claims(), new RSASSASigner(attacker.getPrivate())), NOW))
                .isEqualTo("AUTH_002");
    }

    /** typ는 JWT 또는 생략만 받는다(기본 검증 유지). */
    @Test
    void keepsDefaultTypeCheck() throws Exception {
        assertThat(outcome(signed(new JWSHeader.Builder(header("test-key")).type(JOSEObjectType.JWT).build(),
                claims()), NOW)).isEqualTo("OK");
        assertThat(outcome(signed(new JWSHeader.Builder(header("test-key")).type(new JOSEObjectType("at+jwt"))
                .build(), claims()), NOW)).isEqualTo("AUTH_002");
    }

    /** iss·aud·token_use는 설정값과 정확히 일치하는 JSON 문자열이어야 한다. aud 배열은 모두 문자열이고 설정값을 포함해야 한다. */
    @Test
    void checksIssuerAudienceAndTokenUse() throws Exception {
        assertThat(outcome(signed(claims("aud", List.of("other", "test-audience"))), NOW)).isEqualTo("OK");

        for (Map<String, Object> claims : List.of(
                claims("iss", ABSENT), claims("iss", "test-issuer "), claims("iss", List.of("test-issuer")),
                claims("aud", ABSENT), claims("aud", "other"), claims("aud", List.of("other")),
                claims("aud", List.of("test-audience", 1)),
                claims("token_use", ABSENT), claims("token_use", "refresh"), claims("token_use", "ACCESS"))) {
            assertThat(outcome(signed(claims), NOW)).as(claims.toString()).isEqualTo("AUTH_002");
        }
    }

    /** sub는 JSON 문자열 ^[1-9][0-9]*$이며 signed Long 범위다. */
    @Test
    void checksSubject() throws Exception {
        assertThat(outcome(signed(claims("sub", String.valueOf(Long.MAX_VALUE))), NOW)).isEqualTo("OK");

        for (Object sub : new Object[] {ABSENT, 42L, "0", "-1", "042", "+42", " 42", "42 ", "4.2", "1e3", "",
                "9223372036854775808", "99999999999999999999", "４２"}) {
            assertThat(outcome(signed(claims("sub", sub)), NOW)).as(String.valueOf(sub)).isEqualTo("AUTH_002");
        }
    }

    /** sid·jti는 소문자 정규형 UUID 문자열이다. UUID.fromString이 받는 비정규형도 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {"sid", "jti"})
    void requiresCanonicalUuid(String claim) throws Exception {
        String canonical = UUID.randomUUID().toString();
        assertThat(UUID.fromString("1-1-1-1-1")).isNotNull();

        for (Object value : new Object[] {ABSENT, canonical.toUpperCase(), canonical.replace("-", ""),
                "1-1-1-1-1", "{" + canonical + "}", canonical + " ", 7L, List.of(canonical)}) {
            assertThat(outcome(signed(claims(claim, value)), NOW)).as(String.valueOf(value)).isEqualTo("AUTH_002");
        }
    }

    /**
     * iat·nbf·exp는 필수 JSON 정수다. 누락·문자열·소수·지수·boolean·범위 밖 값은 AUTH_002다.
     *
     * <p>Nimbus는 소수 초를 버리고 Spring 변환은 문자열도 Instant로 바꾸므로, 원본 검사 없이는 소수 값이 통과한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"iat", "nbf", "exp"})
    void requiresIntegerNumericDates(String claim) throws Exception {
        long valid = claim.equals("exp") ? NOW_S + 900 : NOW_S;
        assertThat(outcome(signed(claims(claim, valid)), NOW)).isEqualTo("OK");

        for (Object value : new Object[] {ABSENT, String.valueOf(valid), new BigDecimal(valid + ".5"),
                new BigDecimal(valid + ".0"), (double) valid, true, -1L, 253_402_300_800L, Long.MAX_VALUE,
                List.of(valid)}) {
            assertThat(outcome(signed(claims(claim, value)), NOW)).as(claim + "=" + value).isEqualTo("AUTH_002");
        }
    }

    /** iat·nbf는 30초까지만 미래를 허용한다. exp <= iat, nbf > exp 관계는 AUTH_002다(만료와 겹쳐도). */
    @Test
    void checksTimeRelations() throws Exception {
        assertThat(outcome(signed(claims("iat", NOW_S + 30, "nbf", NOW_S + 30)), NOW)).isEqualTo("OK");
        assertThat(outcome(signed(claims("iat", NOW_S + 31)), NOW)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(claims("nbf", NOW_S + 31)), NOW)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(claims("exp", NOW_S)), NOW)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(claims("exp", NOW_S - 1)), NOW)).isEqualTo("AUTH_002");
        assertThat(outcome(signed(claims("nbf", NOW_S + 20, "exp", NOW_S + 10)), NOW)).isEqualTo("AUTH_002");
    }

    /** Bearer 누락은 COMMON_005, 권한 부족은 COMMON_006이며 모든 오류는 JSON·캐시 금지 헤더, 401은 Bearer challenge다. */
    @Test
    void writesMissingAndDeniedAsJson() throws Exception {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        handler.commence(new MockHttpServletRequest(), missing, new InsufficientAuthenticationException("x"));
        assertErrorResponse(missing, 401, "COMMON_005", "인증이 필요합니다.");

        MockHttpServletResponse denied = new MockHttpServletResponse();
        handler.handle(new MockHttpServletRequest(), denied, new AccessDeniedException("x"));
        assertErrorResponse(denied, 403, "COMMON_006", "접근 권한이 없습니다.");

        MockHttpServletResponse expired = commence(signed(claims()), Instant.ofEpochSecond(NOW_S + 900));
        assertErrorResponse(expired, 401, "AUTH_001", "인증 정보가 만료되었습니다.");
        MockHttpServletResponse invalid = commence(tamperSignature(signed(claims())), NOW);
        assertErrorResponse(invalid, 401, "AUTH_002", "유효하지 않은 인증 정보입니다.");
        assertThat(invalid.getContentAsString()).doesNotContain("Invalid", "signature", "test-key");
    }

    private static void assertErrorResponse(MockHttpServletResponse response, int status, String code, String message)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(status == 401 ? "Bearer" : null);
        JsonNode body = JSON.readTree(response.getContentAsByteArray());
        assertThat(body.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("success", "code", "message");
        assertThat(body.path("success").booleanValue()).isFalse();
        assertThat(body.path("code").stringValue()).isEqualTo(code);
        assertThat(body.path("message").stringValue()).isEqualTo(message);
    }

    /** 디코딩 성공은 "OK", 실패는 응답 오류 코드. */
    private static String outcome(String token, Instant now) throws Exception {
        try {
            decoderAt(now).decode(token);
            return "OK";
        } catch (BadJwtException e) {
            return JSON.readTree(commence(token, now).getContentAsByteArray()).path("code").stringValue();
        }
    }

    /** JwtAuthenticationProvider와 같이 디코더 예외를 InvalidBearerTokenException 원인으로 감싸 EntryPoint에 넘긴다. */
    private static MockHttpServletResponse commence(String token, Instant now) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException failure;
        try {
            decoderAt(now).decode(token);
            throw new AssertionError("디코딩이 성공했다");
        } catch (BadJwtException e) {
            failure = new InvalidBearerTokenException(e.getMessage(), e);
        }
        handler.commence(new MockHttpServletRequest(), response, failure);
        return response;
    }

    private static JwtDecoder decoderAt(Instant now) {
        return new JwtKeyConfig().jwtDecoder(keyPair, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** 발급 서비스와 같은 정상 claim. 키·값 쌍으로 덮어쓰고 {@link #ABSENT}는 제거한다. */
    private static Map<String, Object> claims(Object... overrides) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "test-issuer");
        claims.put("aud", "test-audience");
        claims.put("sub", "42");
        claims.put("sid", SID);
        claims.put("token_use", "access");
        claims.put("iat", NOW_S);
        claims.put("nbf", NOW_S);
        claims.put("exp", NOW_S + 900);
        claims.put("jti", UUID.randomUUID().toString());
        for (int i = 0; i < overrides.length; i += 2) {
            if (overrides[i + 1] == ABSENT) {
                claims.remove((String) overrides[i]);
            } else {
                claims.put((String) overrides[i], overrides[i + 1]);
            }
        }
        return claims;
    }

    private static JWSHeader header(String kid) {
        return new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build();
    }

    private static String signed(Map<String, Object> claims) throws Exception {
        return signed(header("test-key"), claims);
    }

    private static String signed(JWSHeader header, Map<String, Object> claims) throws Exception {
        return sign(header, claims, new RSASSASigner(keyPair.getPrivate()));
    }

    /** payload JSON을 그대로(타입 변환 없이) 서명한다. */
    private static String sign(JWSHeader header, Map<String, Object> claims, JWSSigner signer) throws Exception {
        JWSObject jws = new JWSObject(header, new Payload(JSON.writeValueAsString(claims)));
        jws.sign(signer);
        return jws.serialize();
    }

    /** 서명 첫 글자를 바꾼다. */
    private static String tamperSignature(String token) {
        int i = token.lastIndexOf('.') + 1;
        return token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
