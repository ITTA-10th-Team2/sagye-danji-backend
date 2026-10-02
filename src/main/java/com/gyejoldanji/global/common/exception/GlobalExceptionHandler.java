package com.gyejoldanji.global.common.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.gyejoldanji.global.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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
     * <p>필드·객체 전체의 검증 오류를 응답한다. 로그에는 입력값 없이 필드명 또는 객체명만 남긴다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fieldErrors =
                e.getBindingResult().getAllErrors().stream()
                        .map(GlobalExceptionHandler::toFieldError)
                        .toList();
        log.warn(
                "MethodArgumentNotValidException: fields={}",
                fieldErrors.stream().map(ErrorResponse.FieldError::getField).toList());
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
        List<ErrorResponse.FieldError> fieldErrors =
                e.getConstraintViolations().stream()
                        .map(
                                violation ->
                                        ErrorResponse.FieldError.of(
                                                violation.getPropertyPath().toString(),
                                                violation.getInvalidValue(),
                                                violation.getMessage()))
                        .toList();
        log.warn(
                "ConstraintViolationException: fields={}",
                fieldErrors.stream().map(ErrorResponse.FieldError::getField).toList());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors));
    }

    /**
     * {@code @Validated}가 없는 컨트롤러의 메서드 파라미터 제약 위반 시 처리 (400).
     *
     * <p>Spring 내장 메서드 검증이 던진다. 예: {@code @RequestParam @Size(max = 3) String name}. 파라미터 제약이 함께
     * 있으면 {@code @Valid @RequestBody} 본문 오류도 {@link ParameterErrors}로 여기에 들어온다. 컨트롤러 반환값 검증 실패는
     * 입력 오류가 아닌 서버 오류이므로 500으로 처리한다.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    protected ResponseEntity<ErrorResponse> handleHandlerMethodValidationException(
            HandlerMethodValidationException e) {
        if (e.isForReturnValue()) {
            log.error("Return value validation failed: ", withoutMessages(e));
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
        }
        List<ErrorResponse.FieldError> fieldErrors =
                e.getParameterValidationResults().stream()
                        .flatMap(
                                result ->
                                        // 본문 객체 원문 없이 필드·객체 전체 오류를 함께 변환한다.
                                        result instanceof ParameterErrors errors
                                                ? errors.getAllErrors().stream()
                                                        .map(GlobalExceptionHandler::toFieldError)
                                                : result.getResolvableErrors().stream()
                                                        .map(
                                                                error ->
                                                                        ErrorResponse.FieldError.of(
                                                                                result.getMethodParameter()
                                                                                        .getParameterName(),
                                                                                result.getArgument(),
                                                                                error.getDefaultMessage())))
                        .toList();
        log.warn(
                "HandlerMethodValidationException: fields={}",
                fieldErrors.stream().map(ErrorResponse.FieldError::getField).toList());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors));
    }

    /**
     * DB 무결성 제약 위반 시 처리 (409).
     *
     * <p>예: unique 키 중복, FK 위반, not null 위반. DB 메시지에 중복 값 원문이 있으므로 원인 타입만 로그에 남긴다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    protected ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException e) {
        log.warn(
                "DataIntegrityViolationException: cause={}",
                e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ErrorCode.DUPLICATE_DATA));
    }

    /**
     * 요청 본문을 읽을 수 없을 때 처리 (400).
     *
     * <p>예: 잘못된 JSON 문법, 본문 누락, enum/날짜 등 필드 타입 불일치. 파싱 메시지에 본문 원문이 있으므로 원인 타입만 로그에 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e) {
        log.warn(
                "HttpMessageNotReadableException: cause={}",
                e.getMostSpecificCause().getClass().getSimpleName());
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
        String requiredType =
                e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "?";
        log.warn(
                "MethodArgumentTypeMismatchException: name={}, requiredType={}",
                e.getName(),
                requiredType);
        ErrorResponse.FieldError fieldError =
                ErrorResponse.FieldError.of(
                        e.getName(),
                        e.getValue(),
                        "타입 변환 실패: " + requiredType + " 형식이어야 합니다.");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, List.of(fieldError)));
    }

    /**
     * 위에서 처리되지 않은 모든 예외의 최종 처리 (500).
     *
     * <p>예상하지 못한 오류이므로 스택트레이스를 {@code error} 레벨로 남기고, 상세 내용은 클라이언트에 노출하지 않는다. 예외 메시지에는
     * code·토큰·DB 값 원문이 섞일 수 있어 원인 체인의 타입과 스택트레이스만 남긴다.
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(
            Exception e, HttpServletRequest request) {
        log.error("Unhandled Exception: ", withoutMessages(e));
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** 필드 오류는 입력값을 마스킹하고, 객체 전체 오류는 객체 원문 없이 이름·사유만 응답한다. */
    private static ErrorResponse.FieldError toFieldError(ObjectError error) {
        if (error instanceof org.springframework.validation.FieldError fieldError) {
            return ErrorResponse.FieldError.of(
                    fieldError.getField(),
                    fieldError.getRejectedValue(),
                    // 바인딩 실패 기본 메시지에는 입력 원문이 들어 있다.
                    fieldError.isBindingFailure() ? "타입 변환 실패" : fieldError.getDefaultMessage());
        }
        return ErrorResponse.FieldError.of(error.getObjectName(), null, error.getDefaultMessage());
    }

    /**
     * 메시지를 버린 예외 사본. 각 원인을 {@code Throwable: 원래타입}과 원래 스택트레이스로 바꿔 원인 체인을 유지한다.
     *
     * <p>ponytail: suppressed 예외는 버린다. 필요해지면 같은 방식으로 복사한다.
     */
    private static Throwable withoutMessages(Throwable e) {
        Throwable head = null;
        Throwable tail = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            Throwable copy = new Throwable(t.getClass().getName());
            copy.setStackTrace(t.getStackTrace());
            if (tail == null) {
                head = copy;
            } else {
                tail.initCause(copy);
            }
            tail = copy;
        }
        return head;
    }
}
