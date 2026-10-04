package com.gyejoldanji.global.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 자체 JWT 발급 정보와 Access·세션 수명 설정. 값이 하나라도 잘못되면 애플리케이션이 시작되지 않는다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** JWT 발급자·대상·키 설정. */
    @Valid
    private final Jwt jwt = new Jwt();

    /** Access 토큰 수명(초). 응답 TTL을 초 단위로 내림해도 0이 되지 않도록 1초는 거부한다. */
    @Min(2)
    @Max(900)
    private long accessTtlSeconds = 900;

    /** 세션 절대 수명(초). Access 수명 이상, 14일 이하. */
    @Max(1_209_600)
    private long sessionTtlSeconds = 1_209_600;

    /** 세션 만료 뒤 세션·Refresh 이력을 보관하는 기간(일). 지나면 만료 이력 정리가 삭제한다. 0 이하는 거부한다. */
    @Min(1)
    private int expiredSessionRetentionDays = 7;

    /** 세션이 Access 토큰보다 먼저 끝나지 않는지 검증한다. */
    @AssertTrue(message = "session-ttl-seconds는 access-ttl-seconds 이상이어야 합니다.")
    public boolean isSessionTtlAtLeastAccessTtl() {
        return sessionTtlSeconds >= accessTtlSeconds;
    }

    @Getter
    @Setter
    public static class Jwt {

        /** JWT iss. */
        @NotBlank
        private String issuer;

        /** JWT aud. */
        @NotBlank
        private String audience;

        /** JWT 헤더 kid. */
        @NotBlank
        private String keyId;

        /** PKCS#8 PEM RSA 개인키 파일 경로. */
        @NotBlank
        private String privateKeyPath;

        /** X.509 SubjectPublicKeyInfo PEM RSA 공개키 파일 경로. */
        @NotBlank
        private String publicKeyPath;
    }
}
