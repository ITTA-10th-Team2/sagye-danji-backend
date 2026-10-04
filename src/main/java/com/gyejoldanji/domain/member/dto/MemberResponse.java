package com.gyejoldanji.domain.member.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gyejoldanji.domain.member.entity.Member;

/**
 * 내 정보 응답. memberId는 JSON 문자열, onboardingCompletedAt은 UTC ISO 8601(Z)이며 미완료면 필드를 생략한다. 외부 식별값·세션
 * 정보는 담지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberResponse(String memberId, String onboardingStatus, String onboardingCompletedAt) {

    /** 저장된 완료 시각(UTC LocalDateTime)을 서버 기본 시간대가 아닌 UTC로 해석한다. */
    public static MemberResponse from(Member member) {
        LocalDateTime completedAt = member.getOnboardingCompletedAt();
        return new MemberResponse(String.valueOf(member.getId()),
                member.isOnboardingCompleted() ? "COMPLETED" : "NOT_COMPLETED",
                completedAt == null ? null : completedAt.toInstant(ZoneOffset.UTC).toString());
    }
}
