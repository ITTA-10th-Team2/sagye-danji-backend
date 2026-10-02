package com.gyejoldanji.domain;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainBehaviorTest {

    @Test
    void createsEntitiesThroughUnifiedFactoryMethods() {
        Member member = Member.create("TOSS", "member-1");
        Record record = Record.create(member, LocalDate.of(2026, 10, 1), SeasonType.AUTUMN, "가을");
        Image image = Image.create(record, "original/key", null, PhotoSource.CAMERA, 0);
        SeasonalContent content = SeasonalContent.create(
                SeasonType.AUTUMN, ContentCategory.SCENERY, "단풍 보기", "가까운 공원에서 단풍을 감상해요.");
        assertThat(record.getMember()).isSameAs(member);
        assertThat(image.getRecord()).isSameAs(record);
        assertThat(content.isActive()).isTrue();
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
    void createsActiveMemberAndKeepsLatestAuthentication() {
        Member member = Member.create("TOSS_ANON", "member-1");
        LocalDateTime firstAuthentication = LocalDateTime.of(2026, 10, 1, 3, 0);

        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getLastLoginAt()).isNull();

        member.recordAuthentication(firstAuthentication);
        member.recordAuthentication(firstAuthentication.plusDays(1));

        assertThat(member.getLastLoginAt()).isEqualTo(firstAuthentication.plusDays(1));
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
                SeasonType.AUTUMN, ContentCategory.FOOD, "밤 먹기", "제철 밤을 맛봐요.");

        content.deactivate();
        assertThat(content.isActive()).isFalse();

        content.update(SeasonType.WINTER, ContentCategory.ACTIVITY, "눈사람 만들기", "눈사람을 만들어봐요.");
        content.activate();

        assertThat(content.getSeason()).isEqualTo(SeasonType.WINTER);
        assertThat(content.getCategory()).isEqualTo(ContentCategory.ACTIVITY);
        assertThat(content.getTitle()).isEqualTo("눈사람 만들기");
        assertThat(content.isActive()).isTrue();
    }

    @Test
    void rejectsNegativeImageOrder() {
        Member member = Member.create("TOSS", "member-1");
        Record record = Record.create(member, LocalDate.of(2026, 10, 1), SeasonType.AUTUMN, null);

        assertThatThrownBy(() -> Image.create(record, "original/key", null, PhotoSource.GALLERY, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미지 표시 순서는 0 이상이어야 합니다.");
    }

    @Test
    void startsSessionAndAdvancesGenerationWithoutExtendingExpiry() {
        Member member = Member.create("TOSS_ANON", "member-1");
        UUID sessionKey = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 3, 0);

        AuthSession session = AuthSession.start(member, sessionKey, now, Duration.ofDays(14));
        session.advanceGeneration();
        session.advanceGeneration();

        assertThat(session.getMember()).isSameAs(member);
        assertThat(session.getSessionKey()).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(session.getCurrentRefreshGeneration()).isEqualTo(2);
        assertThat(session.getExpiresAt()).isEqualTo(now.plusDays(14));
        assertThat(session.getRevokedAt()).isNull();
        assertThatThrownBy(() -> AuthSession.start(member, sessionKey, now, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("세션 유효 기간은 0보다 커야 합니다.");
    }

    @Test
    void keepsFirstSessionRevocation() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 3, 0);
        AuthSession session = AuthSession.start(
                Member.create("TOSS_ANON", "member-1"), UUID.randomUUID(), now, Duration.ofDays(14));

        session.revoke(SessionRevokeReason.LOGOUT, now.plusHours(1));
        session.revoke(SessionRevokeReason.REFRESH_REUSE, now.plusHours(2));

        assertThat(session.getRevokedAt()).isEqualTo(now.plusHours(1));
        assertThat(session.getRevokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
    }
}
