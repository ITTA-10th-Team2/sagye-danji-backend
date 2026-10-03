package com.gyejoldanji.global.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import com.gyejoldanji.global.config.properties.TossProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 application.yml의 toss placeholder로 TossProperties를 바인딩·검증한다.
 *
 * <p>클라이언트 Bean 없이 설정만 올리므로 테스트에서 클라이언트를 대체해도 이 검증은 그대로 남는다.
 */
class TossPropertiesTest {

    private static final String PASSWORD = "not-a-real-password";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TossProperties.class);

    /** 필수 환경변수만 주면 기본 주소·timeout으로 기동한다. */
    @Test
    void bindsRequiredValuesWithDefaults() {
        runner.withInitializer(TestApplicationYaml.withEnvironment(environment())).run(context -> {
            assertThat(context).hasNotFailed();
            TossProperties properties = context.getBean(TossProperties.class);
            assertThat(properties.getApiBaseUrl()).isEqualTo("https://apps-in-toss-api.toss.im");
            assertThat(properties.getAppName()).isEqualTo("test-app");
            assertThat(properties.getIdentityEnvironment()).isEqualTo(TossProperties.IdentityEnvironment.DEV);
            assertThat(properties.getMtls().getKeystorePath()).isEqualTo("/not/opened.p12");
            assertThat(properties.getConnectTimeoutSeconds()).isEqualTo(3);
            assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(5);
        });
    }

    /** 필수 환경변수가 하나라도 없으면(미해결 ${...}가 아니라 빈 값) 기동하지 않고, 실패 내용에 암호가 없다. */
    @ParameterizedTest
    @CsvSource({
            "TOSS_APP_NAME, appName",
            "TOSS_IDENTITY_ENVIRONMENT, identityEnvironment",
            "TOSS_MTLS_KEYSTORE_PATH, keystorePath",
            "TOSS_MTLS_KEYSTORE_PASSWORD, keystorePassword"})
    void failsWhenRequiredVariableIsMissing(String variable, String field) {
        Map<String, Object> environment = environment();
        environment.remove(variable);

        runner.withInitializer(TestApplicationYaml.withEnvironment(environment)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining(field);
            StringWriter trace = new StringWriter();
            context.getStartupFailure().printStackTrace(new PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain(PASSWORD);
        });
    }

    /** https가 아닌 주소, 정의되지 않은 배포 구분, 0 이하 timeout은 기동하지 않는다. */
    @ParameterizedTest
    @CsvSource({
            "TOSS_API_BASE_URL, http://apps-in-toss-api.toss.im",
            "TOSS_IDENTITY_ENVIRONMENT, STAGING",
            "TOSS_CONNECT_TIMEOUT_SECONDS, 0",
            "TOSS_REQUEST_TIMEOUT_SECONDS, -1"})
    void failsOnInvalidValue(String variable, String value) {
        Map<String, Object> environment = environment();
        environment.put(variable, value);

        runner.withInitializer(TestApplicationYaml.withEnvironment(environment))
                .run(context -> assertThat(context).hasFailed());
    }

    private static Map<String, Object> environment() {
        return new HashMap<>(Map.of(
                "TOSS_APP_NAME", "test-app",
                "TOSS_IDENTITY_ENVIRONMENT", "DEV",
                "TOSS_MTLS_KEYSTORE_PATH", "/not/opened.p12",
                "TOSS_MTLS_KEYSTORE_PASSWORD", PASSWORD));
    }
}
