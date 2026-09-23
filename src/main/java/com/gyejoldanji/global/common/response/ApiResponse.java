package com.gyejoldanji.global.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponse<T> {

    // 커스텀 비즈니스 코드 (성공 시 "200")
    private final String code;

    // 클라이언트용 메시지
    private final String message;

    // 응답 데이터 (null이면 JSON에서 제외)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final T data;

    /**
     * 성공 응답 (기본 메시지 "성공" + 데이터)
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("200", "성공", data);
    }

    /**
     * 성공 응답 (커스텀 메시지 + 데이터)
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>("200", message, data);
    }

    /**
     * 성공 응답 (데이터 없음 - 수정/삭제 등)
     */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>("200", "성공", null);
    }

    /**
     * 에러 응답
     */
    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}