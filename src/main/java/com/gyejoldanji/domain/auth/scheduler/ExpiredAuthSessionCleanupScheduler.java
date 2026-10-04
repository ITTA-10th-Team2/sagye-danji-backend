package com.gyejoldanji.domain.auth.scheduler;

import com.gyejoldanji.domain.auth.service.ExpiredAuthSessionCleanupService;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 03:00 UTC(한국 12:00)에 만료 이력 정리를 실행한다. Google Sheets 연동 설정과 관계없이 등록된다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredAuthSessionCleanupScheduler {

    private final ExpiredAuthSessionCleanupService cleanupService;

    /**
     * 실패는 여기서 끝낸다. SQL·드라이버 예외 메시지에 DB 값이 섞일 수 있어 메시지를 뺀 타입·스택만 남기고, 스케줄러 기본 오류 처리(예외
     * 원문 출력)로 넘기지 않는다. 자동 재시도 없이 다음 날 실행을 기다린다.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void cleanUp() {
        try {
            cleanupService.cleanUp();
        } catch (RuntimeException e) {
            log.error("만료 인증 세션 정리 실패: ", GlobalExceptionHandler.withoutMessages(e));
        }
    }
}
