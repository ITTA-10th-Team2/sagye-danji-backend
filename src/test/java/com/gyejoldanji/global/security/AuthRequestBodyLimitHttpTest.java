package com.gyejoldanji.global.security;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.gyejoldanji.global.config.SecurityConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static com.gyejoldanji.global.security.AuthRequestBodyLimitFilterTest.TOO_LARGE_BODY;
import static com.gyejoldanji.global.security.AuthRequestBodyLimitFilterTest.jsonFields;
import static com.gyejoldanji.global.security.AuthRequestBodyLimitFilterTest.jsonOfBytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Tomcat(임의 포트)과 운영 {@link SecurityConfig}로 본문 제한의 등록과 chunked 요청 처리를 검증한다(T31).
 *
 * <p>웹·Jackson·Security 자동 설정만 올리고 DB·토스·JWT 설정은 사용하지 않는다. 컨트롤러는 이 테스트 전용 에코다.
 */
@SpringBootTest(classes = AuthRequestBodyLimitHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthRequestBodyLimitHttpTest {

    private static final int LIMIT = AuthRequestBodyLimitFilter.MAX_BODY_BYTES;
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** Security보다 먼저 기록한 각 요청의 "Transfer-Encoding/Content-Length". */
    private static final Queue<String> ARRIVALS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger CONTROLLER_CALLS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void reset() {
        ARRIVALS.clear();
        CONTROLLER_CALLS.set(0);
    }

    /** Security 체인 하나에 한 번만 들어가고 Bean·Servlet 필터로는 등록되지 않는다. */
    @Test
    void registeredOnceOnlyInSecurityChain() {
        List<SecurityFilterChain> chains = context.getBean(FilterChainProxy.class).getFilterChains();
        assertThat(chains).hasSize(1);
        assertThat(chains.getFirst().getFilters()).filteredOn(AuthRequestBodyLimitFilter.class::isInstance).hasSize(1);

        assertThat(context.getBeansOfType(AuthRequestBodyLimitFilter.class)).isEmpty();
        var registrations = context.getServletContext().getFilterRegistrations();
        assertThat(registrations).containsKey("springSecurityFilterChain");
        assertThat(registrations.values()).extracting(FilterRegistration::getClassName)
                .doesNotContain(AuthRequestBodyLimitFilter.class.getName());
    }

    /** 실제 chunked 요청에서 16,384바이트는 원문 그대로 MVC에 도착하고 16,385바이트는 컨트롤러 전에 413이다. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout"})
    void enforcesLimitOnRealChunkedRequests(String path) throws Exception {
        byte[] max = jsonOfBytes(LIMIT);
        HttpResponse<byte[]> accepted = post(path, chunked(max));
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).isEqualTo(max);

        assertTooLarge(post(path, chunked(jsonOfBytes(LIMIT + 1))));

        // 두 요청 모두 Content-Length 없이 chunked로 서버에 도착했다.
        assertThat(ARRIVALS).containsExactly("chunked/-1", "chunked/-1");
        assertThat(CONTROLLER_CALLS).hasValue(1);
    }

    /** Content-Length가 상한을 넘는 실제 요청도 컨트롤러 전에 거부한다. */
    @Test
    void rejectsRealFixedLengthRequestOverLimit() throws Exception {
        assertTooLarge(post("/api/auth/anonymous", BodyPublishers.ofByteArray(jsonOfBytes(LIMIT + 1))));

        assertThat(ARRIVALS).containsExactly("null/" + (LIMIT + 1));
        assertThat(CONTROLLER_CALLS).hasValue(0);
    }

    /** MVC가 인증 경로로 라우팅하는 인코딩 경로에도 적용하고, 다른 경로는 제한하지 않는다. */
    @Test
    void followsMvcRoutingAndLeavesOtherPathsAlone() throws Exception {
        byte[] over = jsonOfBytes(LIMIT + 1);
        // %61은 'a'. 상한 이하 요청이 에코되므로 MVC가 /api/auth/anonymous로 라우팅함을 함께 확인한다.
        assertThat(post("/api/auth/%61nonymous", chunked(jsonOfBytes(LIMIT))).statusCode()).isEqualTo(200);
        assertTooLarge(post("/api/auth/%61nonymous", chunked(over)));

        HttpResponse<byte[]> other = post("/api/other", chunked(over));
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(other.body()).isEqualTo(over);
        assertThat(CONTROLLER_CALLS).hasValue(2);
    }

    private HttpResponse<byte[]> post(String path, BodyPublisher body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(body)
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 길이를 모르는 publisher라 HTTP/1.1 클라이언트가 Content-Length 없이 chunked로 보낸다. */
    private static BodyPublisher chunked(byte[] body) {
        return BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
    }

    private static void assertTooLarge(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().firstValue("Pragma")).hasValue("no-cache");
        assertThat(jsonFields(response.body())).isEqualTo(TOO_LARGE_BODY);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class})
    @Import({SecurityConfig.class, EchoController.class})
    static class TestApplication {

        /** Security 필터보다 먼저 실행해 실제 도착한 길이 헤더를 기록한다. */
        @Bean
        FilterRegistrationBean<Filter> arrivalRecorder() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                ARRIVALS.add(http.getHeader("Transfer-Encoding") + "/" + http.getContentLengthLong());
                chain.doFilter(request, response);
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    @RestController
    static class EchoController {

        /** 필터를 통과해 MVC가 받은 본문을 그대로 돌려준다. */
        @PostMapping({"/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout", "/api/other"})
        byte[] echo(@RequestBody byte[] body) {
            CONTROLLER_CALLS.incrementAndGet();
            return body;
        }
    }
}
