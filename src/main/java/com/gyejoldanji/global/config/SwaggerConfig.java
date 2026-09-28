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

    /** Swagger 문서의 "Try it out" 요청 대상 서버 주소. {@code app.swagger.server-url}에서 주입. */
    @Value("${app.swagger.server-url}")
    private String serverUrl;

    /** 서버 주소에 표시할 설명(예: Local, Dev). {@code app.swagger.server-description}에서 주입. */
    @Value("${app.swagger.server-description}")
    private String serverDescription;

    /**
     * OpenAPI 공통 정보 등록.
     *
     * <p>문서 제목/버전, 전역 JWT 인증({@code bearerAuth}), 서버 주소를 설정한다.
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("GYEJOL-DANJI API").description("GYEJOL-DANJI API 백엔드 API 문서").version("v1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addServersItem(new Server().url(serverUrl).description(serverDescription));
    }
}