package com.gyejoldanji.global.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Swagger(OpenAPI) 문서 설정.
 *
 * <p>API 문서의 기본 정보와 JWT 인증 방식(Authorize 버튼), 요청 서버 주소를 정의한다.
 */
@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER)
public class SwaggerConfig {

    /**
     * Swagger 문서의 "Try it out" 요청 대상 서버 주소. {@code app.swagger.server-url}에서 주입.
     * 비어 있으면 springdoc이 요청 주소(운영은 X-Forwarded-* 반영)로 서버 URL을 만든다.
     */
    @Value("${app.swagger.server-url:}")
    private String serverUrl;

    /** 고정 서버 주소에 표시할 설명(예: Production). {@code app.swagger.server-description}에서 주입. */
    @Value("${app.swagger.server-description:}")
    private String serverDescription;

    /**
     * OpenAPI 공통 정보 등록.
     *
     * <p>문서 제목/버전, 전역 JWT 인증({@code bearerAuth})을 설정하고, 서버 주소는 지정된 경우에만 고정한다.
     */
    @Bean
    public OpenAPI openAPI() {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info().title("GYEJOL-DANJI API").description("GYEJOL-DANJI API 백엔드 API 문서").version("v1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
        if (StringUtils.hasText(serverUrl)) {
            openAPI.addServersItem(new Server().url(serverUrl)
                    .description(StringUtils.hasText(serverDescription) ? serverDescription : null));
        }
        return openAPI;
    }
}