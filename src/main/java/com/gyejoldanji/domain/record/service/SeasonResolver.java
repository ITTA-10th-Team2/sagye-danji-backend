package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.global.common.enums.SeasonType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Objects;

/** 기록 날짜와 DB에 저장할 계절을 일치시키는 서버 계절 정책. */
@Component
public class SeasonResolver {

    /** 봄 3~5월, 여름 6~8월, 가을 9~11월, 겨울 12~2월 규칙으로 계절을 계산한다. */
    public SeasonType resolve(LocalDate recordDate) {
        int month = Objects.requireNonNull(recordDate, "recordDate").getMonthValue();
        return switch (month) {
            case 3, 4, 5 -> SeasonType.SPRING;
            case 6, 7, 8 -> SeasonType.SUMMER;
            case 9, 10, 11 -> SeasonType.AUTUMN;
            default -> SeasonType.WINTER;
        };
    }
}
