package com.gyejoldanji.domain.auth.toss;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.config.properties.TossProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * 토스 익명 인증 code를 mTLS로 한 번 교환해 anonKey를 받는다.
 *
 * <p>Bean 생성 시 PKCS#12에서 미니앱 client 개인키·인증서 체인을 읽고, 서버 신뢰는 JVM 기본 trust store와 hostname 검증을
 * 그대로 쓴다. redirect를 따르지 않고 같은 code를 다시 보내지 않는다. 결과는 HTTP 상태와 본문을 함께 보고 분류하며, 외부 응답·code·
 * anonKey 원문은 응답·로그에 남기지 않는다. 테스트는 이 Bean을 대체하므로 인증서 파일을 열지 않는다.
 */
@Slf4j
@Component
public class TossAnonymousAuthClient {

    static final String EXCHANGE_PATH = "/api-partner/v1/apps-in-toss/users/anon-key/exchange";
    private static final int MAX_ANON_KEY_CODE_POINTS = 255;
    private static final Set<String> UNAVAILABLE_RESULTS =
            Set.of("INTERNAL_ERROR", "NETWORK_ERROR", "EXECUTION_FAIL", "INTERRUPTED");

    private final HttpClient httpClient;
    private final URI exchangeUri;
    private final Duration requestTimeout;
    private final JsonMapper jsonMapper;

    public TossAnonymousAuthClient(TossProperties properties, JsonMapper jsonMapper) {
        this.httpClient = HttpClient.newBuilder()
                // HTTP/1.1 고정: 기본 HTTP/2는 서버의 REFUSED_STREAM에 같은 POST를 자동 재전송한다.
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(clientSslContext(properties.getMtls()))
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.exchangeUri = URI.create(properties.getApiBaseUrl()).resolve(EXCHANGE_PATH);
        this.requestTimeout = Duration.ofSeconds(properties.getRequestTimeoutSeconds());
        this.jsonMapper = jsonMapper;
    }

    /** 토스 요청 한도 초과(AUTH_014). 양의 정수로 확인된 재시도 대기 초만 담는다. */
    @Getter
    public static class RateLimitedException extends BusinessException {

        private final Integer retryAfterSeconds;

        public RateLimitedException(Integer retryAfterSeconds) {
            super(ErrorCode.AUTH_RATE_LIMITED);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /**
     * code를 원문 그대로 한 번 보내 anonKey를 받는다.
     *
     * @throws BusinessException AUTH_004·AUTH_007·AUTH_010·AUTH_012, 요청 한도 초과는 {@link RateLimitedException}
     */
    public String exchangeAnonymousCode(String code) {
        HttpRequest request = HttpRequest.newBuilder(exchangeUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonMapper.writeValueAsBytes(Map.of("code", code))))
                .build();
        HttpResponse<byte[]> response = send(request);
        return readAnonKey(response.statusCode(), response.body());
    }

    /**
     * 요청 전송부터 응답 본문 수신 완료까지를 requestTimeout으로 제한한다. 연결 timeout은 HttpClient에 따로 적용돼 있다.
     * 이 클라이언트는 HTTP/1.1로 고정돼 있어 JDK가 POST(비멱등)를 다시 보내지 않는다. 기본 HTTP/2였다면
     * REFUSED_STREAM에서 자동 재전송한다. 애플리케이션도 재시도하지 않는다.
     */
    // ponytail: 응답 본문 크기는 제한하지 않는다(mTLS로 고정된 토스 서버, 시간만 제한). 필요해지면 크기 제한 BodySubscriber.
    private HttpResponse<byte[]> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw failure(ErrorCode.AUTH_PROVIDER_TIMEOUT, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw failure(cause instanceof HttpTimeoutException
                    ? ErrorCode.AUTH_PROVIDER_TIMEOUT : ErrorCode.AUTH_PROVIDER_UNAVAILABLE, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw failure(ErrorCode.AUTH_PROVIDER_UNAVAILABLE, e);
        }
    }

    /**
     * 알려진 오류 코드(4011·4095) → timeout → 네트워크·서버 장애 → 프로토콜 이상 순서로 판정한다.
     * HTTP 200만으로 성공으로 보지 않고, 필수 필드는 JSON 문자열일 때만 받는다(숫자 자동 변환 없음).
     */
    private String readAnonKey(int status, byte[] body) {
        JsonNode root = readJson(body);
        JsonNode error = root.path("error");
        String errorCode = stringOrNull(error.path("errorCode"));
        if ("4011".equals(errorCode)) {
            throw failure(ErrorCode.AUTH_CODE_REJECTED, status);
        }
        if ("4095".equals(errorCode) || status == 429) {
            log.warn("토스 익명 키 교환 실패: result={}, httpStatus={}", ErrorCode.AUTH_RATE_LIMITED.getCode(), status);
            throw new RateLimitedException(positiveSeconds(error.path("data").path("retryAfterSeconds")));
        }
        String resultType = stringOrNull(root.path("resultType"));
        if ("HTTP_TIMEOUT".equals(resultType)) {
            throw failure(ErrorCode.AUTH_PROVIDER_TIMEOUT, status);
        }
        if (status >= 500 || (resultType != null && UNAVAILABLE_RESULTS.contains(resultType))) {
            throw failure(ErrorCode.AUTH_PROVIDER_UNAVAILABLE, status);
        }
        if (status == 200 && "SUCCESS".equals(resultType)) {
            String anonKey = stringOrNull(root.path("success").path("anonKey"));
            if (isUsableAnonKey(anonKey)) {
                return anonKey;
            }
        }
        throw failure(ErrorCode.AUTH_PROVIDER_BAD_RESPONSE, status);
    }

    /** 해석할 수 없는 본문은 필드가 없는 것으로 본다. */
    private JsonNode readJson(byte[] body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            return root == null ? MissingNode.getInstance() : root;
        } catch (JacksonException e) {
            return MissingNode.getInstance();
        }
    }

    private static String stringOrNull(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }

    /** 양의 int 정수만 받는다. 누락·0·음수·소수·문자열·범위 초과는 보정하지 않고 버린다. */
    private static Integer positiveSeconds(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0 ? node.intValue() : null;
    }

    /** 비어 있지 않고 255 code point 이하이며 짝 없는 surrogate가 없는 값만 쓴다. 원문은 변형하지 않는다. */
    private static boolean isUsableAnonKey(String anonKey) {
        return anonKey != null
                && !anonKey.isBlank()
                && anonKey.codePointCount(0, anonKey.length()) <= MAX_ANON_KEY_CODE_POINTS
                && anonKey.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }

    /** 분류 결과와 HTTP 상태만 기록한다. 외부 응답·code·anonKey 원문은 남기지 않는다. */
    private static BusinessException failure(ErrorCode errorCode, int status) {
        log.warn("토스 익명 키 교환 실패: result={}, httpStatus={}", errorCode.getCode(), status);
        return new BusinessException(errorCode);
    }

    /** 분류 결과와 원인 종류만 기록한다. 예외 메시지는 남기지 않는다. */
    private static BusinessException failure(ErrorCode errorCode, Throwable cause) {
        log.warn("토스 익명 키 교환 실패: result={}, cause={}", errorCode.getCode(), cause.getClass().getSimpleName());
        return new BusinessException(errorCode);
    }

    /** PKCS#12의 client 개인키·인증서 체인으로 SSLContext를 만들고 서버 신뢰는 JVM 기본값을 쓴다. */
    private static SSLContext clientSslContext(TossProperties.Mtls mtls) {
        String path = mtls.getKeystorePath();
        char[] password = mtls.getKeystorePassword().toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(path))) {
                keyStore.load(in, password);
            }
            if (countClientKeys(keyStore, password) != 1) {
                throw new IllegalStateException(
                        "토스 mTLS keystore에는 client 개인키와 인증서 체인이 정확히 하나 있어야 합니다: " + path);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, password);
            SSLContext context = SSLContext.getInstance("TLS");
            // trust manager null: JVM 기본 trust store와 hostname 검증을 그대로 쓴다(trust-all 없음).
            context.init(keyManagers.getKeyManagers(), null, null);
            return context;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            // 원인 메시지 대신 종류만 남긴다.
            throw new IllegalStateException(
                    "토스 mTLS keystore를 불러올 수 없습니다: " + path + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 인증서 체인이 있는 개인키 항목 수. 신뢰 인증서 항목은 세지 않는다. */
    private static int countClientKeys(KeyStore keyStore, char[] password) throws GeneralSecurityException {
        int count = 0;
        for (String alias : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(alias)
                    && keyStore.getEntry(alias, new KeyStore.PasswordProtection(password))
                            instanceof KeyStore.PrivateKeyEntry entry
                    && entry.getCertificateChain().length > 0) {
                count++;
            }
        }
        return count;
    }
}
