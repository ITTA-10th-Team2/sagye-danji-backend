package com.gyejoldanji.global.common.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 민감 입력(code·토큰)이 오류 응답과 로그에 원문으로 남지 않고, 기존 오류 계약은 유지되는지 검증한다. */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private static final String SECRET = "SECRET-REFRESH-TOKEN-1234";
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void masksSensitiveBodyFieldAndKeepsGeneralValue(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + SECRET + "\",\"nickname\":\"abcdef\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[?(@.field=='code')].value").value("[REDACTED]"))
                .andExpect(jsonPath("$.errors[?(@.field=='nickname')].value").value("abcdef"))
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(output).contains("MethodArgumentNotValidException").doesNotContain(SECRET);
    }

    @Test
    void doesNotLogParsingSourceText(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\",\"count\":\"" + SECRET + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(output).contains("HttpMessageNotReadableException").doesNotContain(SECRET);
    }

    @Test
    void masksSensitiveTypeMismatchValue(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/query").param("code", SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"));

        assertThat(output).contains("MethodArgumentTypeMismatchException").doesNotContain(SECRET);
    }

    @Test
    void masksSensitiveConstraintViolationValue(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[?(@.field=='code')].value").value("[REDACTED]"))
                .andExpect(jsonPath("$.errors[?(@.field=='nickname')].value").value("abcdef"));

        assertThat(output).contains("ConstraintViolationException").doesNotContain(SECRET);
    }

    @Test
    void keepsConflictContractWithoutLoggingDbMessage(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_007"));

        assertThat(output).contains("DataIntegrityViolationException").doesNotContain(SECRET);
    }

    @Test
    void returns400ForParameterConstraintWithoutValidated(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/param").param("refreshToken", SECRET).param("nickname", "abcdef"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[?(@.field=='refreshToken')].value").value("[REDACTED]"))
                .andExpect(jsonPath("$.errors[?(@.field=='nickname')].value").value("abcdef"));

        assertThat(output).contains("HandlerMethodValidationException").doesNotContain(SECRET);
    }

    @Test
    void masksBodyFieldsValidatedByMethodValidation(CapturedOutput output) throws Exception {
        // 파라미터 제약이 함께 있으면 @Valid 본문도 메서드 검증(ParameterErrors)으로 처리된다.
        mockMvc.perform(post("/test/body-and-param")
                        .param("nickname", "abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + SECRET + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[?(@.field=='code')].value").value("[REDACTED]"))
                .andExpect(jsonPath("$.errors[?(@.field=='nickname')].value").value("abcdef"))
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void returns500ForReturnValueConstraint(CapturedOutput output) throws Exception {
        // 반환값 검증 실패는 클라이언트 입력이 아니라 서버 오류다.
        mockMvc.perform(get("/test/return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_003"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(output).contains("HandlerMethodValidationException").doesNotContain(SECRET);
    }

    @Test
    void logsUnhandledExceptionTypesAndStackWithoutMessages(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_003"))
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(output)
                .contains("IllegalStateException", "RuntimeException", "GlobalExceptionHandlerTest$TestController.unhandled")
                .doesNotContain(SECRET);
    }

    record Request(@NotBlank @Size(max = 8) String code, @Size(max = 3) String nickname, Integer count) {
    }

    @RestController
    static class TestController {

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Request request) {
        }

        @GetMapping("/test/query")
        void query(@RequestParam Integer code) {
        }

        @GetMapping("/test/violation")
        void violation() {
            throw new ConstraintViolationException(VALIDATOR.validate(new Request(SECRET, "abcdef", null)));
        }

        @GetMapping("/test/param")
        void param(@RequestParam @Size(max = 8) String refreshToken, @RequestParam @Size(max = 3) String nickname) {
        }

        @PostMapping("/test/body-and-param")
        void bodyAndParam(@Valid @RequestBody Request request, @RequestParam @Size(max = 3) String nickname) {
        }

        @GetMapping("/test/return")
        @Size(max = 3)
        String returnValue() {
            return SECRET;
        }

        @GetMapping("/test/unhandled")
        void unhandled() {
            throw new IllegalStateException("refresh failed: " + SECRET,
                    new RuntimeException("refreshToken=" + SECRET));
        }

        @GetMapping("/test/duplicate")
        void duplicate() {
            throw new DataIntegrityViolationException("Duplicate entry '" + SECRET + "' for key 'uk_token_hash'");
        }
    }
}
