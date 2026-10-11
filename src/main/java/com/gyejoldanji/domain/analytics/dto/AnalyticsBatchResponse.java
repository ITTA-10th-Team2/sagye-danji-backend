package com.gyejoldanji.domain.analytics.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 수집 결과. 두 값의 합은 요청 건수와 같다. */
public record AnalyticsBatchResponse(
        @Schema(description = "새로 저장한 이벤트 수", example = "2")
        int acceptedCount,
        @Schema(description = "이미 저장돼 있어 건너뛴 이벤트 수(재전송)", example = "1")
        int duplicateCount) {
}
