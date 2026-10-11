package com.gyejoldanji.domain.analytics.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.gyejoldanji.domain.analytics.dto.AnalyticsBatchResponse;
import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 프론트 행동 이벤트 배치 수집. 같은 회원의 이미 저장된 eventId는 저장하지 않고 중복으로 센다. */
@Service
public class AnalyticsService {

    private final AnalyticsEventValidator validator;
    private final MemberRepository memberRepository;
    private final AnalyticsEventRepository analyticsEventRepository;
    /** 분석 전용 READ COMMITTED 템플릿. 공유 TransactionTemplate 설정은 바꾸지 않는다. */
    private final TransactionTemplate transactionTemplate;

    public AnalyticsService(AnalyticsEventValidator validator, MemberRepository memberRepository,
                            AnalyticsEventRepository analyticsEventRepository,
                            PlatformTransactionManager transactionManager) {
        this.validator = validator;
        this.memberRepository = memberRepository;
        this.analyticsEventRepository = analyticsEventRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 검증 뒤 한 트랜잭션에서 저장한다. 반환 시점에는 commit이 끝나 있고 두 개수의 합은 요청 건수와 같다. 트랜잭션(commit 포함) 실패는
     * 503 COMMON_008 또는 500 COMMON_003이며 이미 분류된 {@link BusinessException}은 그대로 둔다.
     */
    public AnalyticsBatchResponse collect(Long memberId, byte[] body) {
        List<AnalyticsEvent> events = validator.parse(memberId, body);
        try {
            return transactionTemplate.execute(status -> save(memberId, events));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw GlobalExceptionHandler.translateTransactionFailure("행동 이벤트 수집", e);
        }
    }

    /**
     * 회원 행 잠금으로 같은 회원의 동시 배치를 줄 세운 뒤, 이미 있는 eventId를 일반 SELECT로 찾아 없는 것만 저장한다. 이미 있는 행은 내용이
     * 달라도 고치지 않는다.
     */
    private AnalyticsBatchResponse save(Long memberId, List<AnalyticsEvent> events) {
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        Set<String> existing = new HashSet<>(analyticsEventRepository.findExistingEventIds(
                memberId, events.stream().map(AnalyticsEvent::getEventId).toList()));
        List<AnalyticsEvent> accepted = events.stream().filter(event -> !existing.contains(event.getEventId())).toList();
        analyticsEventRepository.saveAll(accepted);
        return new AnalyticsBatchResponse(accepted.size(), events.size() - accepted.size());
    }
}
