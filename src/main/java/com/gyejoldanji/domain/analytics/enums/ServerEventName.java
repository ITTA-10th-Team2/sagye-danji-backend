package com.gyejoldanji.domain.analytics.enums;

/** 업무 API 성공 뒤 백엔드가 직접 남기는 행동 이벤트. 수집 API로는 받지 않는다. */
public enum ServerEventName {
    ONBOARDING_COMPLETE,
    RECORD_COMPLETE,
    RECORD_DELETE,
    STICKER_SAVE
}
