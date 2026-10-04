package com.gyejoldanji.global.common.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Payload;
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
    private static final String PERIOD_REASON = "시작일은 종료일보다 늦을 수 없습니다.";
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /** 본문 검증 오류에서 민감 값은 가리고 일반 값은 유지한다. */
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

    /** 파싱 실패 응답과 로그에서 요청 원문을 제외한다. */
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

    /** 민감 파라미터의 타입 변환 실패 값을 가린다. */
    @Test
    void masksSensitiveTypeMismatchValue(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/query").param("code", SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"));

        assertThat(output).contains("MethodArgumentTypeMismatchException").doesNotContain(SECRET);
    }

    /** 제약 위반 응답에서 민감 값과 일반 값을 구분한다. */
    @Test
    void masksSensitiveConstraintViolationValue(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[?(@.field=='code')].value").value("[REDACTED]"))
                .andExpect(jsonPath("$.errors[?(@.field=='nickname')].value").value("abcdef"));

        assertThat(output).contains("ConstraintViolationException").doesNotContain(SECRET);
    }

    /** DB 오류 원문을 기록하지 않고 기존 충돌 응답을 유지한다. */
    @Test
    void keepsConflictContractWithoutLoggingDbMessage(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_007"));

        assertThat(output).contains("DataIntegrityViolationException").doesNotContain(SECRET);
    }

    /** 내장 메서드 검증의 파라미터 오류를 400으로 반환한다. */
    @Test
    void returns400ForParameterConstraintWithoutValidated(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/param").param("refreshToken", SECRET).param("nickname", "abcdef"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors[?(@.field=='refreshToken')].value").value("[REDACTED]"))
                .andExpect(jsonPath("$.errors[?(@.field=='nickname')].value").value("abcdef"));

        assertThat(output).contains("HandlerMethodValidationException").doesNotContain(SECRET);
    }

    /** 메서드 검증에 포함된 본문의 민감 필드도 가린다. */
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

    /** 본문에 객체 수준 오류만 있어도 사유를 보존하고 객체 원문은 제외한다. */
    @Test
    void keepsBodyObjectErrorWithoutExposingObject(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/test/object")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + SECRET + "\",\"nickname\":\"abc\",\"start\":2,\"end\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("periodRequest"))
                .andExpect(jsonPath("$.errors[0].value").value(""))
                .andExpect(jsonPath("$.errors[0].reason").value(PERIOD_REASON))
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(output).contains("MethodArgumentNotValidException").doesNotContain(SECRET);
    }

    /** 메서드 검증에 포함된 객체 수준 오류도 사유를 보존한다. */
    @Test
    void keepsMethodValidationObjectErrorWithoutExposingObject(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/test/object-and-param")
                        .param("nickname", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + SECRET + "\",\"nickname\":\"abc\",\"start\":2,\"end\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("periodRequest"))
                .andExpect(jsonPath("$.errors[0].value").value(""))
                .andExpect(jsonPath("$.errors[0].reason").value(PERIOD_REASON))
                .andExpect(content().string(not(containsString(SECRET))));

        assertThat(output).contains("HandlerMethodValidationException").doesNotContain(SECRET);
    }

    /** 두 본문 검증 경로에서 필드 오류와 객체 오류를 함께 유지한다. */
    @Test
    void keepsMixedFieldAndObjectErrors(CapturedOutput output) throws Exception {
        for (String path : new String[]{"/test/object", "/test/object-and-param"}) {
            mockMvc.perform(post(path)
                            .param("nickname", "abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + SECRET + "\",\"nickname\":\"abcdef\",\"start\":2,\"end\":1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"))
                    .andExpect(jsonPath("$.errors.length()").value(2))
                    .andExpect(jsonPath("$.errors[?(@.field=='nickname')].value").value("abcdef"))
                    .andExpect(jsonPath("$.errors[?(@.field=='periodRequest')].value").value(""))
                    .andExpect(jsonPath("$.errors[?(@.field=='periodRequest')].reason").value(PERIOD_REASON))
                    .andExpect(content().string(not(containsString(SECRET))));
        }

        assertThat(output)
                .contains("MethodArgumentNotValidException", "HandlerMethodValidationException")
                .doesNotContain(SECRET);
    }

    /** 반환값 검증 실패를 입력 오류로 취급하지 않고 500으로 반환한다. */
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

    /** 예상하지 못한 예외의 타입과 스택만 기록하고 메시지는 제외한다. */
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

    @ValidPeriod
    record PeriodRequest(String code, @Size(max = 3) String nickname, int start, int end) {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = PeriodValidator.class)
    public @interface ValidPeriod {

        /** 객체 오류에 반환할 안전한 검증 사유다. */
        String message() default PERIOD_REASON;

        /** 기본 검증 그룹을 사용한다. */
        Class<?>[] groups() default {};

        /** 추가 제약 메타데이터를 지정하지 않는다. */
        Class<? extends Payload>[] payload() default {};
    }

    public static class PeriodValidator implements ConstraintValidator<ValidPeriod, PeriodRequest> {

        /** 필드별 오류와 별개로 날짜 순서의 객체 수준 오류를 만든다. */
        @Override
        public boolean isValid(PeriodRequest value, ConstraintValidatorContext context) {
            return value == null || value.start() <= value.end();
        }
    }

    @RestController
    static class TestController {

        /** 본문 필드 검증 실패를 발생시킨다. */
        @PostMapping("/test/body")
        void body(@Valid @RequestBody Request request) {
        }

        /** 숫자 파라미터의 타입 변환 실패를 발생시킨다. */
        @GetMapping("/test/query")
        void query(@RequestParam Integer code) {
        }

        /** DTO 검증 결과를 제약 위반 예외로 전달한다. */
        @GetMapping("/test/violation")
        void violation() {
            throw new ConstraintViolationException(VALIDATOR.validate(new Request(SECRET, "abcdef", null)));
        }

        /** 내장 메서드 검증에 파라미터 길이 제약을 적용한다. */
        @GetMapping("/test/param")
        void param(@RequestParam @Size(max = 8) String refreshToken, @RequestParam @Size(max = 3) String nickname) {
        }

        /** 본문과 파라미터를 함께 메서드 검증에 전달한다. */
        @PostMapping("/test/body-and-param")
        void bodyAndParam(@Valid @RequestBody Request request, @RequestParam @Size(max = 3) String nickname) {
        }

        /** 객체 수준 제약이 있는 본문을 일반 본문 검증에 전달한다. */
        @PostMapping("/test/object")
        void object(@Valid @RequestBody PeriodRequest periodRequest) {
        }

        /** 객체 수준 본문 제약을 파라미터와 함께 메서드 검증에 전달한다. */
        @PostMapping("/test/object-and-param")
        void objectAndParam(@Valid @RequestBody PeriodRequest periodRequest, @RequestParam @Size(max = 3) String nickname) {
        }

        /** 반환값 길이 제약을 위반하는 값을 반환한다. */
        @GetMapping("/test/return")
        @Size(max = 3)
        String returnValue() {
            return SECRET;
        }

        /** 최종 예외 처리에 민감 메시지가 있는 원인 체인을 전달한다. */
        @GetMapping("/test/unhandled")
        void unhandled() {
            throw new IllegalStateException("refresh failed: " + SECRET,
                    new RuntimeException("refreshToken=" + SECRET));
        }

        /** 민감 값이 포함된 DB 중복 오류를 발생시킨다. */
        @GetMapping("/test/duplicate")
        void duplicate() {
            throw new DataIntegrityViolationException("Duplicate entry '" + SECRET + "' for key 'uk_token_hash'");
        }
    }
}
