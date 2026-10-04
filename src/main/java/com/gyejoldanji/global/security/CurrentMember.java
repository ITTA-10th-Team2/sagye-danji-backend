package com.gyejoldanji.global.security;

/**
 * JWT와 DB 회원·세션 검사를 통과한 보호 요청의 현재 회원. {@code @AuthenticationPrincipal}로 받는다.
 *
 * @param memberId  회원 내부 ID(JWT sub)
 * @param sessionId 세션 내부 PK. JWT sid(세션 UUID 문자열)와 다르다.
 */
public record CurrentMember(Long memberId, Long sessionId) {
}
