package com.gyejoldanji.global.config;

import com.gyejoldanji.GyejolDanjiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    /** Clock의 시간대와 무관하게 UTC 시각을 마이크로초 정밀도로 공급한다. */
    @Test
    void suppliesUtcTimeTruncatedToMicros() {
        // 시스템 기본 시간대와 무관하게 UTC로 공급되는지 보기 위해 KST Clock 사용
        Clock fixed = Clock.fixed(Instant.parse("2026-10-02T01:02:03.123456789Z"), ZoneId.of("Asia/Seoul"));

        DateTimeProvider provider = new ClockConfig().utcDateTimeProvider(fixed);

        assertThat(provider.getNow()).contains(LocalDateTime.of(2026, 10, 2, 1, 2, 3, 123_456_000));
    }

    /** JPA auditing 참조와 실제 UTC 공급 Bean이 연결되는지 확인한다. */
    @Test
    void auditingRefPointsToUtcProviderBean() {
        String ref = GyejolDanjiApplication.class.getAnnotation(EnableJpaAuditing.class).dateTimeProviderRef();

        try (var context = new AnnotationConfigApplicationContext(ClockConfig.class)) {
            assertThat(context.getBean(ref)).isInstanceOf(DateTimeProvider.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        }
    }

    /** Google Sheets 연동을 켜도 공통 UTC Clock 하나를 재사용한다. */
    @Test
    void sharesClockWhenGoogleSheetsIsEnabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("app.google-sheets.enabled", true)));
            // 실제 인증 파일과 외부 통신 없이 두 설정의 Bean 등록을 검증한다.
            context.addBeanFactoryPostProcessor(factory ->
                    factory.getBeanDefinition("googleSheets").setLazyInit(true));
            context.register(ClockConfig.class, GoogleSheetsConfig.class);
            context.refresh();

            assertThat(context.getBeansOfType(Clock.class)).containsOnlyKeys("clock");
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        }
    }
}
