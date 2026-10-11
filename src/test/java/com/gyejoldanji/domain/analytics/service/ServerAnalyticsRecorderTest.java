package com.gyejoldanji.domain.analytics.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.analytics.enums.EventSource;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 서버 이벤트 조립·헤더별 검사·실패 격리를 DB 없이 확인한다. 실제 INSERT는 AnalyticsMySqlIntegrationTest에서 본다. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class ServerAnalyticsRecorderTest {

    /** 업무 서비스 반환 뒤 읽는 시각(나노초). 마이크로초로 자른다. */
    private static final Instant NOW = Instant.parse("2026-10-11T03:04:05.123456789Z");
    private static final LocalDateTime NOW_MICROS = LocalDateTime.parse("2026-10-11T03:04:05.123456");
    private static final String SESSION = "9B2E7C1D-4A3F-4E8B-8C6D-1F2A3B4C5D6E";
    private static final String VERSION = "1.2.3-베타";

    @Mock
    private AnalyticsEventRepository repository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ServerAnalyticsRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ServerAnalyticsRecorder(repository, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    /** 이벤트 4종의 이름·속성·출처·시각. 정상 헤더는 세션만 소문자로 바꾸고 버전·OS는 그대로다. */
    @Test
    void savesEachServerEventWithHeaders() {
        recorder.recordCompleted(42L, 101L, headers());
        assertSaved("RECORD_COMPLETE", "{\"recordId\":101,\"photoCount\":1}");

        recorder.recordDeleted(42L, 101L, headers());
        assertSaved("RECORD_DELETE", "{\"recordId\":101}");

        recorder.stickersSaved(42L, 31L, 0, headers());
        assertSaved("STICKER_SAVE", "{\"jarPageId\":31,\"stickerCount\":0}");

        recorder.onboardingCompleted(42L, headers());
        AnalyticsEvent onboarding = assertSaved("ONBOARDING_COMPLETE", "{}");
        assertThat(onboarding.getSessionId()).isEqualTo("9b2e7c1d-4a3f-4e8b-8c6d-1f2a3b4c5d6e");
        assertThat(onboarding.getServiceVersion()).isEqualTo(VERSION);
        assertThat(onboarding.getOs()).isEqualTo(ClientOs.ANDROID);
    }

    /** 세션 헤더가 없거나 비었거나 형식이 틀리거나 여러 개면 그 칸만 null이고 이벤트는 저장된다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "", "not-a-uuid", "multiple"})
    void dropsOnlyInvalidSessionHeader(String value) {
        recorder.onboardingCompleted(42L, headers(ServerAnalyticsRecorder.SESSION_HEADER, value));

        AnalyticsEvent saved = saved();
        assertThat(saved.getSessionId()).isNull();
        assertThat(saved.getServiceVersion()).isEqualTo(VERSION);
        assertThat(saved.getOs()).isEqualTo(ClientOs.ANDROID);
    }

    /** 버전 헤더는 빈 값·앞뒤 공백·제어문자·31자 이상·여러 개면 그 칸만 null이다. 자르거나 trim하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "", " 1.0.0", "1.0\t", "1234567890123456789012345678901", "multiple"})
    void dropsOnlyInvalidVersionHeader(String value) {
        recorder.onboardingCompleted(42L, headers(ServerAnalyticsRecorder.VERSION_HEADER, value));

        AnalyticsEvent saved = saved();
        assertThat(saved.getServiceVersion()).isNull();
        assertThat(saved.getSessionId()).isEqualTo("9b2e7c1d-4a3f-4e8b-8c6d-1f2a3b4c5d6e");
        assertThat(saved.getOs()).isEqualTo(ClientOs.ANDROID);
    }

    /** OS 헤더는 대소문자까지 정확히 일치해야 한다. 아니면 그 칸만 null이다. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "", "android", "LINUX", "multiple"})
    void dropsOnlyInvalidOsHeader(String value) {
        recorder.onboardingCompleted(42L, headers(ServerAnalyticsRecorder.OS_HEADER, value));

        AnalyticsEvent saved = saved();
        assertThat(saved.getOs()).isNull();
        assertThat(saved.getSessionId()).isEqualTo("9b2e7c1d-4a3f-4e8b-8c6d-1f2a3b4c5d6e");
        assertThat(saved.getServiceVersion()).isEqualTo(VERSION);
    }

    /** 저장·commit 실패는 밖으로 나오지 않고 이벤트 이름·예외 타입만 남긴다(헤더 값 없음). */
    @Test
    void swallowsSaveAndCommitFailuresWithoutHeaderValuesInLog(CapturedOutput output) {
        when(repository.save(any())).thenThrow(new CannotAcquireLockException("Deadlock SECRET-VALUE"));
        assertThatNoException().isThrownBy(() -> recorder.recordCompleted(42L, 101L, headers()));

        doAnswer(invocation -> invocation.getArgument(0)).when(repository).save(any());
        doThrow(new TransactionSystemException("commit failed SECRET-VALUE")).when(transactionManager).commit(any());
        assertThatNoException().isThrownBy(() -> recorder.stickersSaved(42L, 31L, 2, headers()));

        assertThat(output).contains("event=RECORD_COMPLETE", CannotAcquireLockException.class.getName(),
                        "event=STICKER_SAVE", TransactionSystemException.class.getName())
                .doesNotContain("SECRET-VALUE", SESSION, SESSION.toLowerCase(), VERSION);
    }

    /** 업무 트랜잭션 안에서 잘못 불리면 저장하지 않고 WARN만 남긴다. */
    @Test
    void skipsWhenCalledInsideActiveTransaction(CapturedOutput output) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            recorder.recordDeleted(42L, 101L, headers());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verifyNoInteractions(repository, transactionManager);
        assertThat(output).contains("event=RECORD_DELETE");
    }

    /** 컨트롤러 방어선은 recorder 호출이 던져도 삼킨다. */
    @Test
    void ignoringFailureSwallowsRuntimeExceptions() {
        assertThatNoException().isThrownBy(() -> ServerAnalyticsRecorder.ignoringFailure(() -> {
            throw new IllegalStateException("boom");
        }));
    }

    private AnalyticsEvent assertSaved(String eventName, String properties) {
        AnalyticsEvent event = saved();
        assertThat(event.getMemberId()).isEqualTo(42L);
        assertThat(event.getEventName()).isEqualTo(eventName);
        assertThat(event.getProperties()).isEqualTo(properties);
        assertThat(event.getEventSource()).isEqualTo(EventSource.SERVER);
        assertThat(event.getSequence()).isNull();
        assertThat(event.getEventId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(event.getOccurredAt()).isEqualTo(NOW_MICROS);
        assertThat(event.getReceivedAt()).isEqualTo(NOW_MICROS);
        return event;
    }

    /** 마지막 저장 이벤트. 저장은 분석 전용 트랜잭션 안에서 한 번 일어나고 commit된다. */
    private AnalyticsEvent saved() {
        ArgumentCaptor<AnalyticsEvent> captor = ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        verify(transactionManager, org.mockito.Mockito.atLeastOnce()).commit(any());
        verify(transactionManager, never()).rollback(any());
        return captor.getValue();
    }

    private static MockHttpServletRequest headers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ServerAnalyticsRecorder.SESSION_HEADER, SESSION);
        request.addHeader(ServerAnalyticsRecorder.VERSION_HEADER, VERSION);
        request.addHeader(ServerAnalyticsRecorder.OS_HEADER, "ANDROID");
        return request;
    }

    /** 정상 헤더 중 하나만 바꾼다. missing은 빼고, multiple은 같은 헤더를 두 번 보낸다. */
    private static MockHttpServletRequest headers(String name, String value) {
        MockHttpServletRequest request = headers();
        Consumer<MockHttpServletRequest> change = switch (value) {
            case "missing" -> r -> r.removeHeader(name);
            case "multiple" -> r -> r.addHeader(name, r.getHeader(name));
            default -> r -> {
                r.removeHeader(name);
                r.addHeader(name, value);
            };
        };
        change.accept(request);
        return request;
    }
}
