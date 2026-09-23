package com.gyejoldanji.global.common.exception;

import com.gyejoldanji.global.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. 우리가 만든 커스텀 예외 처리 (가장 많이 타게 될 로직)
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("CustomException: {}", errorCode.getMessage());

        return ResponseEntity
                .status(errorCode.getHttpStatus()) // HTTP 헤더 상태 코드 (예: 400, 401, 404)
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage())); // JSON 바디 (예: "C009", "서버 에러")
    }

    // 2. 잘못된 요청 (파라미터 타입 불일치, DTO 유효성 검사 실패 등)
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        log.warn("Bad Request: {}", e.getMessage());
        ErrorCode errorCode = ErrorCode.BAD_REQUEST;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    // 3. 권한 부족 (Spring Security 관련)
    @ExceptionHandler({
            AuthorizationDeniedException.class,
            AccessDeniedException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(Exception e) {
        log.warn("Access Denied (권한 없음): {}", e.getMessage());
        ErrorCode errorCode = ErrorCode.AUTH_FORBIDDEN;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    // 4. 위에서 못 잡은 모든 서버 내부 에러 (최후의 보루)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAllException(Exception e) {
        log.error("Internal Server Error: ", e); // 500 에러는 원인 파악을 위해 꼭 error 레벨로 전체 스택 트레이스를 남깁니다.
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), errorCode.getMessage()));
    }
}