package com.gyejoldanji.domain.analytics.entity;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import com.gyejoldanji.domain.analytics.enums.ClientEventName;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.analytics.enums.EventSource;
import com.gyejoldanji.domain.analytics.enums.ServerEventName;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 행동 이벤트 원본 한 건. 컬럼·제약·인덱스는 {@code docs/db/migrations/06-analytics-events.sql}과 같다. 회원 FK는 두지 않는다.
 *
 * <p>시각은 모두 UTC 마이크로초다. 저장 뒤에는 바꾸지 않는다.
 */
@Entity
@Table(name = "analytics_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_analytics_member_event", columnNames = {"member_id", "event_id"}),
        indexes = {
                @Index(name = "idx_analytics_member_time", columnList = "member_id, occurred_at, id"),
                @Index(name = "idx_analytics_session_time", columnList = "session_id, occurred_at, sequence"),
                @Index(name = "idx_analytics_name_time", columnList = "event_name, occurred_at"),
                @Index(name = "idx_analytics_sheet_pending", columnList = "sheet_synced_at, id")
        },
        check = {
                @CheckConstraint(name = "ck_analytics_source", constraint = "event_source IN ('CLIENT', 'SERVER')"),
                @CheckConstraint(name = "ck_analytics_os",
                        constraint = "os IS NULL OR os IN ('IOS', 'ANDROID', 'WEB', 'OTHER')"),
                @CheckConstraint(name = "ck_analytics_properties", constraint = "JSON_TYPE(properties) = 'OBJECT'")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 소문자 UUID. 같은 회원 안에서 고유하다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, updatable = false, length = 36,
            columnDefinition = "char(36) character set ascii collate ascii_bin")
    private String eventId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    /** 프론트 분석 세션의 소문자 UUID. 인증 세션과 무관하며 서버 이벤트는 null일 수 있다. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", updatable = false, length = 36,
            columnDefinition = "char(36) character set ascii collate ascii_bin")
    private String sessionId;

    @Column(name = "event_name", nullable = false, updatable = false, length = 40)
    private String eventName;

    @Column(name = "occurred_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime receivedAt;

    @Column(name = "service_version", updatable = false, length = 30)
    private String serviceVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "os", updatable = false, length = 10)
    private ClientOs os;

    @Column(name = "sequence", updatable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_source", nullable = false, updatable = false, length = 10)
    private EventSource eventSource;

    /** 검증된 속성 객체의 compact JSON. 속성이 없으면 {@code {}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties", nullable = false, updatable = false)
    private String properties;

    /** 시트 동기화 시각. 이번 범위에서는 쓰지 않는다. */
    @Column(name = "sheet_synced_at", columnDefinition = "datetime(6)")
    private LocalDateTime sheetSyncedAt;

    /** 수집 API가 검증을 마친 프론트 이벤트. */
    public static AnalyticsEvent client(Long memberId, String eventId, String sessionId, ClientEventName eventName,
                                        LocalDateTime occurredAt, LocalDateTime receivedAt, String serviceVersion,
                                        ClientOs os, Integer sequence, String properties) {
        AnalyticsEvent event = new AnalyticsEvent();
        event.memberId = Objects.requireNonNull(memberId, "memberId");
        event.eventId = Objects.requireNonNull(eventId, "eventId");
        event.sessionId = sessionId;
        event.eventName = eventName.name();
        event.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        event.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        event.serviceVersion = serviceVersion;
        event.os = os;
        event.sequence = sequence;
        event.eventSource = EventSource.CLIENT;
        event.properties = Objects.requireNonNull(properties, "properties");
        return event;
    }

    /** 업무 처리 뒤 서버가 남기는 이벤트. eventId는 새 UUID이고 발생·수신 시각이 같으며 sequence는 없다. */
    public static AnalyticsEvent server(Long memberId, ServerEventName eventName, String sessionId,
                                        String serviceVersion, ClientOs os, LocalDateTime now, String properties) {
        AnalyticsEvent event = new AnalyticsEvent();
        event.memberId = Objects.requireNonNull(memberId, "memberId");
        event.eventId = UUID.randomUUID().toString();
        event.sessionId = sessionId;
        event.eventName = eventName.name();
        event.occurredAt = Objects.requireNonNull(now, "now");
        event.receivedAt = now;
        event.serviceVersion = serviceVersion;
        event.os = os;
        event.eventSource = EventSource.SERVER;
        event.properties = Objects.requireNonNull(properties, "properties");
        return event;
    }
}
