package com.gyejoldanji.global.security;

import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import com.gyejoldanji.global.config.ClockConfig;
import com.gyejoldanji.global.config.JwtKeyConfig;
import com.gyejoldanji.global.config.SecurityConfig;
import com.gyejoldanji.global.config.TestJwtKeys;
import com.gyejoldanji.global.config.properties.AppProperties;
import com.gyejoldanji.global.config.properties.AuthProperties;
import com.gyejoldanji.global.config.properties.TossProperties;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 운영 {@link SecurityConfig}·JwtDecoder·설정 검증·오류 처리를 그대로 올리는 test 전용 웹 설정(DB·토스 없음).
 * AuthSessionRepository는 각 테스트가 대체한다. JWT 키는 실행 중 만든 RSA 키이고 나머지는 비밀이 아닌 test 값이다.
 *
 * <p>{@code @TestConfiguration}이라 전체 앱의 component scan(contextLoads)에는 들어가지 않고 명시한 테스트에서만 쓰인다.
 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        ValidationAutoConfiguration.class,
        ConfigurationPropertiesAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class})
@EnableConfigurationProperties({AuthProperties.class, AppProperties.class, TossProperties.class})
@Import({SecurityConfig.class, JwtKeyConfig.class, ClockConfig.class, GlobalExceptionHandler.class})
public class SecurityTestConfig {

    /** test에서 허용하는 FE Origin. */
    public static final String ALLOWED_ORIGIN = "https://fe.test.example";

    /** 필수 설정(DEV)을 공급한다. 각 테스트의 {@code @DynamicPropertySource}에서 호출한다. */
    public static void register(DynamicPropertyRegistry registry) throws Exception {
        TestJwtKeys.register(registry);
        registry.add("app.cors.allowed-origins", () -> ALLOWED_ORIGIN);
        registry.add("toss.app-name", () -> "test-app");
        registry.add("toss.identity-environment", () -> "DEV");
        registry.add("toss.mtls.keystore-path", () -> "not-opened.p12");
        registry.add("toss.mtls.keystore-password", () -> "not-a-secret");
    }
}
