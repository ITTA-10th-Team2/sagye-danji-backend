package com.gyejoldanji.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import com.gyejoldanji.global.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * 행동 이벤트 수집 POST의 본문을 인증·파싱 전에 {@value #MAX_BODY_BYTES}바이트로 제한하고 전송 형식을 확인한다.
 *
 * <p>{@link AuthRequestBodyLimitFilter}와 같은 방식이다. Content-Length가 상한을 넘으면 읽지 않고 413, 그 밖에는 최대 상한+1바이트만
 * 읽어 판단한다. 이어서 Content-Type이 {@code application/json}(charset 없음 또는 UTF-8)이고 Content-Encoding이 없거나
 * {@code identity}인지 보고 아니면 415다. 오류는 {@link JsonSecurityErrorHandler#write}로 기존 오류 JSON을 쓴다.
 *
 * <p>Spring Bean으로 만들지 않고 보호 체인에서만 생성한다.
 */
public class AnalyticsRequestBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 262_144;

    private static final RequestMatcher ANALYTICS_POST = pathPattern(HttpMethod.POST, "/api/analytics/events");

    private final JsonSecurityErrorHandler errors;

    public AnalyticsRequestBodyLimitFilter(JsonSecurityErrorHandler errors) {
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ANALYTICS_POST.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            errors.write(response, ErrorCode.ANALYTICS_REQUEST_TOO_LARGE);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            errors.write(response, ErrorCode.ANALYTICS_REQUEST_TOO_LARGE);
            return;
        }
        if (!isUtf8Json(request.getContentType()) || !isIdentityEncoding(request)) {
            errors.write(response, ErrorCode.ANALYTICS_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        filterChain.doFilter(new AuthRequestBodyLimitFilter.CachedBodyRequest(request, body), response);
    }

    private static boolean isUtf8Json(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return type.equalsTypeAndSubtype(MediaType.APPLICATION_JSON)
                    && (type.getCharset() == null || type.getCharset().equals(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            // 형식이 틀리거나 모르는 charset이다.
            return false;
        }
    }

    private static boolean isIdentityEncoding(HttpServletRequest request) {
        return Collections.list(request.getHeaders(HttpHeaders.CONTENT_ENCODING)).stream()
                .allMatch(value -> value.strip().equalsIgnoreCase("identity"));
    }
}
