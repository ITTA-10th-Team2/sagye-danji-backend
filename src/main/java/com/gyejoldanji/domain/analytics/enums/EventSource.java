package com.gyejoldanji.domain.analytics.enums;

/** 행동 이벤트를 만든 쪽. */
public enum EventSource {
    /** 프론트가 수집 API로 보낸 이벤트. */
    CLIENT,
    /** 백엔드가 업무 처리 뒤 남긴 이벤트. */
    SERVER
}
