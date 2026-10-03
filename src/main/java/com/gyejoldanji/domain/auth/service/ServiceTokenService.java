package com.gyejoldanji.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.gyejoldanji.global.config.properties.AuthProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * 자체 Access JWT(RS256)와 Refresh 토큰을 만든다.
 *
 * <p>시각은 호출자가 회원 잠금 이후 Clock에서 얻은 UTC 마이크로초 {@code now}를 기준으로 하며, JWT 시각은 정수 초로 내림한다.
 */
@Service
public class ServiceTokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final AuthProperties properties;
    private final JwtEncoder jwtEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public ServiceTokenService(AuthProperties properties, KeyPair jwtKeyPair) {
        this.properties = properties;
        this.jwtEncoder = NimbusJwtEncoder
                .withKeyPair((RSAPublicKey) jwtKeyPair.getPublic(), (RSAPrivateKey) jwtKeyPair.getPrivate())
                .algorithm(SignatureAlgorithm.RS256)
                .jwkPostProcessor(jwk -> jwk.keyID(properties.getJwt().getKeyId()))
                .build();
    }

    /** 발급한 토큰과 응답 TTL(초). Refresh는 응답용 원문과 DB 저장용 SHA-256 해시를 함께 담는다. */
    public record IssuedTokens(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn,
                               byte[] refreshTokenHash) {

        /** 로그·디버그 출력에 토큰과 해시가 남지 않게 가린다. */
        @Override
        public String toString() {
            return "IssuedTokens[REDACTED]";
        }
    }

    /**
     * 세션의 Access 토큰과 최초 Refresh 토큰을 만든다.
     *
     * <p>Access 만료는 {@code min(now + accessTtl, sessionExpiresAt)}를 정수 초로 내린 값이며, 응답 TTL은 그 내림 결과로
     * 계산한다(고정 900 아님). 둘 중 하나라도 1초 미만이면 발급하지 않는다.
     *
     * @throws IllegalStateException 응답 TTL이 1초 미만일 때
     */
    public IssuedTokens issue(long memberId, String sessionKey, Instant now, Instant sessionExpiresAt) {
        Instant accessExpiry = now.plusSeconds(properties.getAccessTtlSeconds());
        Instant expiresAt = Instant.ofEpochSecond(
                (accessExpiry.isBefore(sessionExpiresAt) ? accessExpiry : sessionExpiresAt).getEpochSecond());
        long expiresIn = Duration.between(now, expiresAt).getSeconds();
        long refreshExpiresIn = Duration.between(now, sessionExpiresAt).getSeconds();
        if (expiresIn < 1 || refreshExpiresIn < 1) {
            throw new IllegalStateException("발급할 토큰의 남은 수명이 1초 미만입니다.");
        }

        Instant issuedAt = Instant.ofEpochSecond(now.getEpochSecond());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .audience(List.of(properties.getJwt().getAudience()))
                .subject(Long.toString(memberId))
                .claim("sid", sessionKey)
                .claim("token_use", "access")
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.getJwt().getKeyId()).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        String refreshToken = newRefreshToken();
        return new IssuedTokens(accessToken, expiresIn, refreshToken, refreshExpiresIn, sha256(refreshToken));
    }

    /** SecureRandom 32바이트를 padding 없는 Base64url(43자)로 만든다. */
    private String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 응답하는 Refresh 원문 문자열의 UTF-8 바이트를 해싱한다(난수 바이트 자체가 아니다). */
    private static byte[] sha256(String refreshToken) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
