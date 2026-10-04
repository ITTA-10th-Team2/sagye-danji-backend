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
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_008", "일시적으로 서비스를 이용할 수 없습니다."),
    REQUEST_BODY_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "COMMON_009", "요청 본문이 너무 큽니다."),

    // Auth
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증 정보가 만료되었습니다."),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_002", "유효하지 않은 인증 정보입니다."),
    AUTH_SESSION_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", "종료되었거나 사용할 수 없는 세션입니다."),
    AUTH_CODE_REJECTED(HttpStatus.UNAUTHORIZED, "AUTH_004", "사용자 인증을 다시 시도해 주세요."),
    AUTH_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_007", "사용자 인증 연결을 일시적으로 사용할 수 없습니다."),
    MEMBER_INACTIVE(HttpStatus.FORBIDDEN, "AUTH_008", "현재 이용할 수 없는 계정입니다."),
    AUTH_PROVIDER_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "AUTH_010", "사용자 인증 응답을 처리할 수 없습니다."),
    AUTH_PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AUTH_012", "사용자 인증 응답 시간이 초과되었습니다."),
    AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_014", "요청이 많습니다. 잠시 후 다시 시도해 주세요."),

    // Record
    RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "RECORD_001", "기록을 찾을 수 없습니다."),
    RECORD_IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "RECORD_002", "기록에는 이미지가 한 장 이상 필요합니다."),
    RECORD_IMAGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "RECORD_003", "기록에 첨부할 수 있는 이미지 수를 초과했습니다."),
    RECORD_IMAGE_ORDER_INVALID(HttpStatus.BAD_REQUEST, "RECORD_004", "이미지 표시 순서가 올바르지 않습니다."),

    // Image
    IMAGE_ALREADY_ATTACHED(HttpStatus.CONFLICT, "IMAGE_001", "이미 사용 중인 이미지입니다."),

    // Content Sync
    CONTENT_SYNC_INVALID_ROW(
            HttpStatus.BAD_REQUEST,
            "CONTENT_001",
            "콘텐츠 동기화 입력값이 올바르지 않습니다."),

    // Recommendation
    RECOMMENDATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RECOMMENDATION_001",
            "오늘 노출할 추천 콘텐츠를 찾을 수 없습니다."),
    RECOMMENDATION_CANDIDATES_INSUFFICIENT(
            HttpStatus.CONFLICT,
            "RECOMMENDATION_002",
            "주간 추천 후보가 7개보다 적습니다."),
    RECOMMENDATION_ORDER_INVALID(
            HttpStatus.BAD_REQUEST,
            "RECOMMENDATION_003",
            "추천 순서는 0부터 6까지여야 합니다."),
    RECOMMENDATION_NOT_ASSIGNED(
            HttpStatus.CONFLICT,
            "RECOMMENDATION_004",
            "이번 주 추천으로 배정되지 않은 콘텐츠입니다."),
    RECOMMENDATION_MATERIAL_NOT_UNIQUE(
            HttpStatus.CONFLICT,
            "RECOMMENDATION_005",
            "추천 소재로 콘텐츠를 하나만 식별할 수 없습니다."),

    // Google Sheets
    GOOGLE_SHEETS_API_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GOOGLE_SHEETS_001",
            "Google Sheets 연동 중 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
