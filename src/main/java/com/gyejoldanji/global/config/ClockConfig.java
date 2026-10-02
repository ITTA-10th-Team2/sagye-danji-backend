package com.gyejoldanji.global.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;

/**
 * 애플리케이션 공통 시각 설정.
 *
 * <p>현재 시각은 {@link Clock} Bean으로만 얻어 테스트에서 고정 Clock으로 대체할 수 있게 한다.
 */
@Configuration
public class ClockConfig {

    /** 서버 시간대와 관계없이 UTC 현재 시각을 제공하는 Clock을 등록한다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** JPA auditing 시각. DB {@code TIMESTAMP(6)}에 맞춰 UTC 마이크로초로 자른다. */
    @Bean
    public DateTimeProvider utcDateTimeProvider(Clock clock) {
        return () -> Optional.of(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
    }
}
