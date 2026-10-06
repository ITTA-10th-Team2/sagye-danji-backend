package com.gyejoldanji.domain.jar.dto;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.record.dto.SeasonRecordListItemResponse;
import com.gyejoldanji.global.common.enums.SeasonType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 단지 기본 화면 한 페이지의 기록(최대 5개)과 스티커 배치. */
@Schema(description = "단지 페이지")
public record JarPageResponse(
        @Schema(example = "31") Long jarPageId,
        @Schema(example = "2026") int year,
        @Schema(example = "AUTUMN") SeasonType season,
        @Schema(example = "0") int pageNumber,
        List<SeasonRecordListItemResponse> records,
        List<JarStickerResponse.Item> stickers
) {
    public JarPageResponse {
        records = List.copyOf(records);
        stickers = List.copyOf(stickers);
    }

    public static JarPageResponse of(JarPage page, List<SeasonRecordListItemResponse> records) {
        return new JarPageResponse(page.getId(), page.getYear(), page.getSeason(), page.getPageNumber(), records,
                JarStickerResponse.from(page).items());
    }
}
