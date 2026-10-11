package com.gyejoldanji.domain.analytics.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.gyejoldanji.domain.analytics.dto.AnalyticsBatchResponse;
import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ClientEventName;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 회원 잠금 → 기존 eventId 잠금 조회 → 신규만 저장 순서와 accepted·duplicate 개수를 DB 없이 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsServiceTest {

    private static final byte[] BODY = "{}".getBytes();
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-11T03:00:00.123456");
    private static final List<String> IDS = List.of(
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000003");

    @Mock
    private AnalyticsEventValidator validator;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private AnalyticsEventRepository analyticsEventRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AnalyticsService service;
    private List<AnalyticsEvent> events;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(validator, memberRepository, analyticsEventRepository, transactionManager);
        events = IDS.stream().map(id -> AnalyticsEvent.client(42L, id, IDS.getFirst(), ClientEventName.JAR_VIEW,
                NOW, NOW, "1.0.0", ClientOs.IOS, 1, "{}")).toList();
        when(validator.parse(42L, BODY)).thenReturn(events);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(mock(Member.class)));
    }

    /**
     * 신규 3건 → 3/0, 전부 재전송 → 0/3, 일부 중복 → 2/1. READ COMMITTED 트랜잭션에서 회원 잠금 → 기존 키 조회 → 신규만 저장 → commit
     * 순서다.
     */
    @ParameterizedTest
    @CsvSource({"0, 3, 0", "3, 0, 3", "1, 2, 1"})
    @SuppressWarnings("unchecked")
    void savesOnlyNewEventsAfterLocks(int existingCount, int accepted, int duplicate) {
        List<String> existing = IDS.subList(0, existingCount);
        when(analyticsEventRepository.findExistingEventIds(42L, IDS)).thenReturn(existing);

        AnalyticsBatchResponse response = service.collect(42L, BODY);

        assertThat(response).isEqualTo(new AnalyticsBatchResponse(accepted, duplicate));
        InOrder order = inOrder(transactionManager, memberRepository, analyticsEventRepository);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        order.verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        order.verify(memberRepository).findByIdForUpdate(42L);
        order.verify(analyticsEventRepository).findExistingEventIds(42L, IDS);
        ArgumentCaptor<List<AnalyticsEvent>> saved = ArgumentCaptor.forClass(List.class);
        order.verify(analyticsEventRepository).saveAll(saved.capture());
        order.verify(transactionManager).commit(any());
        assertThat(saved.getValue()).extracting(AnalyticsEvent::getEventId)
                .containsExactlyElementsOf(IDS.subList(existingCount, IDS.size()));
    }

    /** 회원 행이 없으면 401 AUTH_003이고 아무것도 저장하지 않는다. */
    @Test
    void rejectsMissingMemberWithoutSaving() {
        when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collect(42L, BODY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_SESSION_INVALID));
        verify(analyticsEventRepository, never()).saveAll(any());
    }
}
