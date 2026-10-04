package com.gyejoldanji.domain.auth.dto;

/**
 * Refresh 성공 응답. 새 토큰 쌍과 응답 TTL만 담는다(회원 정보·내부 ID·해시 없음).
 *
 * @param expiresIn        Access 남은 수명(초). 발급 시각 기준 내림 값이다.
 * @param refreshExpiresIn 세션 절대 만료까지 남은 수명(초). 갱신해도 연장되지 않는다.
 */
public record RefreshResponse(String tokenType, String accessToken, long expiresIn, String refreshToken,
                              long refreshExpiresIn) {

    /** 로그·디버그 출력에 토큰이 남지 않게 가린다. */
    @Override
    public String toString() {
        return "RefreshResponse[REDACTED]";
    }
}
