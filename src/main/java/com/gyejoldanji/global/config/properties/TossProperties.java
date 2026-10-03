package com.gyejoldanji.global.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 토스 익명 키 교환(mTLS) 설정. 값이 하나라도 잘못되면 애플리케이션이 시작되지 않는다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "toss")
public class TossProperties {

    /** 토스 파트너 API 주소. 서버 설정으로만 정하며 https만 허용한다. */
    @NotBlank
    @Pattern(regexp = "https://.+")
    private String apiBaseUrl = "https://apps-in-toss-api.toss.im";

    /** 미니앱 이름. */
    @NotBlank
    private String appName;

    /** 서버 배포 구분. 회원 식별·요청 선택에는 쓰지 않는다. */
    @NotNull
    private IdentityEnvironment identityEnvironment;

    /** 미니앱 client 인증서 설정. */
    @Valid
    private final Mtls mtls = new Mtls();

    /** TCP·TLS 연결 제한 시간(초). */
    @Positive
    private int connectTimeoutSeconds = 3;

    /** 요청 전송부터 응답 본문 수신까지의 제한 시간(초). */
    @Positive
    private int requestTimeoutSeconds = 5;

    public enum IdentityEnvironment {
        DEV,
        PROD
    }

    @Getter
    @Setter
    public static class Mtls {

        /** 미니앱 client 개인키·인증서 체인이 든 PKCS#12 파일 경로. */
        @NotBlank
        private String keystorePath;

        /** PKCS#12 암호. */
        @NotBlank
        private String keystorePassword;
    }
}
