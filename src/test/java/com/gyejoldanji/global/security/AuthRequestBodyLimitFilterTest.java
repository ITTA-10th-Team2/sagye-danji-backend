package com.gyejoldanji.global.security;

import java.io.ByteArrayInputStream;
import java.util.Map;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** 인증 POST 본문 제한 필터의 바이트 경계·읽기량·적용 범위·초과 응답을 Servlet mock으로 검증한다. */
@ExtendWith(OutputCaptureExtension.class)
class AuthRequestBodyLimitFilterTest {

    static final String SECRET_CODE = "SECRET-AUTH-CODE-1234";
    static final Map<String, Object> TOO_LARGE_BODY =
            Map.of("success", false, "code", "COMMON_009", "message", "요청 본문이 너무 큽니다.");

    private static final int LIMIT = AuthRequestBodyLimitFilter.MAX_BODY_BYTES;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuthRequestBodyLimitFilter filter = new AuthRequestBodyLimitFilter(JSON);

    /** 길이 헤더 유무와 관계없이 상한까지의 본문을 원문 바이트 그대로 넘긴다. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout"})
    void passesBodyUpToLimitUnchanged(String path) throws Exception {
        byte[] body = jsonOfBytes(LIMIT);
        for (long contentLength : new long[]{-1, LIMIT}) {
            TrackedRequest request = new TrackedRequest("POST", path, body, contentLength);
            Exchange exchange = send(request);

            assertThat(exchange.response().getStatus()).isEqualTo(200);
            assertThat(exchange.forwarded()).isNotSameAs(request);
            assertThat(exchange.forwarded().getInputStream().readAllBytes()).isEqualTo(body);
        }
    }

    /** 길이 헤더가 없는 상한+1바이트 본문을 거부하고 다음 단계를 호출하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout"})
    void rejectsOneByteOverLimitWithoutLength(String path) throws Exception {
        TrackedRequest request = new TrackedRequest("POST", path, jsonOfBytes(LIMIT + 1), -1);
        Exchange exchange = send(request);

        assertThat(exchange.response().getStatus()).isEqualTo(413);
        assertThat(exchange.forwarded()).isNull();
        assertThat(request.bytesRead()).isEqualTo(LIMIT + 1);
    }

    /** Content-Length가 상한을 넘으면 본문 stream을 열지 않고 거부한다. */
    @Test
    void rejectsDeclaredLengthOverLimitWithoutReading() throws Exception {
        TrackedRequest request = new TrackedRequest("POST", "/api/auth/anonymous", jsonOfBytes(LIMIT + 1), LIMIT + 1);
        Exchange exchange = send(request);

        assertThat(exchange.response().getStatus()).isEqualTo(413);
        assertThat(exchange.forwarded()).isNull();
        assertThat(request.streamOpened).isZero();
        assertThat(request.bytesRead()).isZero();
    }

    /** 길이 헤더가 없거나 실제보다 작게 와도 상한+1바이트까지만 읽는다. */
    @Test
    void readsAtMostLimitPlusOneRegardlessOfLengthHeader() throws Exception {
        byte[] huge = new byte[1_000_000];
        for (long contentLength : new long[]{-1, 100}) {
            TrackedRequest request = new TrackedRequest("POST", "/api/auth/refresh", huge, contentLength);
            Exchange exchange = send(request);

            assertThat(exchange.response().getStatus()).isEqualTo(413);
            assertThat(exchange.forwarded()).isNull();
            assertThat(request.bytesRead()).isEqualTo(LIMIT + 1);
        }
    }

    /** 정확한 세 인증 POST가 아니면 본문을 읽지 않고 원래 요청을 그대로 넘긴다. */
    @ParameterizedTest
    @CsvSource({
            "POST, /api/auth/anonymous/",
            "POST, /api/auth/anonymous/extra",
            "POST, /api/auth/anonymousx",
            "POST, /api/auth",
            "POST, /api/auth/logout/all",
            "POST, /API/auth/refresh",
            "POST, /api/members/me",
            "GET, /api/auth/anonymous",
            "PUT, /api/auth/refresh",
            "PATCH, /api/auth/logout",
            "DELETE, /api/auth/logout"
    })
    void skipsOtherPathsAndMethods(String method, String path) throws Exception {
        TrackedRequest request = new TrackedRequest(method, path, jsonOfBytes(LIMIT + 1), -1);
        Exchange exchange = send(request);

        assertThat(exchange.response().getStatus()).isEqualTo(200);
        assertThat(exchange.forwarded()).isSameAs(request);
        assertThat(request.streamOpened).isZero();
    }

    /** 상한 이하의 빈 본문·잘못된 JSON은 파싱 오류로 바꾸지 않고 그대로 넘긴다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "{not-json", "null", "[1,2]"})
    void passesEmptyOrMalformedBodyWithinLimit(String text) throws Exception {
        byte[] body = text.getBytes(UTF_8);
        Exchange exchange = send(new TrackedRequest("POST", "/api/auth/anonymous", body, -1));

        assertThat(exchange.response().getStatus()).isEqualTo(200);
        assertThat(exchange.forwarded().getInputStream().readAllBytes()).isEqualTo(body);
    }

    /** 다시 제공하는 본문은 Servlet 계약대로 stream과 reader 중 하나로만 읽는다. */
    @Test
    void forwardedRequestFollowsServletReadContract() throws Exception {
        String json = "{\"code\":\"가나다\"}";

        HttpServletRequest streamRequest = forwarded(json);
        ServletInputStream stream = streamRequest.getInputStream();
        assertThat(streamRequest.getInputStream()).isSameAs(stream);
        assertThat(stream.readAllBytes()).isEqualTo(json.getBytes(UTF_8));
        assertThat(stream.isFinished()).isTrue();
        assertThatIllegalStateException().isThrownBy(streamRequest::getReader);

        HttpServletRequest readerRequest = forwarded(json);
        assertThat(readerRequest.getReader()).isSameAs(readerRequest.getReader());
        assertThat(readerRequest.getReader().readLine()).isEqualTo(json);
        assertThatIllegalStateException().isThrownBy(readerRequest::getInputStream);
    }

    /** 초과 응답은 기존 오류 JSON과 캐시 금지 헤더만 담고 요청 원문을 응답·로그에 남기지 않는다. */
    @Test
    void tooLargeResponseUsesErrorContractWithoutInput(CapturedOutput output) throws Exception {
        MockHttpServletResponse response =
                send(new TrackedRequest("POST", "/api/auth/logout", jsonOfBytes(LIMIT + 1), -1)).response();

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(jsonFields(response.getContentAsByteArray())).isEqualTo(TOO_LARGE_BODY);
        assertThat(response.getContentAsString(UTF_8)).doesNotContain(SECRET_CODE);
        assertThat(output).doesNotContain(SECRET_CODE);
    }

    /**
     * code와 큰 추가 필드를 담고 UTF-8로 정확히 {@code size}바이트인 JSON. 한글(3바이트)로 채워 문자 수가 바이트 수보다 적다.
     */
    static byte[] jsonOfBytes(int size) {
        String head = "{\"code\":\"" + SECRET_CODE + "\",\"extra\":\"";
        String tail = "\"}";
        int fill = size - head.length() - tail.length();
        String json = head + "가".repeat(fill / 3) + "a".repeat(fill % 3) + tail;
        byte[] bytes = json.getBytes(UTF_8);
        assertThat(bytes).hasSize(size);
        assertThat(json.length()).isLessThan(size);
        return bytes;
    }

    static Map<?, ?> jsonFields(byte[] json) {
        return JSON.readValue(json, Map.class);
    }

    private HttpServletRequest forwarded(String json) throws Exception {
        return send(new TrackedRequest("POST", "/api/auth/anonymous", json.getBytes(UTF_8), -1)).forwarded();
    }

    private Exchange send(TrackedRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Exchange(response, (HttpServletRequest) chain.getRequest());
    }

    /** 필터 응답과 다음 단계로 넘어간 요청(호출되지 않았으면 null). */
    private record Exchange(MockHttpServletResponse response, HttpServletRequest forwarded) {
    }

    /** Content-Length 값을 지정하고 원래 본문 stream의 사용량을 기록하는 요청. */
    private static final class TrackedRequest extends MockHttpServletRequest {

        private final ByteArrayInputStream source;
        private final int size;
        private final long contentLength;
        private int streamOpened;

        TrackedRequest(String method, String path, byte[] body, long contentLength) {
            super(method, path);
            this.source = new ByteArrayInputStream(body);
            this.size = body.length;
            this.contentLength = contentLength;
        }

        int bytesRead() {
            return size - source.available();
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            streamOpened++;
            return new DelegatingServletInputStream(source);
        }
    }
}
