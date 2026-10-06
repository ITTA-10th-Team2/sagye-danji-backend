package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** 계절 단지 목록에 표시할 기록과 최대 두 장의 이미지 미리보기. */
@Schema(description = "계절 단지 기록 목록 항목")
public record SeasonRecordListItemResponse(
        @Schema(description = "기록 ID", example = "101") Long id,
        @Schema(description = "기록 날짜", example = "2026-10-04") LocalDate recordDate,
        @Schema(description = "기록 계절", example = "AUTUMN") SeasonType season,
        @Schema(description = "기록 메모", example = "가을밤 산책 🍂", nullable = true) String memo,
        @Schema(description = "표시 순서가 앞선 이미지 미리보기. 최대 2개")
        List<RecordImageResponse> previewImages,
        @Schema(description = "기록에 연결된 전체 이미지 수", example = "5") int imageCount
) {

    private static final int PREVIEW_IMAGE_LIMIT = 2;

    /** 일괄 조회한 이미지 중 표시 순서가 앞선 두 장만 URL과 함께 변환한다. */
    public static SeasonRecordListItemResponse from(Record record, List<Image> images,
                                                     Function<String, String> viewUrlIssuer) {
        List<RecordImageResponse> previewImages = images.stream()
                .sorted(Comparator.comparingInt(Image::getSortOrder))
                .limit(PREVIEW_IMAGE_LIMIT)
                .map(image -> RecordImageResponse.from(image, viewUrlIssuer))
                .toList();
        return new SeasonRecordListItemResponse(record.getId(), record.getRecordDate(), record.getSeason(),
                record.getMemo(), previewImages, images.size());
    }

    /** 외부 변경으로부터 이미지 목록을 보호한다. */
    public SeasonRecordListItemResponse {
        previewImages = List.copyOf(previewImages);
    }
}
