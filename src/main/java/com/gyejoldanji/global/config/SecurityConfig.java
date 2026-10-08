package com.gyejoldanji.global.config;

import java.time.Clock;
import java.util.List;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.config.properties.AppProperties;
import com.gyejoldanji.global.config.properties.TossProperties;
import com.gyejoldanji.global.config.properties.TossProperties.IdentityEnvironment;
import com.gyejoldanji.global.security.AuthRequestBodyLimitFilter;
import com.gyejoldanji.global.security.JsonSecurityErrorHandler;
import com.gyejoldanji.global.security.SessionValidationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 두 STATELESS 체인. 1번은 본문 증명으로 인증하는 세 경로, 2번은 나머지 전체(JWT·DB 세션 검사, 허용 목록 밖은 거부)다.
 *
 * <p>인증 정보는 Authorization 헤더·요청 본문으로만 받고 자동 전송 쿠키를 쓰지 않으므로 CSRF를 끈다. 세션·Basic·formLogin·기본
 * logout은 쓰지 않는다. Swagger 문서 경로는 환경과 무관하게 GET으로 공개하며, 실제 노출 여부는 {@code app.swagger.enabled}로
 * springdoc 자체를 켜고 끈다({@code SWAGGER_ENABLED} env, 기본 true). 테스트 API는 {@code toss.identity-environment=DEV}에서만
 * GET으로 공개하고 PROD에서는 거부한다. actuator health는 헬스체크용으로 공개하며 상세 정보는 노출하지 않는다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 정확한 /api/auth/anonymous·refresh·logout(메서드 무관). CORS preflight 뒤 POST만 허용하고 다른 메서드는 403이다. Bearer
     * 필터가 없어 잘못된 Authorization 헤더가 붙어도 본문 계약으로 처리한다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        JsonSecurityErrorHandler errors = new JsonSecurityErrorHandler(jsonMapper);
        statelessApi(http)
                .securityMatcher("/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout")
                // 헤더·CORS 처리 뒤, 본문을 읽을 수 있는 필터보다 먼저 실행한다.
                // Bean이 아니므로 Servlet 필터로 자동 등록되지 않고 이 체인에만 한 번 들어간다.
                .addFilterBefore(new AuthRequestBodyLimitFilter(jsonMapper), CsrfFilter.class)
                // 이 체인에는 인증 수단이 없으므로 익명 요청의 거부도 인증 요구(401)가 아닌 403이다.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) -> errors.write(response, ErrorCode.FORBIDDEN))
                        .accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    /**
     * 나머지 전체. Bearer JWT 검증 → DB 회원·세션 검사({@link SessionValidationFilter}) 뒤 확인된 경로·메서드만 허용한다.
     * 다른 도메인 API는 실제 경로·메서드가 정해지면 여기에 추가하고 각 도메인에서 소유권을 검사한다.
     *
     * <p>표준 BearerTokenAuthenticationFilter·JwtAuthenticationProvider를 직접 등록한다. oauth2ResourceServer DSL은 허용
     * 목록과 무관하게 응답하는 /.well-known/oauth-protected-resource 메타데이터 필터와 DPoP 필터를 함께 추가하므로 쓰지 않는다.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, JsonMapper jsonMapper, JwtDecoder jwtDecoder,
                                              AuthSessionRepository sessionRepository, Clock clock,
                                              TossProperties tossProperties) throws Exception {
        JsonSecurityErrorHandler errors = new JsonSecurityErrorHandler(jsonMapper);
        boolean dev = tossProperties.getIdentityEnvironment() == IdentityEnvironment.DEV;
        // Bean이 아니므로 두 필터 모두 Servlet 필터로 자동 등록되지 않고 이 체인에만 한 번 들어간다.
        BearerTokenAuthenticationFilter bearer =
                new BearerTokenAuthenticationFilter(new ProviderManager(new JwtAuthenticationProvider(jwtDecoder)));
        bearer.setAuthenticationEntryPoint(errors);
        statelessApi(http)
                .addFilter(bearer)
                .addFilterAfter(new SessionValidationFilter(sessionRepository, clock, errors),
                        BearerTokenAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> {
                    // 앞 단계에서 이미 처리된 오류의 내부 오류 페이지 dispatch는 인증 오류로 덮지 않는다.
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    // 콘텐츠 추천의 경우, 인증 불필요
                    auth.requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/recommendations/today"
                    ).permitAll();

                    auth.requestMatchers(HttpMethod.GET, "/api/members/me").authenticated();
                    auth.requestMatchers(HttpMethod.POST, "/api/members/me/onboarding/complete").authenticated();
                    auth.requestMatchers(HttpMethod.GET,
                            "/api/records/*", "/api/records/seasons/*").authenticated();
                    auth.requestMatchers(HttpMethod.POST, "/api/records").authenticated();
                    auth.requestMatchers(HttpMethod.PATCH, "/api/records/*").authenticated();
                    auth.requestMatchers(HttpMethod.DELETE, "/api/records/*").authenticated();
                    auth.requestMatchers(HttpMethod.POST, "/api/images/presigned-url").authenticated();
                    auth.requestMatchers(HttpMethod.GET, "/api/jar-pages", "/api/jar-pages/*/stickers")
                            .authenticated();
                    auth.requestMatchers(HttpMethod.PUT, "/api/jar-pages/*/stickers").authenticated();
                    auth.requestMatchers(HttpMethod.DELETE, "/api/jar-pages/*/stickers").authenticated();
                    // 환경과 무관하게 경로는 열어 두고, 실제 응답 여부는 app.swagger.enabled로 springdoc 자체를 켜고 끈다.
                    auth.requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                            .permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                    if (dev) {
                        auth.requestMatchers(HttpMethod.GET, "/api/test/response").permitAll();
                    }
                    auth.anyRequest().denyAll();
                });
        return http.build();
    }

    private static HttpSecurity statelessApi(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    /**
     * 설정한 실제 Origin만 허용한다. Authorization·Content-Type과 분석용 공통 헤더, GET·POST·PUT·PATCH·DELETE·OPTIONS,
     * credentials 없음, Retry-After 노출.
     *
     * @throws IllegalStateException PROD에서 https가 아니거나 localhost·loopback Origin이 있을 때(기동 실패)
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties appProperties, TossProperties tossProperties) {
        List<String> origins = appProperties.getCors().getAllowedOrigins();
        if (tossProperties.getIdentityEnvironment() == IdentityEnvironment.PROD) {
            origins.stream().filter(origin -> !origin.startsWith("https://") || isLocal(origin)).findFirst()
                    .ifPresent(origin -> {
                        throw new IllegalStateException("PROD CORS Origin은 https 실제 주소여야 합니다: " + origin);
                    });
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                "X-Analytics-Session-Id",
                "X-Client-Version",
                "X-Client-OS"));
        cors.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /** 형식 검증(AppProperties)을 통과한 Origin의 host가 localhost·loopback·미지정 주소인지. */
    private static boolean isLocal(String origin) {
        String host = origin.substring(origin.indexOf("://") + 3).replaceFirst(":[0-9]+$", "");
        return host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("127.")
                || host.equals("0.0.0.0");
    }
}
