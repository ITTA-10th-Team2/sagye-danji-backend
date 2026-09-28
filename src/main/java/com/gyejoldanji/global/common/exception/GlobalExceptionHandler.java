package com.gyejoldanji.global.common.exception;

import java.util.List;

import com.gyejoldanji.global.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 전역 예외 처리기.
 *
 * <p>모든 예외를 {@link ErrorResponse} 형식으로 변환하여 일관된 에러 응답을 보장한다.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /**
     * 서비스 로직에서 직접 던진 {@link BusinessException} 처리.
     *
     * <p>예외에 담긴 {@link ErrorCode}의 HTTP 상태와 메시지를 그대로 응답한다.
     */
    @ExceptionHandler(BusinessException.class)
    protected ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getMessage());
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode, e.getMessage()));
    }

    /**
     * {@code @RequestBody} + {@code @Valid} 검증 실패 시 처리 (400).
     *
     * <p>예: DTO 필드의 {@code @NotBlank}, {@code @Size} 위반. 필드별 오류 목록을 응답한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e) {
        log.warn("MethodArgumentNotValidException: {}", e.getMessage());
        List<ErrorResponse.FieldError> fieldErrors =
                e.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        ErrorResponse.FieldError.of(
                                                error.getField(),
                                                error.getRejectedValue() == null
                                                        ? ""
                                                        : error.getRejectedValue().toString(),
                                                error.getDefaultMessage()))
                        .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors));
    }

    /**
     * {@code @RequestParam}, {@code @PathVariable} 등에 붙은 제약 조건 위반 시 처리 (400).
     *
     * <p>컨트롤러에 {@code @Validated}가 있을 때 발생한다. 예: {@code @Min(1) Long id}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    protected ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException e) {
        log.warn("ConstraintViolationException: {}", e.getMessage());
        List<ErrorResponse.FieldError> fieldErrors =
                e.getConstraintViolations().stream()
                        .map(
                                violation ->
                                        ErrorResponse.FieldError.of(
                                                violation.getPropertyPath().toString(),
                                                violation.getInvalidValue() == null
                                                        ? ""
                                                        : violation.getInvalidValue().toString(),
                                                violation.getMessage()))
                        .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors));
    }

    /**
     * DB 무결성 제약 위반 시 처리 (409).
     *
     * <p>예: unique 키 중복, FK 위반, not null 위반.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    protected ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolationException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.DUPLICATE_DATA));
    }

    /**
     * 요청 본문을 읽을 수 없을 때 처리 (400).
     *
     * <p>예: 잘못된 JSON 문법, 본문 누락, enum/날짜 등 필드 타입 불일치.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_REQUEST_BODY));
    }

    /**
     * 파라미터 타입 변환에 실패했을 때 처리 (400).
     *
     * <p>예: {@code /users/abc} 처럼 Long 타입 {@code @PathVariable}에 문자열이 들어온 경우.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e) {
        log.warn(
                "MethodArgumentTypeMismatchException: name={}, value={}",
                e.getName(),
                e.getValue());
        String requiredType =
                e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "?";
        ErrorResponse.FieldError fieldError =
                ErrorResponse.FieldError.of(
                        e.getName(),
                        e.getValue() == null ? "" : e.getValue().toString(),
                        "타입 변환 실패: " + requiredType + " 형식이어야 합니다.");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, List.of(fieldError)));
    }

    /**
     * 위에서 처리되지 않은 모든 예외의 최종 처리 (500).
     *
     * <p>예상하지 못한 오류이므로 스택트레이스를 {@code error} 레벨로 남기고, 상세 내용은 클라이언트에 노출하지 않는다.
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(
            Exception e, HttpServletRequest request) {
        log.error("Unhandled Exception: ", e);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}