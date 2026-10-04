package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 기록 날짜의 월 경계에서 서버 계절 정책이 일관되게 적용되는지 검증한다. */
class SeasonResolverTest {

    private final SeasonResolver resolver = new SeasonResolver();

    @ParameterizedTest
    @CsvSource({
            "2026-02-28, WINTER",
            "2026-03-01, SPRING",
            "2026-05-31, SPRING",
            "2026-06-01, SUMMER",
            "2026-08-31, SUMMER",
            "2026-09-01, AUTUMN",
            "2026-11-30, AUTUMN",
            "2026-12-01, WINTER"
    })
    void resolvesSeasonAtMonthBoundaries(LocalDate date, SeasonType expected) {
        assertThat(resolver.resolve(date)).isEqualTo(expected);
    }
}
