package com.gyejoldanji.domain.analytics.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;
import java.util.Locale;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.analytics.enums.ServerEventName;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 업무 API가 성공한 뒤 서버 행동 이벤트를 한 건 남긴다. 업무 서비스가 반환된 뒤(업무 트랜잭션 commit 뒤) 컨트롤러에서 부른다.
 *
 * <p>분석 저장 실패는 업무 데이터·응답을 바꾸지 않는다. 헤더 읽기부터 commit까지 모든 실패를 삼키고 이벤트 이름과 예외 타입만 WARN으로
 * 남긴다. 분석 전용 트랜잭션에서 INSERT 한 건만 하며 회원 잠금·기존 eventId 조회는 하지 않는다(서버가 만든 UUID라 중복이 없다).
 *
 * <p>공통 분석 헤더는 헤더마다 따로 검사해 틀린 칸만 null로 저장한다. 없거나 비었거나 여러 값이어도 null이다. 분석 세션은 인증 세션·JWT
 * sid로 대신 채우지 않는다.
 */
@Slf4j
@Component
public class ServerAnalyticsRecorder {

    static final String SESSION_HEADER = "X-Analytics-Session-Id";
    static final String VERSION_HEADER = "X-Client-Version";
    static final String OS_HEADER = "X-Client-OS";

    private final AnalyticsEventRepository repository;
    /** 분석 전용 템플릿. 공유 TransactionTemplate 설정은 바꾸지 않는다. */
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ServerAnalyticsRecorder(AnalyticsEventRepository repository, PlatformTransactionManager transactionManager,
                                   Clock clock) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 기록 생성 성공. 기록은 이미지 한 장이다. */
    public void recordCompleted(Long memberId, Long recordId, HttpServletRequest request) {
        record(ServerEventName.RECORD_COMPLETE, memberId, request, "{\"recordId\":" + recordId + ",\"photoCount\":1}");
    }

    /** 기록 삭제 성공. */
    public void recordDeleted(Long memberId, Long recordId, HttpServletRequest request) {
        record(ServerEventName.RECORD_DELETE, memberId, request, "{\"recordId\":" + recordId + "}");
    }

    /** 스티커 전체 저장 성공. 빈 배열 저장은 0이다. */
    public void stickersSaved(Long memberId, Long jarPageId, int stickerCount, HttpServletRequest request) {
        record(ServerEventName.STICKER_SAVE, memberId, request,
                "{\"jarPageId\":" + jarPageId + ",\"stickerCount\":" + stickerCount + "}");
    }

    /** 온보딩 최초 완료. 반복 호출에는 부르지 않는다. */
    public void onboardingCompleted(Long memberId, HttpServletRequest request) {
        record(ServerEventName.ONBOARDING_COMPLETE, memberId, request, "{}");
    }

    /**
     * 컨트롤러가 recorder 호출을 감싸는 마지막 방어선. recorder 자체가 예상 밖으로 던져도 업무 응답으로 번지지 않게 한다.
     */
    public static void ignoringFailure(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("서버 행동 이벤트 기록 실패: type={}", e.getClass().getName());
        }
    }

    private void record(ServerEventName eventName, Long memberId, HttpServletRequest request, String properties) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                log.warn("서버 행동 이벤트 미저장(업무 트랜잭션 안에서 호출): event={}", eventName);
                return;
            }
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            AnalyticsEvent event = AnalyticsEvent.server(memberId, eventName, sessionId(request),
                    serviceVersion(request), os(request), now, properties);
            transactionTemplate.executeWithoutResult(status -> repository.save(event));
        } catch (RuntimeException e) {
            log.warn("서버 행동 이벤트 저장 실패: event={}, type={}", eventName, e.getClass().getName());
        }
    }

    private static String sessionId(HttpServletRequest request) {
        String value = singleHeader(request, SESSION_HEADER);
        return value != null && AnalyticsEventValidator.isSessionId(value) ? value.toLowerCase(Locale.ROOT) : null;
    }

    private static String serviceVersion(HttpServletRequest request) {
        String value = singleHeader(request, VERSION_HEADER);
        return value != null && AnalyticsEventValidator.isServiceVersion(value) ? value : null;
    }

    private static ClientOs os(HttpServletRequest request) {
        String value = singleHeader(request, OS_HEADER);
        return value != null ? AnalyticsEventValidator.clientOs(value) : null;
    }

    /** 헤더가 정확히 한 번 왔을 때만 그 값. 없거나 여러 번이면 null. */
    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }
}
