package com.gyejoldanji.global.config;

import com.gyejoldanji.global.security.AuthRequestBodyLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // 헤더·CORS 처리 뒤, 본문을 읽을 수 있는 필터(CSRF 파라미터·로그아웃·인증)보다 먼저 실행한다.
                // Bean이 아니므로 Servlet 필터로 자동 등록되지 않고 이 체인에만 한 번 들어간다.
                .addFilterBefore(new AuthRequestBodyLimitFilter(jsonMapper), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().permitAll() // 개발 초기 전체 허용
                );
        return http.build();
    }
}
