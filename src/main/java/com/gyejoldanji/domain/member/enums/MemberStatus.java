package com.gyejoldanji.domain.member.enums;

/** 회원 이용 상태. ACTIVE만 인증할 수 있다. */
public enum MemberStatus {
    /** 정상 이용. */
    ACTIVE,
    /** 내부 탈퇴 처리. */
    WITHDRAWN,
    /** 운영 차단. */
    BLOCKED
}
