package com.gyejoldanji.domain.auth.toss;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.config.properties.TossProperties;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 실제 클라이언트를 로컬 HTTPS(mTLS) fixture에 붙여 교환·오류 분류·timeout·요청 횟수·TLS 검증을 확인한다.
 *
 * <p>fixture는 client 인증서를 요구한다. 인증서는 실행 중 keytool로 임시 폴더에 만든다. 실토스 연동 검증이 아니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class TossAnonymousAuthClientTest {

    private static final String PASSWORD = "test-password";
    private static final String PATH = TossAnonymousAuthClient.EXCHANGE_PATH;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();

    @TempDir
    static Path dir;

    private static Path clientKeystore;
    private static Path clientTrustStore;
    private static SSLContext serverContext;
    private static HttpsServer server;
    private static ExecutorService serverThreads;
    private static volatile HttpHandler behavior;
    private static volatile String lastContentType;
    private static volatile byte[] lastBody;

    @BeforeAll
    static void startFixture() throws Exception {
        Path serverKeystore = keytool("server", "CN=localhost", "dns:localhost");
        clientKeystore = keytool("client", "CN=test-mini-app", null);
        clientTrustStore = trustStoreOf(serverKeystore, "server");

        serverContext = SSLContext.getInstance("TLS");
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(load(serverKeystore), PASSWORD.toCharArray());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(load(trustStoreOf(clientKeystore, "client")));
        serverContext.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true); // mTLS: client 인증서가 없으면 연결되지 않는다
                params.setSSLParameters(ssl);
            }
        });
        server.createContext("/", exchange -> {
            REQUESTS.computeIfAbsent(exchange.getRequestURI().getPath(), path -> new AtomicInteger()).incrementAndGet();
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            lastBody = exchange.getRequestBody().readAllBytes();
            behavior.handle(exchange);
        });
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterAll
    static void stopFixture() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        behavior = respond(500, "");
    }

    /** code를 원문 그대로 JSON 한 번에 보내고, SUCCESS의 anonKey를 변형 없이 돌려준다. */
    @Test
    void exchangesCodeOnceAndReturnsAnonKeyUnchanged(CapturedOutput output) throws Exception {
        String code = " SECRET-CODE-가😀 ";
        String anonKey = " SECRET-ANON-Ａé😀 ";
        behavior = respond(200, "{\"resultType\":\"SUCCESS\",\"success\":{\"anonKey\":" + quote(anonKey) + "},\"extra\":1}");

        assertThat(client().exchangeAnonymousCode(code)).isEqualTo(anonKey);

        assertThat(requests(PATH)).isOne();
        assertThat(lastContentType).isEqualTo("application/json");
        assertThat(JSON.readTree(lastBody).path("code").stringValue()).isEqualTo(code);
        assertThat(JSON.readTree(lastBody).propertyNames()).containsExactly("code");
        assertThat(output.getAll()).doesNotContain("SECRET-CODE", "SECRET-ANON");
    }

    /** 255 code point까지의 anonKey는 그대로 받는다. */
    @Test
    void acceptsAnonKeyUpTo255CodePoints() {
        String anonKey = "😀".repeat(255);
        behavior = respond(200, "{\"resultType\":\"SUCCESS\",\"success\":{\"anonKey\":\"" + anonKey + "\"}}");

        assertThat(client().exchangeAnonymousCode("code")).isEqualTo(anonKey);
    }

    /** HTTP 상태와 본문을 함께 보고 분류하며, 외부 응답 원문을 예외·로그에 남기지 않는다. 어떤 경우에도 한 번만 보낸다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void classifiesResponses(String label, int status, String body, ErrorCode expected, CapturedOutput output) {
        behavior = respond(status, body);

        assertThatThrownBy(() -> client().exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(expected);
                    assertThat(e.getMessage()).isEqualTo(expected.getMessage());
                });
        assertThat(requests(PATH)).isOne();
        assertThat(output.getAll()).doesNotContain("SECRET-REASON");
    }

    static Stream<Arguments> responses() {
        String fail = "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":%s,\"reason\":\"SECRET-REASON\"}}";
        String success = "{\"resultType\":\"SUCCESS\",\"success\":%s}";
        return Stream.of(
                arguments("200 FAIL 4011", 200, fail.formatted("\"4011\""), ErrorCode.AUTH_CODE_REJECTED),
                arguments("400 FAIL 4011", 400, fail.formatted("\"4011\""), ErrorCode.AUTH_CODE_REJECTED),
                arguments("200 FAIL 4095", 200, fail.formatted("\"4095\""), ErrorCode.AUTH_RATE_LIMITED),
                arguments("HTTP 429", 429, "", ErrorCode.AUTH_RATE_LIMITED),
                arguments("HTTP_TIMEOUT", 200, "{\"resultType\":\"HTTP_TIMEOUT\"}", ErrorCode.AUTH_PROVIDER_TIMEOUT),
                arguments("INTERNAL_ERROR", 200, "{\"resultType\":\"INTERNAL_ERROR\"}", ErrorCode.AUTH_PROVIDER_UNAVAILABLE),
                arguments("NETWORK_ERROR", 200, "{\"resultType\":\"NETWORK_ERROR\"}", ErrorCode.AUTH_PROVIDER_UNAVAILABLE),
                arguments("EXECUTION_FAIL", 200, "{\"resultType\":\"EXECUTION_FAIL\"}", ErrorCode.AUTH_PROVIDER_UNAVAILABLE),
                arguments("INTERRUPTED", 200, "{\"resultType\":\"INTERRUPTED\"}", ErrorCode.AUTH_PROVIDER_UNAVAILABLE),
                arguments("HTTP 500", 500, "<html>SECRET-REASON</html>", ErrorCode.AUTH_PROVIDER_UNAVAILABLE),
                arguments("HTTP 503 FAIL 미정의", 503, fail.formatted("\"9999\""), ErrorCode.AUTH_PROVIDER_UNAVAILABLE),
                arguments("SUCCESS anonKey 누락", 200, success.formatted("{}"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS anonKey null", 200, success.formatted("{\"anonKey\":null}"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS anonKey 숫자", 200, success.formatted("{\"anonKey\":123}"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS anonKey 객체", 200, success.formatted("{\"anonKey\":{}}"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS anonKey 빈 값", 200, success.formatted("{\"anonKey\":\"\"}"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS anonKey 공백", 200, success.formatted("{\"anonKey\":\"  \"}"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS anonKey 256자", 200, success.formatted("{\"anonKey\":\"" + "😀".repeat(256) + "\"}"),
                        ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS anonKey 짝 없는 surrogate", 200, success.formatted("{\"anonKey\":\"a\\uD800\"}"),
                        ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("SUCCESS success null", 200, success.formatted("null"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("201 SUCCESS", 201, success.formatted("{\"anonKey\":\"k\"}"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("미정의 resultType", 200, "{\"resultType\":\"PENDING\"}", ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("FAIL 미정의 코드", 200, fail.formatted("\"9999\""), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("FAIL errorCode 숫자", 200, fail.formatted("4011"), ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("FAIL errorType만 4095", 200,
                        "{\"resultType\":\"FAIL\",\"error\":{\"errorType\":4095}}", ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("잘못된 JSON", 200, "{\"resultType\":", ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("빈 본문", 200, "", ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("HTTP 404", 404, "<html>SECRET-REASON</html>", ErrorCode.AUTH_PROVIDER_BAD_RESPONSE),
                arguments("HTTP 400 FAIL 코드 없음", 400, "{\"resultType\":\"FAIL\"}", ErrorCode.AUTH_PROVIDER_BAD_RESPONSE));
    }

    /** error.data.retryAfterSeconds가 양의 정수일 때만 쓴다. 위치가 다르거나 보정이 필요한 값은 버린다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            '{"errorCode":"4095","data":{"retryAfterSeconds":30}}'          | 30
            '{"errorCode":"4095","data":{"retryAfterSeconds":2147483647}}'  | 2147483647
            '{"errorCode":"4095","data":{"retryAfterSeconds":2147483648}}'  |
            '{"errorCode":"4095","data":{"retryAfterSeconds":0}}'           |
            '{"errorCode":"4095","data":{"retryAfterSeconds":-5}}'          |
            '{"errorCode":"4095","data":{"retryAfterSeconds":1.5}}'         |
            '{"errorCode":"4095","data":{"retryAfterSeconds":30.0}}'        |
            '{"errorCode":"4095","data":{"retryAfterSeconds":"30"}}'        |
            '{"errorCode":"4095","data":{}}'                                |
            '{"errorCode":"4095"}'                                          |
            '{"errorCode":"4095","retryAfterSeconds":30}'                   |
            """)
    void keepsOnlyPositiveIntegerRetryAfter(String error, Integer expected) {
        behavior = respond(200, "{\"resultType\":\"FAIL\",\"retryAfterSeconds\":30,\"error\":" + error + "}");

        assertThatThrownBy(() -> client().exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(TossAnonymousAuthClient.RateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(expected));
    }

    /** redirect를 따르지 않는다. */
    @Test
    void doesNotFollowRedirects() {
        behavior = exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl() + "/redirected");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        };

        assertThatThrownBy(() -> client().exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_BAD_RESPONSE));
        assertThat(requests(PATH)).isOne();
        assertThat(requests("/redirected")).isZero();
    }

    /** 응답 헤더 지연과 본문 지연 모두 요청 timeout(1초)에서 끝나며 다시 보내지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timesOutOnSlowHeadersOrBody(boolean headersFirst) {
        byte[] body = "{\"resultType\":\"SUCCESS\",\"success\":{\"anonKey\":\"k\"}}".getBytes(UTF_8);
        behavior = exchange -> {
            try {
                if (headersFirst) {
                    exchange.sendResponseHeaders(200, body.length);
                    OutputStream out = exchange.getResponseBody();
                    out.write(body, 0, 5);
                    out.flush();
                    Thread.sleep(3_000);
                    out.write(body, 5, body.length - 5);
                } else {
                    Thread.sleep(3_000);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊으면 쓰기가 실패한다.
            } finally {
                exchange.close();
            }
        };

        long start = System.nanoTime();
        assertThatThrownBy(() -> client().exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_TIMEOUT));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_500);
        assertThat(requests(PATH)).isOne();
    }

    /** 응답 없이 연결이 끊기면 AUTH_007이며 다시 보내지 않는다. */
    @Test
    void doesNotRetryWhenConnectionDrops() {
        behavior = exchange -> exchange.close();

        assertThatThrownBy(() -> client().exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
        assertThat(requests(PATH)).isOne();
    }

    /**
     * h2를 제안하는 서버가 요청 stream을 REFUSED_STREAM으로 거절하면 JDK 기본(HTTP/2) 클라이언트는 같은 POST를 다시 보낸다.
     * 이 클라이언트는 HTTP/1.1로만 통신하므로 서버가 받는 요청은 정확히 1회다.
     */
    @Test
    void sendsRequestOnceEvenIfServerOffersHttp2() throws Exception {
        try (RefusingHttp2Server h2Server = new RefusingHttp2Server(serverContext)) {
            assertThatThrownBy(() -> client("https://localhost:" + h2Server.port(), clientTrustStore)
                    .exchangeAnonymousCode("code"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));

            assertThat(h2Server.http2Requests).as("HTTP/2 요청").hasValue(0);
            assertThat(h2Server.http1Requests).as("HTTP/1.1 요청").hasValue(1);
        }
    }

    /** 연결 거부는 AUTH_007이다. */
    @Test
    void mapsConnectionRefusedToUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> client("https://localhost:" + closedPort, clientTrustStore).exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
    }

    /**
     * 응답하지 않는 주소로의 연결은 연결 timeout(1초)에서 AUTH_012로 끝난다. 네트워크가 그 주소를 즉시 거부하면
     * 연결 timeout을 재현할 수 없으므로 건너뛴다(실패로 바꾸지 않는다).
     */
    @Test
    void mapsConnectTimeoutToTimeout() {
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> client("https://10.255.255.1", clientTrustStore)
                .exchangeAnonymousCode("code"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assumeTrue(elapsedMillis >= 900, "이 네트워크는 응답 없는 주소를 즉시 거부해 연결 timeout을 재현할 수 없다");
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_TIMEOUT));
        assertThat(elapsedMillis).isLessThan(2_500);
    }

    /** JVM 기본 trust store가 믿지 않는 서버와 hostname이 다른 인증서는 연결 전에 거부한다(trust-all 없음). */
    @Test
    void keepsServerTrustAndHostnameVerification() {
        behavior = respond(200, "{\"resultType\":\"SUCCESS\",\"success\":{\"anonKey\":\"k\"}}");

        assertThatThrownBy(() -> client(baseUrl(), null).exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
        assertThatThrownBy(() -> client("https://127.0.0.1:" + server.getAddress().getPort(), clientTrustStore)
                .exchangeAnonymousCode("code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
        assertThat(requests(PATH)).isZero();
    }

    /** 잘못된 keystore는 Bean 생성(기동) 시 실패한다. 신뢰 인증서만 있거나 client 키가 둘 이상이면 준비되지 않은 것으로 본다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("badKeystores")
    void failsToStartWithBadKeystore(String label, Path keystore, String password) {
        TossProperties properties = properties(baseUrl());
        properties.getMtls().setKeystorePath(keystore.toString());
        properties.getMtls().setKeystorePassword(password);

        assertThatThrownBy(() -> new TossAnonymousAuthClient(properties, JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("토스 mTLS keystore")
                .hasMessageNotContaining(password);
    }

    static Stream<Arguments> badKeystores() throws Exception {
        Path empty = Files.createFile(dir.resolve("empty.p12"));
        Path corrupted = Files.write(dir.resolve("corrupted.p12"), "not a keystore".getBytes(UTF_8));
        KeyStore twoKeys = load(clientKeystore);
        KeyStore serverStore = load(dir.resolve("server.p12"));
        Key serverKey = serverStore.getKey("server", PASSWORD.toCharArray());
        twoKeys.setKeyEntry("second", serverKey, PASSWORD.toCharArray(), serverStore.getCertificateChain("server"));
        return Stream.of(
                arguments("파일 없음", dir.resolve("missing.p12"), PASSWORD),
                arguments("빈 파일", empty, PASSWORD),
                arguments("손상된 파일", corrupted, PASSWORD),
                arguments("잘못된 암호", clientKeystore, "wrong-password"),
                arguments("신뢰 인증서만 있음", clientTrustStore, PASSWORD),
                arguments("client 키 두 개", store(twoKeys, "two-keys.p12"), PASSWORD));
    }

    private static TossAnonymousAuthClient client() {
        return client(baseUrl(), clientTrustStore);
    }

    /**
     * 실제 생성자로 클라이언트를 만든다. fixture 서버 인증서를 믿게 하려고 생성하는 동안만 JVM 표준 trust store 속성을
     * 지정하고 되돌린다(코드에 신뢰 우회 경로를 두지 않는다). trustStore가 null이면 JVM 기본 trust store를 쓴다.
     */
    private static TossAnonymousAuthClient client(String baseUrl, Path trustStore) {
        Map<String, String> previous = new HashMap<>();
        Map<String, String> trust = trustStore == null ? Map.of() : Map.of(
                "javax.net.ssl.trustStore", trustStore.toString(),
                "javax.net.ssl.trustStorePassword", PASSWORD,
                "javax.net.ssl.trustStoreType", "PKCS12");
        trust.keySet().forEach(name -> previous.put(name, System.getProperty(name)));
        try {
            trust.forEach(System::setProperty);
            return new TossAnonymousAuthClient(properties(baseUrl), JSON);
        } finally {
            previous.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
        }
    }

    private static TossProperties properties(String baseUrl) {
        TossProperties properties = new TossProperties();
        properties.setApiBaseUrl(baseUrl);
        properties.setAppName("test-app");
        properties.setIdentityEnvironment(TossProperties.IdentityEnvironment.DEV);
        properties.getMtls().setKeystorePath(clientKeystore.toString());
        properties.getMtls().setKeystorePassword(PASSWORD);
        properties.setConnectTimeoutSeconds(1);
        properties.setRequestTimeoutSeconds(1);
        return properties;
    }

    private static String baseUrl() {
        return "https://localhost:" + server.getAddress().getPort();
    }

    private static int requests(String path) {
        AtomicInteger count = REQUESTS.get(path);
        return count == null ? 0 : count.get();
    }

    private static HttpHandler respond(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    private static String quote(String value) {
        return JSON.writeValueAsString(value);
    }

    /** JDK keytool로 RSA 2048 키와 자체 서명 인증서를 가진 PKCS#12를 만든다. */
    private static Path keytool(String alias, String dname, String san) throws Exception {
        Path keystore = dir.resolve(alias + ".p12");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", dname, "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt"));
        if (san != null) {
            command.addAll(List.of("-ext", "SAN=" + san));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return keystore;
    }

    /** keystore의 인증서 하나만 신뢰 인증서 항목으로 담은 PKCS#12를 만든다. */
    private static Path trustStoreOf(Path keystore, String alias) throws Exception {
        Certificate certificate = load(keystore).getCertificate(alias);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry(alias, certificate);
        return store(trust, alias + "-trust.p12");
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static Path store(KeyStore keyStore, String name) throws Exception {
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path)) {
            keyStore.store(out, PASSWORD.toCharArray());
        }
        return path;
    }

    /**
     * ALPN으로 h2와 http/1.1을 모두 제안하는 테스트 TLS 서버(client 인증서 필수). h2로 들어온 요청 stream은
     * RST_STREAM(REFUSED_STREAM)으로 거절하고, HTTP/1.1 요청은 503으로 답한다. 받은 완전한 요청 수를 프로토콜별로 센다.
     */
    private static final class RefusingHttp2Server implements AutoCloseable {

        private final AtomicInteger http1Requests = new AtomicInteger();
        private final AtomicInteger http2Requests = new AtomicInteger();
        private final SSLServerSocket socket;
        private final ExecutorService threads = Executors.newCachedThreadPool();

        RefusingHttp2Server(SSLContext context) throws IOException {
            socket = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 10, InetAddress.getLoopbackAddress());
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[] {"h2", "http/1.1"});
            parameters.setNeedClientAuth(true);
            socket.setSSLParameters(parameters);
            threads.submit(this::acceptLoop);
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
            threads.shutdownNow();
        }

        private void acceptLoop() {
            while (!socket.isClosed()) {
                try {
                    SSLSocket connection = (SSLSocket) socket.accept();
                    threads.submit(() -> serve(connection));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(SSLSocket connection) {
            try (connection) {
                connection.setSoTimeout(3_000);
                connection.startHandshake();
                DataInputStream in = new DataInputStream(connection.getInputStream());
                OutputStream out = connection.getOutputStream();
                if ("h2".equals(connection.getApplicationProtocol())) {
                    refuseHttp2Streams(in, new DataOutputStream(out));
                } else {
                    readHttp1Request(in);
                    http1Requests.incrementAndGet();
                    out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(US_ASCII));
                    out.flush();
                }
            } catch (IOException ignored) {
                // 클라이언트가 연결을 끊으면 끝낸다.
            }
        }

        /** 연결 preface 뒤 SETTINGS를 주고받고, 본문까지 끝난 요청 stream마다 REFUSED_STREAM(0x7)으로 거절한다. */
        private void refuseHttp2Streams(DataInputStream in, DataOutputStream out) throws IOException {
            in.readNBytes(24);
            frame(out, 0x4, 0, 0, new byte[0]);
            while (true) {
                int length = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                int type = in.readUnsignedByte();
                int flags = in.readUnsignedByte();
                int stream = in.readInt() & 0x7fffffff;
                in.readNBytes(length);
                if (type == 0x4 && (flags & 0x1) == 0) {
                    frame(out, 0x4, 0x1, 0, new byte[0]);
                } else if (stream > 0 && (type == 0x0 || type == 0x1) && (flags & 0x1) != 0) {
                    http2Requests.incrementAndGet();
                    frame(out, 0x3, 0, stream, new byte[] {0, 0, 0, 0x7});
                }
            }
        }

        private static void frame(DataOutputStream out, int type, int flags, int stream, byte[] payload)
                throws IOException {
            out.writeByte(payload.length >> 16);
            out.writeByte(payload.length >> 8);
            out.writeByte(payload.length);
            out.writeByte(type);
            out.writeByte(flags);
            out.writeInt(stream);
            out.write(payload);
            out.flush();
        }

        /** 헤더와 Content-Length만큼의 본문을 끝까지 읽는다. */
        private static void readHttp1Request(DataInputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
                head.append((char) in.readUnsignedByte());
            }
            Matcher length = Pattern.compile("(?im)^content-length:\\s*(\\d+)").matcher(head);
            in.readNBytes(length.find() ? Integer.parseInt(length.group(1)) : 0);
        }
    }
}
