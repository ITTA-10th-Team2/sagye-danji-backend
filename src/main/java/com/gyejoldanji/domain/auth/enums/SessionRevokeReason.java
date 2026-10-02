package com.gyejoldanji.domain.auth.enums;

/** 인증 세션 폐기 사유. */
public enum SessionRevokeReason {
    /** 사용자의 현재 세션 종료. */
    LOGOUT,
    /** 이미 사용한 Refresh 토큰의 재사용 탐지. */
    REFRESH_REUSE,
    /** 회원 탈퇴. */
    MEMBER_WITHDRAWN,
    /** 회원 차단. */
    MEMBER_BLOCKED
}
