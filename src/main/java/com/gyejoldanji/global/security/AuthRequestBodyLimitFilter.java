package com.gyejoldanji.global.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.response.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * 세 인증 POST의 실제 요청 본문을 JSON 파싱 전에 {@value #MAX_BODY_BYTES}바이트로 제한한다.
 *
 * <p>Content-Length가 상한을 넘으면 본문을 읽지 않고 거부한다. 그 밖에는 길이 헤더·chunked 여부와 관계없이 최대 상한+1바이트만
 * 읽어 판단하고, 상한 이하 본문만 다시 읽을 수 있게 감싸 다음 단계로 넘긴다. 필터 오류는 ControllerAdvice를 거치지 않으므로 기존
 * {@link ErrorResponse}로 직접 응답한다.
 *
 * <p>Spring Bean으로 만들지 않고 Security 체인에서만 생성하므로 Servlet 필터로 자동 등록되지 않는다.
 */
public class AuthRequestBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 16_384;

    /** MVC와 같은 경로 해석(디코딩·정확 일치)으로 세 POST만 고른다. */
    private static final RequestMatcher AUTH_POSTS = new OrRequestMatcher(
            pathPattern(HttpMethod.POST, "/api/auth/anonymous"),
            pathPattern(HttpMethod.POST, "/api/auth/refresh"),
            pathPattern(HttpMethod.POST, "/api/auth/logout"));

    private final JsonMapper jsonMapper;

    public AuthRequestBodyLimitFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AUTH_POSTS.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            rejectTooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            rejectTooLarge(response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** 413 COMMON_009를 기존 오류 JSON과 캐시 금지 헤더로 응답한다. 요청 본문은 응답·로그에 남기지 않는다. */
    private void rejectTooLarge(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.REQUEST_BODY_TOO_LARGE;
        byte[] json = jsonMapper.writeValueAsBytes(ErrorResponse.of(errorCode));
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setContentLength(json.length);
        response.getOutputStream().write(json);
    }

    /** 이미 읽은 본문을 다시 제공한다. Servlet 계약대로 getInputStream과 getReader 중 하나만 사용할 수 있다. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called");
            }
            if (inputStream == null) {
                inputStream = new CachedBodyInputStream(body);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws UnsupportedEncodingException {
            if (inputStream != null) {
                throw new IllegalStateException("getInputStream() has already been called");
            }
            if (reader == null) {
                String encoding = getCharacterEncoding();
                // 인코딩 지정이 없으면 JSON 기본값인 UTF-8로 읽는다.
                reader = new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(body), encoding != null ? encoding : "UTF-8"));
            }
            return reader;
        }
    }

    private static final class CachedBodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream source;

        CachedBodyInputStream(byte[] body) {
            this.source = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return source.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return source.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return source.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        /** ponytail: 비동기(non-blocking) 읽기 미지원. MVC는 blocking 읽기만 쓴다. 비동기 Servlet IO가 필요해지면 구현한다. */
        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Non-blocking read is not supported");
        }
    }
}
