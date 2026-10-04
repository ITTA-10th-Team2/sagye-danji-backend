package com.gyejoldanji.domain.auth.entity;

import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 회원의 로그인 세션. 생성 시 정한 절대 만료 시각은 갱신으로 연장하지 않는다. */
@Entity
@Table(name = "auth_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_auth_sessions_key", columnNames = "session_key"),
        indexes = {
                @Index(name = "ix_auth_sessions_member", columnList = "member_id, revoked_at, expires_at"),
                @Index(name = "ix_auth_sessions_expiry", columnList = "expires_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthSession extends BaseTimeEntity {

    /** 서비스 내부에서 사용하는 세션 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 세션 소유 회원이며 세션이 남아 있으면 회원을 삭제할 수 없다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_auth_sessions_member"))
    private Member member;

    /** JWT sid로 쓰는 소문자 UUID 문자열. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_key", nullable = false, updatable = false, length = 36,
            columnDefinition = "char(36) character set ascii collate ascii_bin")
    private String sessionKey;

    /** 현재 유효한 Refresh 토큰 세대. 최초 0이며 갱신마다 1씩 증가한다. */
    @Column(name = "current_refresh_generation", nullable = false)
    private int currentRefreshGeneration;

    /** UTC 기준 절대 만료 시각. */
    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "timestamp(6)")
    private LocalDateTime expiresAt;

    /** UTC 기준 폐기 시각이며 미폐기는 null. */
    @Column(name = "revoked_at", columnDefinition = "timestamp(6)")
    private LocalDateTime revokedAt;

    /** 폐기 사유이며 미폐기는 null. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "revoke_reason", length = 32)
    private SessionRevokeReason revokeReason;

    /** 지금부터 유효 기간만큼 살아 있는 신규 세션을 시작한다. */
    public static AuthSession start(Member member, UUID sessionKey, LocalDateTime now, Duration ttl) {
        if (!ttl.isPositive()) {
            throw new IllegalArgumentException("세션 유효 기간은 0보다 커야 합니다.");
        }
        AuthSession session = new AuthSession();
        session.member = Objects.requireNonNull(member, "member");
        session.sessionKey = sessionKey.toString();
        session.expiresAt = now.plus(ttl);
        return session;
    }

    /** Refresh 토큰 세대를 다음 값으로 올린다. */
    public void advanceGeneration() {
        currentRefreshGeneration++;
    }

    /** 세션을 폐기한다. 이미 폐기된 세션은 최초 시각·사유를 유지한다. */
    public void revoke(SessionRevokeReason reason, LocalDateTime now) {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(now);
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }
}
