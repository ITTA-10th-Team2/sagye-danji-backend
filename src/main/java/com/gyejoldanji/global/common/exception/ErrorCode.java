package com.gyejoldanji.global.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 애플리케이션 에러 코드 정의.
 *
 * <p>모든 에러 코드는 단일 enum에 정의하며, 도메인별 섹션 주석으로 구분한다. 코드 값은 {@code {DOMAIN}_{NNN}} 형식을 따른다 (예: {@code
 * COMMON_001}, {@code USER_001}).
 *
 * <pre>
 * // 도메인 에러 코드 추가 예시
 * // User
 * USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "사용자를 찾을 수 없습니다."),
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "잘못된 입력값입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_002", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_003", "서버 내부 오류가 발생했습니다."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "COMMON_004", "요청 본문을 파싱할 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_005", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_006", "접근 권한이 없습니다."),
    DUPLICATE_DATA(HttpStatus.CONFLICT, "COMMON_007", "이미 존재하는 데이터입니다."),
    REQUEST_BODY_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "COMMON_009", "요청 본문이 너무 큽니다."),

    // Content Sync
    CONTENT_SYNC_INVALID_ROW(
            HttpStatus.BAD_REQUEST,
            "CONTENT_001",
            "콘텐츠 동기화 입력값이 올바르지 않습니다."),

    // Google Sheets
    GOOGLE_SHEETS_API_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GOOGLE_SHEETS_001",
            "Google Sheets 연동 중 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
