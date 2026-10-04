package com.gyejoldanji.global.common.exception;

import lombok.Getter;

/**
 * 비즈니스 로직 예외의 베이스 클래스.
 *
 * <p>도메인 규칙 위반 시 {@link ErrorCode}와 함께 던지면 {@link GlobalExceptionHandler}가 일관된 에러 응답으로 변환한다.
 *
 * <pre>{@code
 * throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
 * throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "게시글을 찾을 수 없습니다.");
 * }</pre>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 에러 코드의 기본 메시지로 비즈니스 예외를 생성한다. */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 에러 코드와 사용자 정의 메시지로 비즈니스 예외를 생성한다. */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 에러 코드와 원인 예외로 비즈니스 예외를 생성한다. */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    /** 에러 코드·사용자 정의 메시지·원인 예외로 비즈니스 예외를 생성한다. */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
