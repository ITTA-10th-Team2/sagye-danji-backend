package com.gyejoldanji.domain.auth.controller;

import java.util.stream.Stream;

import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.dto.RefreshRequest;
import com.gyejoldanji.domain.auth.dto.RefreshResponse;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.service.AnonymousAuthService;
import com.gyejoldanji.domain.auth.service.SessionLogoutService;
import com.gyejoldanji.domain.auth.service.TokenRefreshService;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.security.SecurityTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
 * 실제 anonymous·refresh·logout endpoint의 입력·본문 제한·응답 형식·오류 매핑·헤더·마스킹을 운영 Security 체인과 기존 오류 처리로 검증한다.
 *
 * <p>서비스는 대체한다(외부 호출·DB 없음). 서비스 내부와 실제 DB는 각 서비스 테스트와 MySQL 통합 테스트에서 검증한다. 세 인증 경로
 * 공통의 본문 제한(chunked 포함)·체인·CORS는 각 필터·체인 테스트가 맡는다.
 */
@SpringBootTest(classes = {SecurityTestConfig.class, AuthController.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {

    private static final String PATH = "/api/auth/anonymous";
    private static final String SECRET_CODE = "SECRET-CODE-1234";
    private static final String REFRESH_PATH = "/api/auth/refresh";
    /** 형식에 맞는 43자 Refresh 원문. */
    private static final String SECRET_REFRESH = "SECRET-refresh_0123456789abcdefghijklmnopqr";
    private static final String LOGOUT_PATH = "/api/auth/logout";

    @MockitoBean
    private AnonymousAuthService anonymousAuthService;
    @MockitoBean
    private TokenRefreshService tokenRefreshService;
    @MockitoBean
    private SessionLogoutService sessionLogoutService;
    /** 보호 체인의 세션 필터용. 인증 체인은 Bearer를 보지 않으므로 호출되지 않아야 한다. */
    @MockitoBean
    private AuthSessionRepository sessionRepository;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        SecurityTestConfig.register(registry);
    }

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
        verifyNoInteractions(sessionRepository);
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

    /**
     * Refresh 성공은 ApiResponse로 감싼 토큰 쌍 다섯 필드뿐이다. Access 없이·잘못된 Bearer·다른 인증 헤더가 붙어도 본문 Refresh 원문
     * 그대로 서비스에 넘기고, 추가 필드(회원·세션 ID 등)는 무시한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer not-a-valid-token", "Bearer eyJhbGciOiJub25lIn0.e30.", "Basic dXNlcjpwYXNz"})
    void refreshReturnsOnlyTokenPair(String authorization, CapturedOutput output) throws Exception {
        when(tokenRefreshService.refresh(SECRET_REFRESH)).thenReturn(
                new RefreshResponse("Bearer", "SECRET-ACCESS", 899, "SECRET-NEW-REFRESH", 1_208_994));

        var request = post(REFRESH_PATH).contentType(APPLICATION_JSON).content("{\"refreshToken\":\"" + SECRET_REFRESH
                + "\",\"memberId\":\"99\",\"sessionId\":7,\"refreshTokenHash\":\"x\",\"code\":\"c\"}");
        if (!authorization.isEmpty()) {
            request.header("Authorization", authorization);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").value("SECRET-ACCESS"))
                .andExpect(jsonPath("$.data.expiresIn").value(899))
                .andExpect(jsonPath("$.data.refreshToken").value("SECRET-NEW-REFRESH"))
                .andExpect(jsonPath("$.data.refreshExpiresIn").value(1_208_994));

        verify(tokenRefreshService).refresh(SECRET_REFRESH);
        verifyNoInteractions(anonymousAuthService, sessionRepository);
        assertThat(output.getAll()).doesNotContain(SECRET_REFRESH, "SECRET-ACCESS", "SECRET-NEW-REFRESH");
        assertThat(new RefreshRequest(SECRET_REFRESH).toString()).doesNotContain(SECRET_REFRESH);
    }

    /** 패턴에 맞는 값은 trim·대소문자 변환·Base64 정규화 없이 그대로 넘긴다(정규형이 아닌 Base64url도 형식상 허용). */
    @ParameterizedTest
    @MethodSource("acceptedRefreshTokens")
    void refreshPassesTokenUnchanged(String refreshToken) throws Exception {
        when(tokenRefreshService.refresh(refreshToken)).thenReturn(new RefreshResponse("Bearer", "a", 1, "r", 1));

        postRefresh("{\"refreshToken\":\"" + refreshToken + "\"}").andExpect(status().isOk());

        verify(tokenRefreshService).refresh(refreshToken);
    }

    static Stream<String> acceptedRefreshTokens() {
        return Stream.of("A".repeat(42) + "B", "_".repeat(43), "-".repeat(43), "0".repeat(43),
                "aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_zZaAq");
    }

    /**
     * 07 입력 결정표: 본문 없음·잘못된 JSON·최상위 null/비객체·refreshToken 타입 오류는 COMMON_004, 누락·null·빈 값·패턴 불일치는
     * COMMON_001이다. 서비스(DB)를 부르지 않고 오류에도 캐시 금지 헤더가 있다.
     */
    @ParameterizedTest
    @MethodSource("invalidRefreshBodies")
    void refreshRejectsInvalidInputWithoutCallingService(String body, String code) throws Exception {
        postRefresh(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        verifyNoInteractions(tokenRefreshService);
    }

    static Stream<Arguments> invalidRefreshBodies() {
        String valid = SECRET_REFRESH;
        String short42 = valid.substring(1);
        return Stream.of(
                arguments("", "COMMON_004"),
                arguments("{\"refreshToken\":", "COMMON_004"),
                arguments("null", "COMMON_004"),
                arguments("[]", "COMMON_004"),
                arguments("\"" + valid + "\"", "COMMON_004"),
                arguments("123", "COMMON_004"),
                arguments("true", "COMMON_004"),
                arguments("{\"refreshToken\":123}", "COMMON_004"),
                arguments("{\"refreshToken\":1.5}", "COMMON_004"),
                arguments("{\"refreshToken\":true}", "COMMON_004"),
                arguments("{\"refreshToken\":[\"" + valid + "\"]}", "COMMON_004"),
                arguments("{\"refreshToken\":{\"value\":\"" + valid + "\"}}", "COMMON_004"),
                arguments("{\"refreshToken\":\"" + valid + "\"} {}", "COMMON_004"),
                arguments("{}", "COMMON_001"),
                arguments("{\"refreshTokens\":\"" + valid + "\"}", "COMMON_001"),
                arguments("{\"refreshToken\":null}", "COMMON_001"),
                arguments("{\"refreshToken\":\"\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + " ".repeat(43) + "\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + short42 + "\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + valid + "A\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\" " + short42 + "\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\" " + valid + "\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + valid + " \"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + valid + "\\n\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + short42 + "=\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + short42 + "+\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + short42 + "/\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + short42 + ".\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + short42 + "\\u00e9\"}", "COMMON_001"),
                arguments("{\"refreshToken\":\"" + short42 + "\\uff21\"}", "COMMON_001"));
    }

    /** 패턴 오류 응답의 value는 가려지고 응답·로그에 원문이 없다. */
    @Test
    void refreshMasksRejectedValue(CapturedOutput output) throws Exception {
        postRefresh("{\"refreshToken\":\"" + SECRET_REFRESH + "x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[0].field").value("refreshToken"))
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"))
                .andExpect(content().string(not(containsString(SECRET_REFRESH))));

        assertThat(output.getAll()).doesNotContain(SECRET_REFRESH);
    }

    /** 16,384바이트 초과 본문은 파싱 전에 413이며 서비스를 부르지 않는다. */
    @Test
    void refreshRejectsOversizedBodyBeforeParsing() throws Exception {
        postRefresh("{\"refreshToken\":\"" + SECRET_REFRESH + "\",\"pad\":\"" + "a".repeat(16_384) + "\"}")
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("COMMON_009"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        verifyNoInteractions(tokenRefreshService);
    }

    /** 서비스가 분류한 오류는 상태·코드·기본 메시지 그대로이며 캐시 금지 헤더가 있다. 인증 체인이라 WWW-Authenticate는 없다. */
    @ParameterizedTest
    @MethodSource("refreshErrors")
    void refreshMapsServiceErrors(ErrorCode errorCode, CapturedOutput output) throws Exception {
        when(tokenRefreshService.refresh(anyString())).thenThrow(new BusinessException(errorCode));

        postRefresh("{\"refreshToken\":\"" + SECRET_REFRESH + "\"}")
                .andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        assertThat(output.getAll()).doesNotContain(SECRET_REFRESH);
    }

    static Stream<Arguments> refreshErrors() {
        return Stream.of(ErrorCode.AUTH_REFRESH_INVALID, ErrorCode.AUTH_REFRESH_REUSED, ErrorCode.AUTH_SESSION_INVALID,
                ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_SERVER_ERROR).map(Arguments::of);
    }

    /**
     * 로그아웃 성공은 정확히 {success, code, message} 세 필드다(data·토큰 없음). Access 없이·잘못된 Bearer·다른 인증 헤더가 붙어도 본문
     * Refresh 원문 그대로 서비스에 넘기고 추가 필드(회원·세션 ID 등)는 무시한다. Bearer 검증·세션 조회를 하지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer not-a-valid-token", "Bearer eyJhbGciOiJub25lIn0.e30.", "Basic dXNlcjpwYXNz"})
    void logoutReturnsExactMessage(String authorization, CapturedOutput output) throws Exception {
        var request = post(LOGOUT_PATH).contentType(APPLICATION_JSON).content("{\"refreshToken\":\"" + SECRET_REFRESH
                + "\",\"memberId\":\"99\",\"sessionId\":7,\"allDevices\":true,\"code\":\"c\"}");
        if (!authorization.isEmpty()) {
            request.header("Authorization", authorization);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(content().json("{\"success\":true,\"code\":\"200\",\"message\":\"세션이 종료되었습니다.\"}",
                        JsonCompareMode.STRICT));

        verify(sessionLogoutService).logout(SECRET_REFRESH);
        verifyNoInteractions(anonymousAuthService, tokenRefreshService, sessionRepository);
        assertThat(output.getAll()).doesNotContain(SECRET_REFRESH);
    }

    /** 패턴에 맞는 값은 trim·대소문자 변환·Base64 정규화 없이 그대로 넘긴다. */
    @ParameterizedTest
    @MethodSource("acceptedRefreshTokens")
    void logoutPassesTokenUnchanged(String refreshToken) throws Exception {
        postLogout("{\"refreshToken\":\"" + refreshToken + "\"}").andExpect(status().isOk());

        verify(sessionLogoutService).logout(refreshToken);
    }

    /** 입력 결정표는 Refresh와 같다(COMMON_004/COMMON_001). 서비스(DB)를 부르지 않고 오류에도 캐시 금지 헤더가 있다. */
    @ParameterizedTest
    @MethodSource("invalidRefreshBodies")
    void logoutRejectsInvalidInputWithoutCallingService(String body, String code) throws Exception {
        postLogout(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        verifyNoInteractions(sessionLogoutService);
    }

    /** 패턴 오류 응답의 value는 가려지고 응답·로그에 원문이 없다. */
    @Test
    void logoutMasksRejectedValue(CapturedOutput output) throws Exception {
        postLogout("{\"refreshToken\":\"" + SECRET_REFRESH + "x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[0].field").value("refreshToken"))
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"))
                .andExpect(content().string(not(containsString(SECRET_REFRESH))));

        verifyNoInteractions(sessionLogoutService);
        assertThat(output.getAll()).doesNotContain(SECRET_REFRESH);
    }

    /** 16,384바이트 초과 본문은 파싱 전에 413이며 서비스를 부르지 않는다. */
    @Test
    void logoutRejectsOversizedBodyBeforeParsing() throws Exception {
        postLogout("{\"refreshToken\":\"" + SECRET_REFRESH + "\",\"pad\":\"" + "a".repeat(16_384) + "\"}")
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("COMMON_009"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        verifyNoInteractions(sessionLogoutService);
    }

    /** DB 장애·commit 실패는 200이 아니라 서비스가 분류한 503/500이며 캐시 금지 헤더가 있다. 인증 체인이라 WWW-Authenticate는 없다. */
    @ParameterizedTest
    @MethodSource("logoutErrors")
    void logoutMapsServiceErrors(ErrorCode errorCode, CapturedOutput output) throws Exception {
        doThrow(new BusinessException(errorCode)).when(sessionLogoutService).logout(anyString());

        postLogout("{\"refreshToken\":\"" + SECRET_REFRESH + "\"}")
                .andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));

        assertThat(output.getAll()).doesNotContain(SECRET_REFRESH);
    }

    static Stream<Arguments> logoutErrors() {
        return Stream.of(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_SERVER_ERROR).map(Arguments::of);
    }

    private ResultActions postLogout(String body) throws Exception {
        return mockMvc.perform(post(LOGOUT_PATH).contentType(APPLICATION_JSON).content(body));
    }

    private ResultActions postRefresh(String body) throws Exception {
        return mockMvc.perform(post(REFRESH_PATH).contentType(APPLICATION_JSON).content(body));
    }

    private ResultActions postBody(String body) throws Exception {
        return mockMvc.perform(post(PATH)
                .header("Authorization", "Bearer not-a-valid-token")
                .contentType(APPLICATION_JSON)
                .content(body));
    }

}
