package com.gyejoldanji.domain.auth.dto;

import java.util.List;
import java.util.Map;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.global.security.SecurityTestConfig;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * anonymous 입력 계약(T23 입력 부분)을 실제 Boot JSON 바인딩 → {@code @Valid} → 기존 오류 처리와 운영 Security 체인(본문 제한
 * 필터 포함)으로 검증한다.
 *
 * <p>{@link SecurityTestConfig}(웹·Jackson·Validation·Security·JWT test 키)만 올리고 DB·토스는 쓰지 않는다. 컨트롤러는 이
 * 테스트 전용이다.
 */
@SpringBootTest(classes = AnonymousAuthRequestTest.TestApplication.class)
@ExtendWith(OutputCaptureExtension.class)
class AnonymousAuthRequestTest {

    private static final String PATH = "/api/auth/anonymous";
    private static final String SECRET = "SECRET-ANON-CODE-1234";
    /** 보조 평면 문자(U+1F600): code point 1개, UTF-16 2단위, UTF-8 4바이트. */
    private static final String EMOJI = "\uD83D\uDE00";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Validator validator;

    /** 보호 체인의 세션 필터용. 인증 체인 요청에서는 호출되지 않는다. */
    @MockitoBean
    private AuthSessionRepository sessionRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        SecurityTestConfig.register(registry);
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** 입력 결정표: 본문·최상위·code 타입 오류는 COMMON_004, 정상 객체의 누락·null·빈 값은 COMMON_001. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ''                       | COMMON_004
            '{'                      | COMMON_004
            '{"code":"abc"'          | COMMON_004
            '{"code":"abc"} x'       | COMMON_004
            'null'                   | COMMON_004
            '[]'                     | COMMON_004
            '[{"code":"abc"}]'       | COMMON_004
            '"abc"'                  | COMMON_004
            '123'                    | COMMON_004
            'true'                   | COMMON_004
            '{"code":123}'           | COMMON_004
            '{"code":1.5}'           | COMMON_004
            '{"code":true}'          | COMMON_004
            '{"code":false}'         | COMMON_004
            '{"code":["abc"]}'       | COMMON_004
            '{"code":[]}'            | COMMON_004
            '{"code":{"v":"abc"}}'   | COMMON_004
            '{}'                     | COMMON_001
            '{"code":null}'          | COMMON_001
            '{"code":""}'            | COMMON_001
            '{"code":"   "}'         | COMMON_001
            '{"anonKey":"abc"}'      | COMMON_001
            """)
    void rejectsByDecisionTable(String body, String errorCode) throws Exception {
        postBody(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(errorCode));
    }

    /**
     * OpenAPI(ECMAScript) 공백만 있는 code는 COMMON_001이고 값은 원문 대신 [REDACTED]로 응답한다.
     *
     * <p>공백, 탭, LF, CRLF, VT, FF, NBSP, EM SPACE, NARROW NBSP, 전각 공백, LS, PS, BOM, 혼합.
     */
    @ParameterizedTest
    @ValueSource(strings = {" ", "\t", "\n", "\r\n", "\u000B", "\f", "\u00A0", "\u2003", "\u202F", "\u3000",
            "\u2028", "\u2029", "\uFEFF", " \u00A0\u3000\t"})
    void rejectsWhitespaceOnlyCode(String code) throws Exception {
        postCode(code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"));
    }

    /**
     * OpenAPI 기준 비공백 문자가 하나라도 있으면 통과하고, trim·대소문자·정규화 없이 원문 그대로 도착한다.
     *
     * <p>대소문자, 앞뒤 공백, NBSP·전각 공백 사이 문자, 제어 문자 U+0001, 정보 구분자 U+001C, NEL, 폭 없는 공백, 전각 A,
     * 결합 문자(e + U+0301), 이모지.
     */
    @ParameterizedTest
    @ValueSource(strings = {"AbC", " a ", "\u00A0a\u3000", "\u0001", "\u001C", "\u0085", "\u200B", "\uFF21",
            "e\u0301", EMOJI})
    void keepsValidCodeUnchanged(String code) throws Exception {
        postCode(code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(code));
    }

    /** Java 기본 공백 판정은 OpenAPI(ECMAScript)와 달라 그대로 쓸 수 없다. 위 두 테스트의 대조 근거다. */
    @Test
    void javaBuiltInWhitespaceDiffersFromOpenApi() {
        assertThat("\u00A0".isBlank()).isFalse();       // NBSP: OpenAPI 공백, isBlank는 비공백
        assertThat("\u3000".matches("\\s")).isFalse();  // 전각 공백: OpenAPI 공백, Java 기본 \s 아님
        assertThat("\u001C".isBlank()).isTrue();        // 정보 구분자: OpenAPI 비공백, isBlank는 공백
        assertThat("\u0001".trim()).isEmpty();          // 제어 문자: OpenAPI 비공백, @NotBlank(trim 기반)는 공백
    }

    /** 4096 code point까지 통과하고 4097부터 COMMON_001이다. String.length()가 아니라 code point로 센다. */
    @Test
    void limitsCodeByCodePointsOverHttp() throws Exception {
        // 한글은 UTF-8 3바이트, 이모지 2048개 + ASCII 2048개는 length() 6144지만 code point는 4096이다. 둘 다 16KB 이하.
        for (String code : List.of("가".repeat(4096), EMOJI.repeat(2048) + "a".repeat(2048))) {
            assertThat(code.codePointCount(0, code.length())).isEqualTo(4096);

            postCode(code).andExpect(status().isOk()).andExpect(jsonPath("$.received").value(code));
            postCode(code + "a")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"))
                    .andExpect(jsonPath("$.errors[0].field").value("code"));
        }
    }

    /** 이모지 4096개는 JSON이 16KB를 넘어 HTTP에서는 413이 먼저 나므로, 길이 경계는 DTO 검증기로 따로 확인한다. */
    @Test
    void validatesSupplementaryCodePointBoundaryWithoutHttp() {
        assertThat(validator.validate(new AnonymousAuthRequest(EMOJI.repeat(4096)))).isEmpty();
        assertThat(validator.validate(new AnonymousAuthRequest(EMOJI.repeat(4097))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("code");
    }

    /** 본문 제한 필터가 JSON 파싱·DTO 검증보다 먼저 413 COMMON_009를 낸다. */
    @Test
    void bodyLimitComesBeforeParsingAndValidation() throws Exception {
        List<String> largeBodies = List.of(
                json(EMOJI.repeat(4096)),                                    // DTO로는 유효
                "{\"code\":123,\"extra\":\"" + "a".repeat(16_384) + "\"}",   // 타입 오류
                "{\"code\":\"" + "a".repeat(16_384) + "\"}",                 // 길이 오류
                "{" + "a".repeat(16_384));                                   // 잘못된 JSON
        for (String body : largeBodies) {
            assertThat(body.getBytes(UTF_8).length).isGreaterThan(16_384);

            postBody(body)
                    .andExpect(status().is(413))
                    .andExpect(jsonPath("$.code").value("COMMON_009"));
        }
    }

    /** 추가 필드는 값·타입과 관계없이 무시하고 code만 사용한다. */
    @Test
    void ignoresExtraFieldsOfAnyType() throws Exception {
        postBody("{\"memberId\":1,\"anonKey\":{\"x\":[1,true,null]},\"env\":\"prod\",\"code\":\"abc\",\"extra\":[\"z\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value("abc"));
    }

    /** 값·형식 오류의 응답과 로그, DTO 문자열 표현에 code 원문을 남기지 않는다. */
    @Test
    void doesNotExposeCode(CapturedOutput output) throws Exception {
        String numericSecret = "987654321987654321";

        postCode(SECRET + "a".repeat(4096))
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"))
                .andExpect(content().string(not(containsString(SECRET))));
        postBody("{\"code\":" + numericSecret + "}")
                .andExpect(jsonPath("$.code").value("COMMON_004"))
                .andExpect(content().string(not(containsString(numericSecret))));
        postBody("{\"code\":[\"" + SECRET + "\"]}")
                .andExpect(jsonPath("$.code").value("COMMON_004"))
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(new AnonymousAuthRequest(SECRET).toString()).doesNotContain(SECRET);
        assertThat(output).doesNotContain(SECRET).doesNotContain(numericSecret);
    }

    /**
     * 인증 DTO 밖의 기존 JSON 동작(숫자→문자열 자동 변환)은 바뀌지 않는다. 보호 체인은 이 test 경로를 거부하므로 바인딩만 보려고
     * Security 필터 없이 보낸다.
     */
    @Test
    void keepsDefaultJsonBehaviorForOtherDtos() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build().perform(post("/test/general").contentType(APPLICATION_JSON).content("{\"name\":123,\"unknown\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value("123"));
    }

    private ResultActions postCode(String code) throws Exception {
        return postBody(json(code));
    }

    private ResultActions postBody(String body) throws Exception {
        return mockMvc.perform(post(PATH).contentType(APPLICATION_JSON).content(body));
    }

    private String json(String code) {
        return jsonMapper.writeValueAsString(Map.of("code", code));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SecurityTestConfig.class, TestController.class})
    static class TestApplication {
    }

    record GeneralRequest(String name) {
    }

    @RestController
    static class TestController {

        /** 검증을 통과해 컨트롤러가 받은 code를 그대로 돌려준다. */
        @PostMapping(PATH)
        Map<String, String> anonymous(@Valid @RequestBody AnonymousAuthRequest request) {
            return Map.of("received", request.code());
        }

        /** 인증 DTO가 아닌 일반 DTO의 기존 바인딩을 확인한다. */
        @PostMapping("/test/general")
        Map<String, String> general(@RequestBody GeneralRequest request) {
            return Map.of("received", request.name());
        }
    }
}
