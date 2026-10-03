package com.gyejoldanji.domain.auth.dto;

/**
 * 익명 인증 성공 응답. anonKey와 내부 엔티티는 담지 않는다.
 *
 * @param expiresIn        Access 남은 수명(초). 발급 시각 기준 내림 값이다.
 * @param refreshExpiresIn 세션 절대 만료까지 남은 수명(초).
 * @param isNewMember      내부 회원의 첫 인증 성공 여부.
 */
public record AnonymousAuthResponse(String tokenType, String accessToken, long expiresIn, String refreshToken,
                                    long refreshExpiresIn, boolean isNewMember, MemberInfo member) {

    /** 회원 ID(문자열)와 온보딩 상태(NOT_COMPLETED·COMPLETED). */
    public record MemberInfo(String memberId, String onboardingStatus) {
    }

    /** 로그·디버그 출력에 토큰이 남지 않게 가린다. */
    @Override
    public String toString() {
        return "AnonymousAuthResponse[REDACTED]";
    }
}
