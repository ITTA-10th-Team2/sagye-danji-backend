package com.gyejoldanji.domain.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 계절 단지 기록과 이미지 미리보기의 cursor 기반 페이지 응답. */
@Schema(description = "계절 단지 기록 커서 페이지")
public record SeasonRecordCursorPageResponse(
        @Schema(description = "현재 페이지의 계절 단지 기록 목록") List<SeasonRecordListItemResponse> items,
        @Schema(description = "다음 페이지 조회용 opaque cursor", nullable = true,
                example = "MjAyNi0xMC0wNHwxMDE") String nextCursor,
        @Schema(description = "다음 페이지 존재 여부", example = "true") boolean hasNext
) {

    /** 외부 변경으로부터 목록을 보호하는 불변 페이지를 만든다. */
    public SeasonRecordCursorPageResponse {
        items = List.copyOf(items);
    }
}
