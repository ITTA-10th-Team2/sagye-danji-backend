package com.gyejoldanji.global.config;

import com.gyejoldanji.GyejolDanjiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    @Test
    void suppliesUtcTimeTruncatedToMicros() {
        // 시스템 기본 시간대와 무관하게 UTC로 공급되는지 보기 위해 KST Clock 사용
        Clock fixed = Clock.fixed(Instant.parse("2026-10-02T01:02:03.123456789Z"), ZoneId.of("Asia/Seoul"));

        DateTimeProvider provider = new ClockConfig().utcDateTimeProvider(fixed);

        assertThat(provider.getNow()).contains(LocalDateTime.of(2026, 10, 2, 1, 2, 3, 123_456_000));
    }

    @Test
    void auditingRefPointsToUtcProviderBean() {
        String ref = GyejolDanjiApplication.class.getAnnotation(EnableJpaAuditing.class).dateTimeProviderRef();

        try (var context = new AnnotationConfigApplicationContext(ClockConfig.class)) {
            assertThat(context.getBean(ref)).isInstanceOf(DateTimeProvider.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        }
    }
}
