package com.gyejoldanji.global.common.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.gyejoldanji.global.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공통 에러 응답 DTO.
 *
 * <p>{@code success}는 항상 {@code false}이며, Validation 에러 시 {@code errors} 필드에 필드별 상세 정보가 포함된다.
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

    @Schema(description = "필드별 상세 에러 목록 (Validation 에러 시)")
    private final List<FieldError> errors;

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(false, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(false, errorCode.getCode(), message, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(false, errorCode.getCode(), errorCode.getMessage(), errors);
    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class FieldError {

        @Schema(description = "에러 발생 필드명", example = "nickname")
        private final String field;

        @Schema(description = "입력된 값", example = "")
        private final String value;

        @Schema(description = "에러 사유", example = "닉네임은 필수입니다.")
        private final String reason;

        public static FieldError of(String field, String value, String reason) {
            return new FieldError(field, value, reason);
        }
    }
}