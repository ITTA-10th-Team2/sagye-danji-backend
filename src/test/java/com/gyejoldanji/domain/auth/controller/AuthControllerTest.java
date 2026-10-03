package com.gyejoldanji.domain.auth.controller;

import java.util.stream.Stream;

import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.service.AnonymousAuthService;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import com.gyejoldanji.global.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 anonymous endpoint의 입력·본문 제한·응답 형식·오류 매핑·헤더·마스킹을 운영 Security 체인과 기존 오류 처리로 검증한다.
 *
 * <p>서비스는 대체한다(외부 호출·DB 없음). 서비스 내부와 실제 DB는 각 서비스 테스트와 MySQL 통합 테스트에서 검증한다.
 */
@SpringBootTest(classes = AuthControllerTest.TestApplication.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {

    private static final String PATH = "/api/auth/anonymous";
    private static final String SECRET_CODE = "SECRET-CODE-1234";

    @MockitoBean
    private AnonymousAuthService anonymousAuthService;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** 성공은 ApiResponse로 감싼 토큰 응답이다. 추가 필드는 무시하고 Bearer 헤더와 무관하게 본문 code만 쓴다. */
    @Test
    void returnsTokensForValidCode(CapturedOutput output) throws Exception {
        when(anonymousAuthService.authenticate(SECRET_CODE)).thenReturn(new AnonymousAuthResponse(
                "Bearer", "SECRET-ACCESS", 899, "SECRET-REFRESH", 1_209_600, true,
                new AnonymousAuthResponse.MemberInfo("7", "NOT_COMPLETED")));

        postBody("{\"code\":\"" + SECRET_CODE + "\",\"memberId\":99,\"anonKey\":\"x\",\"env\":\"PROD\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").value("SECRET-ACCESS"))
                .andExpect(jsonPath("$.data.expiresIn").value(899))
                .andExpect(jsonPath("$.data.refreshToken").value("SECRET-REFRESH"))
                .andExpect(jsonPath("$.data.refreshExpiresIn").value(1_209_600))
                .andExpect(jsonPath("$.data.isNewMember").value(true))
                .andExpect(jsonPath("$.data.newMember").doesNotExist())
                .andExpect(jsonPath("$.data.member.memberId").value("7"))
                .andExpect(jsonPath("$.data.member.onboardingStatus").value("NOT_COMPLETED"))
                .andExpect(jsonPath("$.data.anonKey").doesNotExist());

        verify(anonymousAuthService).authenticate(SECRET_CODE);
        assertThat(output.getAll()).doesNotContain(SECRET_CODE, "SECRET-ACCESS", "SECRET-REFRESH");
    }

    /** 입력 오류는 서비스(토스 교환·DB)를 부르지 않으며 오류에도 캐시 금지 헤더가 있다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ''                        | 400 | COMMON_004
            '{"code":'                | 400 | COMMON_004
            'null'                    | 400 | COMMON_004
            '[]'                      | 400 | COMMON_004
            '{"code":123}'            | 400 | COMMON_004
            '{"code":true}'           | 400 | COMMON_004
            '{"code":["a"]}'          | 400 | COMMON_004
            '{}'                      | 400 | COMMON_001
            '{"code":null}'           | 400 | COMMON_001
            '{"code":"   "}'          | 400 | COMMON_001
            """)
    void rejectsInvalidInputWithoutCallingService(String body, int status, String code) throws Exception {
        postBody(body)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        verifyNoInteractions(anonymousAuthService);
    }

    /** 4096 code point 초과 code는 COMMON_001이고 값은 가려진다. */
    @Test
    void rejectsTooLongCodeWithMaskedValue() throws Exception {
        postBody("{\"code\":\"" + SECRET_CODE + "a".repeat(4096) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"))
                .andExpect(content().string(not(containsString(SECRET_CODE))));

        verifyNoInteractions(anonymousAuthService);
    }

    /** 16,384바이트 초과 본문은 파싱 전에 413이며 서비스를 부르지 않는다. */
    @Test
    void rejectsOversizedBodyBeforeParsing() throws Exception {
        postBody("{\"code\":\"" + "a".repeat(16_384) + "\"}")
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("COMMON_009"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        verifyNoInteractions(anonymousAuthService);
    }

    /** 서비스가 분류한 오류는 상태·코드·기본 메시지 그대로이며 Retry-After는 없다. */
    @ParameterizedTest
    @MethodSource("serviceErrors")
    void mapsServiceErrors(ErrorCode errorCode, CapturedOutput output) throws Exception {
        when(anonymousAuthService.authenticate(anyString())).thenThrow(new BusinessException(errorCode));

        postBody("{\"code\":\"" + SECRET_CODE + "\"}")
                .andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        assertThat(output.getAll()).doesNotContain(SECRET_CODE);
    }

    static Stream<Arguments> serviceErrors() {
        return Stream.of(ErrorCode.AUTH_CODE_REJECTED, ErrorCode.MEMBER_INACTIVE, ErrorCode.AUTH_PROVIDER_BAD_RESPONSE,
                ErrorCode.AUTH_PROVIDER_TIMEOUT, ErrorCode.AUTH_PROVIDER_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
                ErrorCode.INTERNAL_SERVER_ERROR).map(Arguments::of);
    }

    /** 토스 한도 초과는 429 AUTH_014이며, 검증된 대기 초가 있을 때만 Retry-After를 준다. */
    @ParameterizedTest
    @MethodSource("rateLimits")
    void returnsRetryAfterOnlyWhenKnown(Integer retryAfterSeconds) throws Exception {
        when(anonymousAuthService.authenticate(anyString()))
                .thenThrow(new TossAnonymousAuthClient.RateLimitedException(retryAfterSeconds));

        ResultActions result = postBody("{\"code\":\"c\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_014"))
                .andExpect(header().string("Cache-Control", "no-store"));
        if (retryAfterSeconds == null) {
            result.andExpect(header().doesNotExist("Retry-After"));
        } else {
            result.andExpect(header().string("Retry-After", retryAfterSeconds.toString()));
        }
    }

    static Stream<Arguments> rateLimits() {
        return Stream.of(arguments(30), arguments((Object) null));
    }

    private ResultActions postBody(String body) throws Exception {
        return mockMvc.perform(post(PATH)
                .header("Authorization", "Bearer not-a-valid-token")
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            ValidationAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class})
    @Import({SecurityConfig.class, GlobalExceptionHandler.class, AuthController.class})
    static class TestApplication {
    }
}
