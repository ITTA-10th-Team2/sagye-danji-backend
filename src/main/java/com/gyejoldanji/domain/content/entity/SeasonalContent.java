package com.gyejoldanji.domain.content.entity;

import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.global.common.BaseTimeEntity;
import com.gyejoldanji.global.common.enums.SeasonType;
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

/** 계절별 추천 활동의 기본 정보를 관리한다. */
@Entity
@Table(name = "seasonal_contents", indexes = @Index(name = "idx_contents_season_active", columnList = "season, is_active"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonalContent extends BaseTimeEntity {

    /** 서비스 내부에서 사용하는 추천 활동 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 활동을 추천할 계절. */
    @Enumerated(EnumType.STRING)
    @Column(name = "season", nullable = false)
    private SeasonType season;

    /** 활동 분류 및 홈 아이콘 구분. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private ContentCategory category;

    /** 화면에 표시할 활동 제목. */
    @Column(name = "title", nullable = false, length = 100)
    private String title;

    /** 활동 소개 및 참여 방법. */
    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    /** 신규 추천 대상 여부. */
    @ColumnDefault("true")
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** 활성 상태의 신규 추천 활동을 생성한다. */
    public static SeasonalContent create(SeasonType season, ContentCategory category,
                                         String title, String description) {
        SeasonalContent content = new SeasonalContent();
        content.season = Objects.requireNonNull(season, "season");
        content.category = Objects.requireNonNull(category, "category");
        content.title = validateTitle(title);
        content.description = requireText(description, "활동 설명");
        return content;
    }

    /** 추천 활동의 계절·카테고리·문구를 수정한다. */
    public void update(SeasonType season, ContentCategory category, String title, String description) {
        this.season = Objects.requireNonNull(season);
        this.category = Objects.requireNonNull(category);
        this.title = validateTitle(title);
        this.description = requireText(description, "활동 설명");
    }

    /** 기존 데이터는 보존하고 신규 추천에서 제외한다. */
    public void deactivate() {
        active = false;
    }

    /** 비활성화된 활동을 다시 추천 대상으로 전환한다. */
    public void activate() {
        active = true;
    }

    /** 활동 제목의 필수값과 최대 길이를 검증한다. */
    private static String validateTitle(String title) {
        String validated = requireText(title, "활동 제목");
        if (validated.codePointCount(0, validated.length()) > 100) {
            throw new IllegalArgumentException("활동 제목은 100자를 초과할 수 없습니다.");
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
