package com.gyejoldanji.domain;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainBehaviorTest {

    @Test
    void createsEntitiesThroughUnifiedFactoryMethods() {
        Member member = Member.create("TOSS", "member-1");
        Record record = Record.create(member, LocalDate.of(2026, 10, 1), SeasonType.AUTUMN, "가을");
        Image image = Image.create(record, "original/key", null, PhotoSource.CAMERA, 0);
        SeasonalContent content = SeasonalContent.create(
                "1d9477ea-a0bd-4b21-b4a6-4aaf60a7f458",
                SeasonType.AUTUMN, ContentCategory.SCENERY,
                "단풍", "20261001", "20261130",
                OptimalPeriod.PEAK, "전국", "산림청", "20260918",
                "단풍 보기", "가까운 공원에서 단풍을 감상해요.");
        assertThat(record.getMember()).isSameAs(member);
        assertThat(image.getRecord()).isSameAs(record);
        assertThat(content.isRecommendationStatus()).isFalse();
    }

    @Test
    void completesOnboardingOnlyOnce() {
        Member member = Member.create("TOSS", "member-1");
        LocalDateTime firstCompletion = LocalDateTime.of(2026, 10, 1, 3, 0);

        member.completeOnboarding(firstCompletion);
        member.completeOnboarding(firstCompletion.plusDays(1));

        assertThat(member.isOnboardingCompleted()).isTrue();
        assertThat(member.getOnboardingCompletedAt()).isEqualTo(firstCompletion);
    }

    @Test
    void updatesRecordDateSeasonAndMemoTogether() {
        Member member = Member.create("TOSS", "member-1");
        Record record = Record.create(member, LocalDate.of(2026, 9, 30), SeasonType.AUTUMN, "수정 전");

        record.update(LocalDate.of(2026, 12, 1), SeasonType.WINTER, "수정 후 ❄️");

        assertThat(record.getRecordDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(record.getSeason()).isEqualTo(SeasonType.WINTER);
        assertThat(record.getMemo()).isEqualTo("수정 후 ❄️");
    }

    @Test
    void rejectsRecordMemoOverOneHundredUnicodeCharacters() {
        Member member = Member.create("TOSS", "member-1");

        assertThatThrownBy(() -> Record.create(
                member, LocalDate.of(2026, 10, 1), SeasonType.AUTUMN, "🍁".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("기록 글은 100자를 초과할 수 없습니다.");
    }

    @Test
    void changesSeasonalContentAndRecommendationState() {
        SeasonalContent content = SeasonalContent.create(
                "c1849166-210b-45d0-bd54-681c0147b010",
                SeasonType.AUTUMN, ContentCategory.FOOD,
                "밤", "20260901", "20261130",
                OptimalPeriod.PEAK, "전국", "농촌진흥청", "20260820",
                "밤 먹기", "제철 밤을 맛봐요.");

        content.assignRecommendation(0);
        assertThat(content.isRecommendationStatus()).isTrue();

        content.update(
                SeasonType.WINTER, ContentCategory.ACTIVITY_LIFESTYLE,
                "눈", "20261201", "20270228",
                OptimalPeriod.PEAK, "전국", "기상청", "20261120",
                "눈사람 만들기", "눈사람을 만들어봐요.");
        content.rejectRecommendation();

        assertThat(content.getSeason()).isEqualTo(SeasonType.WINTER);
        assertThat(content.getCategory()).isEqualTo(ContentCategory.ACTIVITY_LIFESTYLE);
        assertThat(content.getTitle()).isEqualTo("눈사람 만들기");
        assertThat(content.isRecommendationApproved()).isFalse();
    }

    @Test
    void rejectsNegativeImageOrder() {
        Member member = Member.create("TOSS", "member-1");
        Record record = Record.create(member, LocalDate.of(2026, 10, 1), SeasonType.AUTUMN, null);

        assertThatThrownBy(() -> Image.create(record, "original/key", null, PhotoSource.GALLERY, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미지 표시 순서는 0 이상이어야 합니다.");
    }
}
