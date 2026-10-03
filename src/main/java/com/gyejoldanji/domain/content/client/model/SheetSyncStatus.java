package com.gyejoldanji.domain.content.client.model;

/** Google Sheets 행의 동기화 처리 상태. */
public enum SheetSyncStatus {
    /** 운영자가 작성 중인 상태. */
    DRAFT,
    /** 동기화 요청이 완료된 상태. */
    READY,
    /** 백엔드가 처리 중이거나, DB 반영 후 시트 결과 기록을 재시도해야 하는 상태. */
    PROCESSING,
    /** DB 반영이 완료된 상태. */
    SYNCED,
    /** 동기화에 실패한 상태. */
    FAILED
}
