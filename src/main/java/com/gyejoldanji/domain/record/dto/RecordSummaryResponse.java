package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.global.common.enums.SeasonType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** 홈 화면에 표시할 현재 회원의 기록 누적 요약. */
@Schema(description = "홈 기록 요약")
public record RecordSummaryResponse(
        @Schema(description = "현재 회원이 작성한 전체 기록 수", example = "3") long recordCount,
        @Schema(description = "첫 기록일부터 오늘까지 양 끝 날짜를 포함한 일수. 기록이 없으면 0", example = "12")
        long recordingDayCount,
        @Schema(description = "계절별 기록 수. 기록이 없는 계절도 0으로 포함")
        Map<SeasonType, Long> seasonRecordCounts
) {
    /** 외부 변경으로부터 계절 집계를 보호한다. */
    public RecordSummaryResponse {
        seasonRecordCounts = Map.copyOf(seasonRecordCounts);
    }
}
