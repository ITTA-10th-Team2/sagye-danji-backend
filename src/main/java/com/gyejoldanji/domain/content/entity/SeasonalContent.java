package com.gyejoldanji.domain.content.entity;

import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.global.common.BaseTimeEntity;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.util.Objects;
import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 계절별 추천 활동의 기본 정보를 관리한다. */
@Entity
@Table(name = "seasonal_contents", indexes = {
        @Index(name = "idx_contents_season", columnList = "season"),
        @Index(name = "idx_contents_recommendation", columnList = "recommendation_status, recommendation_approved, recommendation_order")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonalContent extends BaseTimeEntity {

    /** 시트 날짜 입력 형식. */
    private static final DateTimeFormatter SHEET_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /** 서비스 내부에서 사용하는 추천 활동 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 외부 데이터 원본과 콘텐츠를 멱등하게 연결하는 고유 코드. */
    @Column(name = "content_code", nullable = false, unique = true, updatable = false, length = 36)
    private String contentCode;

    /** 활동을 추천할 계절. */
    @Enumerated(EnumType.STRING)
    @Column(name = "season", nullable = false)
    private SeasonType season;

    /** 활동 분류 및 홈 아이콘 구분. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private ContentCategory category;

    /** 추천 콘텐츠의 근거가 되는 소재명. */
    @Column(name = "material", nullable = false, length = 100)
    private String material;

    /** 소재를 안정적으로 추천할 수 있는 선택적 시작일. */
    @Column(name = "available_start_date", length = 8)
    private String availableStartDate;

    /** 소재를 안정적으로 추천할 수 있는 선택적 종료일. */
    @Column(name = "available_end_date", length = 8)
    private String availableEndDate;

    /** 소재를 추천하기 가장 좋은 시기 단계. */
    @Enumerated(EnumType.STRING)
    @Column(name = "optimal_period", nullable = false, length = 10)
    private OptimalPeriod optimalPeriod;

    /** 소재를 적용할 지역 범위. */
    @Column(name = "region", nullable = false, length = 100)
    private String region;

    /** 사용 가능 시기 판단에 사용한 출처. */
    @Column(name = "timing_source", nullable = false, length = 500)
    private String timingSource;

    /** 출처 정보를 마지막으로 확인한 선택적 날짜. */
    @Column(name = "source_checked_date", length = 8)
    private String sourceCheckedDate;

    /** 화면에 표시할 활동 제목. */
    @Column(name = "title", nullable = false, length = 100)
    private String title;

    /** 활동 소개 및 참여 방법. */
    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    /** 이번 주 추천 콘텐츠로 선정됐는지 여부. */
    @ColumnDefault("false")
    @Column(name = "recommendation_status", nullable = false)
    private boolean recommendationStatus = false;

    /** 이번 주 추천에서 배정된 요일 순서. 월요일 0부터 일요일 6까지 사용한다. */
    @Column(name = "recommendation_order")
    private Integer recommendationOrder;

    /** 운영자가 이번 주 추천 노출을 승인했는지 여부. */
    @ColumnDefault("false")
    @Column(name = "recommendation_approved", nullable = false)
    private boolean recommendationApproved = false;

    /** 활성 상태의 신규 추천 활동을 생성한다. */
    public static SeasonalContent create(
            String contentCode,
            SeasonType season,
            ContentCategory category,
            String material,
            String availableStartDate,
            String availableEndDate,
            OptimalPeriod optimalPeriod,
            String region,
            String timingSource,
            String sourceCheckedDate,
            String title,
            String description
    ) {
        SeasonalContent content = new SeasonalContent();
        content.contentCode = validateContentCode(contentCode);
        content.applyDetails(season, category, material,
                availableStartDate, availableEndDate, optimalPeriod, region,
                timingSource, sourceCheckedDate, title, description);
        return content;
    }

    /** 추천 활동과 수집 근거 정보를 수정한다. */
    public void update(
            SeasonType season,
            ContentCategory category,
            String material,
            String availableStartDate,
            String availableEndDate,
            OptimalPeriod optimalPeriod,
            String region,
            String timingSource,
            String sourceCheckedDate,
            String title,
            String description
    ) {
        applyDetails(season, category, material,
                availableStartDate, availableEndDate, optimalPeriod, region,
                timingSource, sourceCheckedDate, title, description);
    }

    /** 검증된 추천 활동과 수집 근거 정보를 반영한다. */
    private void applyDetails(
            SeasonType season,
            ContentCategory category,
            String material,
            String availableStartDate,
            String availableEndDate,
            OptimalPeriod optimalPeriod,
            String region,
            String timingSource,
            String sourceCheckedDate,
            String title,
            String description
    ) {
        this.season = Objects.requireNonNull(season, "season");
        this.category = Objects.requireNonNull(category, "category");
        this.material = validateLength(material, "소재", 100);
        this.availableStartDate = validateNullableDate(availableStartDate, "사용 가능 시작일");
        this.availableEndDate = validateNullableDate(availableEndDate, "사용 가능 종료일");
        validateDateRange(this.availableStartDate, this.availableEndDate);
        this.optimalPeriod = Objects.requireNonNull(optimalPeriod, "optimalPeriod");
        this.region = validateLength(region, "지역", 100);
        this.timingSource = validateLength(timingSource, "시기 출처", 500);
        this.sourceCheckedDate = validateNullableDate(sourceCheckedDate, "출처 확인일");
        this.title = validateTitle(title);
        this.description = requireText(description, "활동 설명");
    }

    /** 이번 주 추천으로 선정하고 요일 순서를 배정한다. */
    public void assignRecommendation(int order) {
        if (order < 0 || order > 6) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_ORDER_INVALID);
        }
        recommendationStatus = true;
        recommendationOrder = order;
        recommendationApproved = true;
    }

    /** 이번 주 추천의 운영 승인을 복구한다. */
    public void approveRecommendation() {
        if (!recommendationStatus || recommendationOrder == null) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_NOT_ASSIGNED);
        }
        recommendationApproved = true;
    }

    /** 이번 주 추천 선정과 순서를 유지한 채 운영 승인만 해제한다. */
    public void rejectRecommendation() {
        recommendationApproved = false;
    }

    /** 다음 주 재선정을 위해 기존 추천 상태와 순서를 초기화한다. */
    public void clearRecommendation() {
        recommendationStatus = false;
        recommendationOrder = null;
        recommendationApproved = false;
    }

    /** UUID 형식의 외부 고유 코드를 검증한다. */
    private static String validateContentCode(String contentCode) {
        Objects.requireNonNull(contentCode, "콘텐츠 코드는 필수입니다.");
        if (contentCode.isBlank()) {
            throw new IllegalArgumentException("콘텐츠 코드는 비어 있을 수 없습니다.");
        }
        if (contentCode.length() > 36) {
            throw new IllegalArgumentException("콘텐츠 코드는 36자를 초과할 수 없습니다.");
        }
        try {
            UUID.fromString(contentCode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("콘텐츠 코드는 UUID 형식이어야 합니다.");
        }
        return contentCode;
    }

    /** 활동 제목의 필수값과 최대 길이를 검증한다. */
    private static String validateTitle(String title) {
        return validateLength(title, "활동 제목", 100);
    }

    /** 선택적으로 입력된 yyyyMMdd 형식의 실제 날짜를 검증한다. */
    private static String validateNullableDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String validated = value.trim();
        try {
            LocalDate.parse(validated, SHEET_DATE_FORMATTER);
            return validated;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + "은 yyyyMMdd 형식이어야 합니다.");
        }
    }

    /** 사용 가능 종료일이 시작일보다 빠르지 않은지 검증한다. */
    private static void validateDateRange(String startDate, String endDate) {
        if (startDate == null || endDate == null) {
            return;
        }
        LocalDate start = LocalDate.parse(startDate, SHEET_DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, SHEET_DATE_FORMATTER);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("사용 가능 종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    /** 필수 문자열의 최대 길이를 검증한다. */
    private static String validateLength(String value, String fieldName, int maxLength) {
        String validated = requireText(value, fieldName);
        if (validated.codePointCount(0, validated.length()) > maxLength) {
            throw new IllegalArgumentException(fieldName + "은 " + maxLength + "자를 초과할 수 없습니다.");
        }
        return validated;
    }

    /** 공백이 아닌 필수 문구인지 검증한다. */
    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + "은 필수입니다.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 비어 있을 수 없습니다.");
        }
        return value;
    }
}
