package com.gyejoldanji.global.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500", "서버 에러, 관리자에게 문의하세요."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_400", "잘못된 요청입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_404", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_405", "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "COMMON_409", "이미 존재하는 데이터입니다."),

    // Auth
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_401", "인증이 필요합니다."),
    AUTH_INVALID_CODE(HttpStatus.UNAUTHORIZED, "AUTH_401_CODE", "유효하지 않은 소셜 인가 코드입니다."),
    AUTH_ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_401_ACCESS", "Access Token이 만료되었습니다."),
    AUTH_REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_401_REFRESH", "Refresh Token이 만료되었습니다. 다시 로그인이 필요합니다."),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH_403", "해당 API에 대한 접근 권한(관리자 등)이 없습니다."),

    // OAuth
    INVALID_PROVIDER(HttpStatus.BAD_REQUEST, "AUTH_400_PROVIDER", "지원하지 않는 소셜 로그인 provider입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}