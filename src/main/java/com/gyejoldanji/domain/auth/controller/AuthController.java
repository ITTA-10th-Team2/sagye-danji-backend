package com.gyejoldanji.domain.auth.controller;

import com.gyejoldanji.domain.auth.dto.AnonymousAuthRequest;
import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.service.AnonymousAuthService;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.global.common.response.ApiResponse;
import com.gyejoldanji.global.common.response.ErrorResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 익명 인증 API.
 *
 * <p>본문 크기 제한·캐시 금지 헤더는 Security 체인의 본문 제한 필터가, 입력·오류 응답 형식은 기존 검증과 GlobalExceptionHandler가
 * 맡는다. Bearer 헤더는 보지 않는다(본문 code로만 인증).
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AnonymousAuthService anonymousAuthService;

    /** 토스 일회용 code로 회원 세션을 만들고 자체 Access·Refresh 토큰을 발급한다. */
    @PostMapping("/anonymous")
    public ApiResponse<AnonymousAuthResponse> anonymous(@Valid @RequestBody AnonymousAuthRequest request) {
        return ApiResponse.ok(anonymousAuthService.authenticate(request.code()));
    }

    /** 토스 요청 한도 초과(429 AUTH_014). 검증된 대기 초가 있을 때만 Retry-After로 전달한다. */
    @ExceptionHandler(TossAnonymousAuthClient.RateLimitedException.class)
    ResponseEntity<ErrorResponse> handleRateLimited(TossAnonymousAuthClient.RateLimitedException e) {
        log.warn("BusinessException: {}", e.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getErrorCode().getHttpStatus());
        if (e.getRetryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, e.getRetryAfterSeconds().toString());
        }
        return response.body(ErrorResponse.of(e.getErrorCode()));
    }
}
