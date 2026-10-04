package com.gyejoldanji.global.config.properties;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 앱 공통 설정 중 CORS. 값이 하나라도 잘못되면 애플리케이션이 시작되지 않는다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 브라우저 교차 출처 요청 설정. */
    @Valid
    private final Cors cors = new Cors();

    @Getter
    @Setter
    public static class Cors {

        /**
         * 허용할 실제 FE Origin 목록(쉼표 구분). 소문자 {@code scheme://host[:port]}만 받으며 wildcard·경로·끝 슬래시·
         * {@code APP_NAME} 같은 대문자/밑줄 자리표시자는 거부한다. PROD 추가 규칙은 SecurityConfig에서 검사한다.
         */
        @NotEmpty
        private List<@NotNull @Pattern(regexp = "https?://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*"
                + "(:[0-9]{1,5})?") String> allowedOrigins = new ArrayList<>();
    }
}
