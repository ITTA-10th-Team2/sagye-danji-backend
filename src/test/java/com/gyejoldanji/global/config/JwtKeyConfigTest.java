package com.gyejoldanji.global.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.gyejoldanji.global.config.properties.AuthProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * AuthProperties 검증과 JWT 키 로더를 DB 없이 설정 컨텍스트로 확인한다.
 *
 * <p>키는 테스트 실행 중 실제로 만들어 JUnit 임시 폴더에만 쓴다.
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtKeyConfigTest {

    @TempDir
    static Path dir;

    private static KeyPair rsa;
    private static Path privatePem;
    private static Path publicPem;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AuthProperties.class, ClockConfig.class, JwtKeyConfig.class);

    @BeforeAll
    static void createKeys() throws Exception {
        rsa = TestJwtKeys.generate("RSA", 2048);
        privatePem = TestJwtKeys.writePem(dir, "PRIVATE KEY", rsa.getPrivate().getEncoded());
        publicPem = TestJwtKeys.writePem(dir, "PUBLIC KEY", rsa.getPublic().getEncoded());
    }

    /** 필수값과 같은 RSA 2048 키쌍이 있으면 기동하고 TTL 기본값은 900초·14일이다. */
    @Test
    void startsWithValidKeyPairAndDefaults() {
        runner.withPropertyValues(TestJwtKeys.properties(privatePem, publicPem)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KeyPair.class).getPublic()).isEqualTo(rsa.getPublic());
            assertThat(context.getBean(KeyPair.class).getPrivate()).isEqualTo(rsa.getPrivate());
            AuthProperties properties = context.getBean(AuthProperties.class);
            assertThat(properties.getAccessTtlSeconds()).isEqualTo(900);
            assertThat(properties.getSessionTtlSeconds()).isEqualTo(1_209_600);
        });
    }

    /** jwt 설정이 하나도 없으면 기동하지 않는다. */
    @Test
    void failsWithoutJwtSettings() {
        runner.run(context -> assertInvalidSetting(context, "issuer"));
    }

    /** 필수 jwt 값이 공백이면 기동하지 않는다. */
    @ParameterizedTest
    @CsvSource({
            "issuer, issuer",
            "audience, audience",
            "key-id, keyId",
            "private-key-path, privateKeyPath",
            "public-key-path, publicKeyPath"})
    void failsWhenRequiredJwtValueIsBlank(String property, String field) {
        runner.withPropertyValues(TestJwtKeys.properties(privatePem, publicPem))
                .withPropertyValues("auth.jwt." + property + "= ")
                .run(context -> assertInvalidSetting(context, field));
    }

    /** 실제 application.yml placeholder로 바인딩해도 환경변수가 모두 있으면 기동하고 TTL은 yml 기본값을 쓴다. */
    @Test
    void startsFromApplicationYamlWithJwtEnvironmentVariables() {
        yamlRunner(jwtEnvironment()).run(context -> {
            assertThat(context).hasNotFailed();
            AuthProperties properties = context.getBean(AuthProperties.class);
            assertThat(properties.getJwt().getIssuer()).isEqualTo("test-issuer");
            assertThat(properties.getJwt().getAudience()).isEqualTo("test-audience");
            assertThat(properties.getJwt().getKeyId()).isEqualTo("test-key");
            assertThat(properties.getAccessTtlSeconds()).isEqualTo(900);
            assertThat(properties.getSessionTtlSeconds()).isEqualTo(1_209_600);
        });
    }

    /** 키 파일이 정상이어도 application.yml의 필수 JWT 환경변수가 하나라도 없으면 기동하지 않는다. */
    @ParameterizedTest
    @CsvSource({
            "AUTH_JWT_ISSUER, issuer",
            "AUTH_JWT_AUDIENCE, audience",
            "AUTH_JWT_KEY_ID, keyId",
            "AUTH_JWT_PRIVATE_KEY_PATH, privateKeyPath",
            "AUTH_JWT_PUBLIC_KEY_PATH, publicKeyPath"})
    void failsWhenJwtEnvironmentVariableIsMissing(String variable, String field) {
        Map<String, Object> environment = jwtEnvironment();
        environment.remove(variable);
        yamlRunner(environment).run(context -> assertInvalidSetting(context, field));
    }

    /** Access TTL은 2~900초만 허용한다. */
    @ParameterizedTest
    @CsvSource({"1, false", "2, true", "900, true", "901, false"})
    void limitsAccessTtl(long seconds, boolean valid) {
        assertTtl(valid, "auth.access-ttl-seconds=" + seconds);
    }

    /** 세션 TTL은 Access TTL 이상, 1,209,600초 이하만 허용한다. */
    @ParameterizedTest
    @CsvSource({
            "900, 899, false",
            "900, 900, true",
            "2, 2, true",
            "900, 1209600, true",
            "900, 1209601, false"})
    void limitsSessionTtl(long accessSeconds, long sessionSeconds, boolean valid) {
        assertTtl(valid, "auth.access-ttl-seconds=" + accessSeconds, "auth.session-ttl-seconds=" + sessionSeconds);
    }

    /** 키 파일 문제는 모두 기동 실패이며, 오류 내용과 출력에 키 원문이 없다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("badKeyFiles")
    void failsOnBadKeyFiles(String label, Path privateKey, Path publicKey, CapturedOutput output) throws Exception {
        runner.withPropertyValues(TestJwtKeys.properties(privateKey, publicKey)).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(failure).rootCause().isInstanceOf(IllegalStateException.class).hasMessageStartingWith("JWT ");

            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            for (String line : keyBodyLines(privateKey, publicKey)) {
                assertThat(trace.toString()).doesNotContain(line);
                assertThat(output.getAll()).doesNotContain(line);
            }
        });
    }

    static Stream<Arguments> badKeyFiles() throws Exception {
        KeyPair other = TestJwtKeys.generate("RSA", 2048);
        KeyPair small = TestJwtKeys.generate("RSA", 1024);
        KeyPair ec = TestJwtKeys.generate("EC", 256);
        byte[] privateDer = rsa.getPrivate().getEncoded();
        byte[] publicDer = rsa.getPublic().getEncoded();
        Path empty = Files.createTempFile(dir, "empty-", ".pem");
        return Stream.of(
                arguments("개인키 파일 없음", dir.resolve("missing-private.pem"), publicPem),
                arguments("공개키 파일 없음", privatePem, dir.resolve("missing-public.pem")),
                arguments("빈 개인키 파일", empty, publicPem),
                arguments("빈 공개키 파일", privatePem, empty),
                arguments("손상된 개인키", TestJwtKeys.writePem(dir, "PRIVATE KEY", Arrays.copyOf(privateDer, 300)), publicPem),
                arguments("손상된 공개키", privatePem, TestJwtKeys.writePem(dir, "PUBLIC KEY", Arrays.copyOf(publicDer, 100))),
                arguments("PKCS#1 헤더 개인키", TestJwtKeys.writePem(dir, "RSA PRIVATE KEY", privateDer), publicPem),
                arguments("인증서 헤더 공개키", privatePem, TestJwtKeys.writePem(dir, "CERTIFICATE", publicDer)),
                arguments("개인키·공개키 경로 뒤바뀜", publicPem, privatePem),
                arguments("EC 개인키", TestJwtKeys.writePem(dir, "PRIVATE KEY", ec.getPrivate().getEncoded()), publicPem),
                arguments("EC 공개키", privatePem, TestJwtKeys.writePem(dir, "PUBLIC KEY", ec.getPublic().getEncoded())),
                arguments("RSA 1024 키쌍", TestJwtKeys.writePem(dir, "PRIVATE KEY", small.getPrivate().getEncoded()),
                        TestJwtKeys.writePem(dir, "PUBLIC KEY", small.getPublic().getEncoded())),
                arguments("다른 키쌍의 공개키", privatePem,
                        TestJwtKeys.writePem(dir, "PUBLIC KEY", other.getPublic().getEncoded())));
    }

    private void assertTtl(boolean valid, String... ttlProperties) {
        runner.withPropertyValues(TestJwtKeys.properties(privatePem, publicPem))
                .withPropertyValues(ttlProperties)
                .run(context -> {
                    if (valid) {
                        assertThat(context).hasNotFailed();
                    } else {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class);
                    }
                });
    }

    /** 정상 키 파일을 가리키는 필수 JWT 환경변수 값. */
    private static Map<String, Object> jwtEnvironment() {
        return new HashMap<>(Map.of(
                "AUTH_JWT_ISSUER", "test-issuer",
                "AUTH_JWT_AUDIENCE", "test-audience",
                "AUTH_JWT_KEY_ID", "test-key",
                "AUTH_JWT_PRIVATE_KEY_PATH", privatePem.toString(),
                "AUTH_JWT_PUBLIC_KEY_PATH", publicPem.toString()));
    }

    /** 실제 application.yml과 주어진 환경변수 값만으로 바인딩한다. */
    private ApplicationContextRunner yamlRunner(Map<String, Object> environment) {
        return runner.withInitializer(TestApplicationYaml.withEnvironment(environment));
    }

    private static void assertInvalidSetting(AssertableApplicationContext context, String field) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining(field);
    }

    /** 존재하는 키 파일의 Base64 본문 줄. 오류·출력에 한 줄이라도 있으면 키 원문이 노출된 것이다. */
    private static List<String> keyBodyLines(Path... files) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            if (Files.exists(file)) {
                Files.readAllLines(file).stream().filter(line -> !line.isBlank() && !line.startsWith("-----")).forEach(lines::add);
            }
        }
        return lines;
    }
}
