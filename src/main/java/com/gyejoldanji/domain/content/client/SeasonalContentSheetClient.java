package com.gyejoldanji.domain.content.client;

import com.gyejoldanji.domain.content.client.model.SeasonalContentSheetRow;

import java.time.Instant;
import java.util.List;

/** 콘텐츠 동기화 서비스가 사용하는 Google Sheets 접근 계약. */
public interface SeasonalContentSheetClient {

    /** 콘텐츠 시트의 전체 데이터 행을 읽는다. */
    List<SeasonalContentSheetRow> readRows();

    /** 백엔드가 발급한 콘텐츠 코드를 기록한다. */
    void writeContentCode(int rowNumber, String contentCode);

    /** 행을 처리 중 상태로 선점한다. */
    void markProcessing(int rowNumber, Instant processingStartedAt);

    /** 중단된 행을 다시 처리 가능한 상태로 복구한다. */
    void markReady(int rowNumber);

    /** DB 반영이 완료된 행에 결과를 기록한다. */
    void markSynced(int rowNumber, Long dbId, Instant syncedAt);

    /** 처리에 실패한 행에 간결한 오류를 기록한다. */
    void markFailed(int rowNumber, String errorMessage);
}
