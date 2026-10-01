package com.gyejoldanji.domain.member.entity;

import com.gyejoldanji.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/** 인증 식별자와 온보딩 정보를 보관하는 회원. */
@Entity
@Table(name = "members", uniqueConstraints = @UniqueConstraint(name = "uk_members_provider_user", columnNames = {"identity_provider", "provider_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    /** 서비스 내부에서 사용하는 회원 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 사용자를 인증한 제공자. */
    @Column(name = "identity_provider", nullable = false, length = 30)
    private String identityProvider;

    /** 인증 제공자가 발급한 사용자 식별자. */
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    /** UTC 기준 온보딩 완료 시각이며 미완료는 null. */
    @Column(name = "onboarding_completed_at", columnDefinition = "timestamp(6)")
    private LocalDateTime onboardingCompletedAt;

    /** 인증 제공자의 식별자로 신규 회원을 생성한다. */
    public static Member create(String identityProvider, String providerUserId) {
        Member member = new Member();
        member.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        member.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId");
        return member;
    }

    /** 온보딩 최초 완료 시각을 기록한다. */
    public void completeOnboarding(LocalDateTime completedAt) {
        Objects.requireNonNull(completedAt);
        if (onboardingCompletedAt == null) {
            onboardingCompletedAt = completedAt;
        }
    }

    /** 온보딩 완료 여부를 반환한다. */
    public boolean isOnboardingCompleted() {
        return onboardingCompletedAt != null;
    }
}
