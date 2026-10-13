package com.gyejoldanji.domain.analytics.enums;

/** 프론트가 수집 API로 보낼 수 있는 행동 이벤트. 이벤트별 속성 규칙은 {@code AnalyticsEventValidator}에 있다. */
public enum ClientEventName {
    ONBOARDING_START,
    ONBOARDING_SKIP,
    HOME_VIEW,
    HOME_ELEMENT_TAP,
    RECORD_SCREEN_VIEW,
    RECORD_START,
    JAR_VIEW,
    LOCKED_SEASON_CLICK,
    SHARE_CLICK,
    SCREEN_ENGAGEMENT,
    SESSION_START
}
