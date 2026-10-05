package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** 전체·계절 단지 목록에 표시할 기록 요약. */
@Schema(description = "기록 목록 항목")
public record RecordListItemResponse(
        @Schema(description = "기록 ID", example = "101") Long id,
        @Schema(description = "기록 날짜", example = "2026-10-04") LocalDate recordDate,
        @Schema(description = "기록 계절", example = "AUTUMN") SeasonType season,
        @Schema(description = "기록 메모", example = "가을밤 산책 🍂", nullable = true) String memo,
        @Schema(description = "표시 순서가 가장 앞선 대표 이미지", nullable = true) CoverImageResponse coverImage,
        @Schema(description = "기록에 연결된 이미지 수", example = "2") int imageCount
) {

    /** Record와 한 번에 조회한 이미지 목록으로 화면용 요약을 만든다. */
    public static RecordListItemResponse from(Record record, List<Image> images) {
        CoverImageResponse coverImage = images.stream()
                .min(Comparator.comparingInt(Image::getSortOrder))
                .map(CoverImageResponse::from)
                .orElse(null);
        return new RecordListItemResponse(record.getId(), record.getRecordDate(), record.getSeason(),
                record.getMemo(), coverImage, images.size());
    }

    /** 목록 대표 이미지의 공개 가능한 메타데이터. */
    @Schema(description = "기록 대표 이미지 메타데이터")
    public record CoverImageResponse(
            @Schema(description = "이미지 ID", example = "501") Long id,
            @Schema(description = "이미지 입력 경로", example = "CAMERA") PhotoSource source,
            @Schema(description = "기록 안의 표시 순서", example = "0") int sortOrder
    ) {
        /** 객체 키를 노출하지 않고 Image를 대표 이미지 응답으로 변환한다. */
        private static CoverImageResponse from(Image image) {
            // TODO(infra): 이미지 조회 URL 제공 계약이 추가되면 이 응답에 URL을 매핑한다.
            return new CoverImageResponse(image.getId(), image.getSource(), image.getSortOrder());
        }
    }
}

