package com.gyejoldanji.domain.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 홈 화면에 표시할 현재 회원의 기록 누적 요약. */
@Schema(description = "홈 기록 요약")
public record RecordSummaryResponse(
        @Schema(description = "현재 회원이 작성한 전체 기록 수", example = "3") long recordCount,
        @Schema(description = "첫 기록일부터 오늘까지 양 끝 날짜를 포함한 일수. 기록이 없으면 0", example = "12")
        long recordingDayCount
) {
}
