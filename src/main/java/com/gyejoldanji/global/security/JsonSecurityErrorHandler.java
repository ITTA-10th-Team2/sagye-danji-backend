package com.gyejoldanji.global.security;

import java.io.IOException;

import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security 필터 단계의 인증·권한 실패를 기존 {@link ErrorResponse} JSON과 캐시 금지 헤더로 응답한다. 필터 오류는
 * ControllerAdvice를 거치지 않으므로 이 클래스가 직접 쓴다.
 *
 * <p>Bearer가 없으면 COMMON_005, JWT 검증 오류가 만료 하나뿐이면 AUTH_001, 그 밖의 Bearer 오류(서명·형식·claim)는 AUTH_002,
 * 권한 부족은 COMMON_006이다. 분류는 검증 결과의 오류 코드로 하며 예외 메시지를 보지 않는다.
 */
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public JsonSecurityErrorHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, classify(e));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, ErrorCode.FORBIDDEN);
    }

    /**
     * 오류 코드의 상태·기본 메시지로 응답한다. 401에는 Bearer challenge를 둔다. 토큰·요청 값은 응답·로그에 남기지 않는다.
     * 한글 메시지가 깨지지 않게 응답 인코딩과 Content-Type의 charset을 함께 지정한다.
     */
    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        byte[] json = jsonMapper.writeValueAsBytes(ErrorResponse.of(errorCode));
        response.setStatus(errorCode.getHttpStatus().value());
        if (errorCode.getHttpStatus() == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.setContentLength(json.length);
        response.getOutputStream().write(json);
    }

    /** Bearer 처리 오류는 OAuth2AuthenticationException이다. 그 밖(익명 접근)은 Bearer 누락이다. */
    private static ErrorCode classify(AuthenticationException e) {
        if (!(e instanceof OAuth2AuthenticationException)) {
            return ErrorCode.UNAUTHORIZED;
        }
        boolean onlyExpired = e.getCause() instanceof JwtValidationException invalid
                && invalid.getErrors().stream()
                        .allMatch(error -> AccessTokenValidator.EXPIRED.equals(error.getErrorCode()));
        return onlyExpired ? ErrorCode.AUTH_TOKEN_EXPIRED : ErrorCode.AUTH_TOKEN_INVALID;
    }
}
