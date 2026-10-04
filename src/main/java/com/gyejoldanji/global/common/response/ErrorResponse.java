package com.gyejoldanji.global.common.response;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.gyejoldanji.global.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공통 에러 응답 DTO.
 *
 * <p>{@code success}는 항상 {@code false}이며, 검증 오류 시 {@code errors}에 필드·객체 전체의 상세 정보가 포함된다.
 *
 * @see ErrorCode
 * @see FieldError
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @Schema(description = "요청 성공 여부", example = "false")
    private final boolean success;

    @Schema(description = "커스텀 에러 코드", example = "COMMON_001")
    private final String code;

    @Schema(description = "에러 메시지", example = "잘못된 입력값입니다.")
    private final String message;

    @Schema(description = "필드·객체 전체의 상세 에러 목록 (Validation 에러 시)")
    private final List<FieldError> errors;

    /** 오류 코드의 기본 메시지로 응답하며 상세 오류 목록은 생략한다. */
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(false, errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 오류 코드와 지정한 메시지로 응답한다. */
    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(false, errorCode.getCode(), message, null);
    }

    /** 오류 코드의 기본 메시지와 필드·객체 전체의 검증 오류를 함께 응답한다. */
    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(false, errorCode.getCode(), errorCode.getMessage(), errors);
    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class FieldError {

        @Schema(description = "에러 발생 필드명 또는 객체명", example = "nickname")
        private final String field;

        @Schema(description = "입력된 값", example = "")
        private final String value;

        @Schema(description = "에러 사유", example = "닉네임은 필수입니다.")
        private final String reason;

        /** 필드명에 포함되면 입력 원문 대신 {@code [REDACTED]}를 응답하는 키워드 (code·토큰·키 등). */
        private static final List<String> SENSITIVE_FIELD_KEYWORDS =
                List.of("code", "token", "key", "hash", "authorization", "password", "secret");

        /** {@code value}가 null이면 빈 문자열, 민감 필드면 {@code [REDACTED]}로 응답한다. */
        public static FieldError of(String field, Object value, String reason) {
            return new FieldError(field, maskValue(field, value), reason);
        }

        /** null은 빈 문자열로, 민감 필드 또는 알 수 없는 필드의 입력은 마스킹해 변환한다. */
        private static String maskValue(String field, Object value) {
            if (value == null) {
                return "";
            }
            if (field == null) {
                // 필드명을 모르면 민감 여부를 판단할 수 없으므로 가린다.
                return "[REDACTED]";
            }
            String lowerField = field.toLowerCase(Locale.ROOT);
            return SENSITIVE_FIELD_KEYWORDS.stream().anyMatch(lowerField::contains)
                    ? "[REDACTED]"
                    : value.toString();
        }
    }
}
