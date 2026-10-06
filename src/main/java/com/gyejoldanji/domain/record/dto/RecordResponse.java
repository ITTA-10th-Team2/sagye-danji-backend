package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** 기록 상세와 저장 결과에 사용하는 기록·이미지 메타데이터 응답. */
@Schema(description = "기록 상세 정보")
public record RecordResponse(
        @Schema(description = "기록 ID. 수정·삭제 API의 recordId로 사용", example = "1") Long id,
        @Schema(description = "기록 날짜", example = "2026-10-04") LocalDate recordDate,
        @Schema(description = "기록 날짜에서 서버가 계산한 계절", example = "AUTUMN") SeasonType season,
        @Schema(description = "기록 메모", example = "가을밤 산책 🍂", nullable = true) String memo,
        @Schema(description = "표시 순서대로 정렬된 이미지 메타데이터") List<RecordImageResponse> images,
        @Schema(description = "UTC 기준 생성 시각", example = "2026-10-03T17:10:00Z") String createdAt,
        @Schema(description = "UTC 기준 수정 시각", example = "2026-10-03T17:10:00Z") String updatedAt
) {

    /** flush를 마친 Entity를 외부 응답으로 변환하고 객체 키는 노출하지 않는다. */
    public static RecordResponse from(Record record, List<Image> images, Function<String, String> viewUrlIssuer) {
        return new RecordResponse(record.getId(), record.getRecordDate(), record.getSeason(), record.getMemo(),
                images.stream().sorted(Comparator.comparingInt(Image::getSortOrder))
                        .map(image -> RecordImageResponse.from(image, viewUrlIssuer)).toList(),
                utc(record.getCreatedAt()), utc(record.getUpdatedAt()));
    }

    /** 저장된 UTC LocalDateTime을 서버 기본 시간대와 무관한 ISO-8601 Z 문자열로 변환한다. */
    private static String utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

}
