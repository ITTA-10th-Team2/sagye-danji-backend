package com.gyejoldanji.domain.auth.entity;

import com.gyejoldanji.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

/** 세션에서 발급한 Refresh 토큰 이력. 원문 없이 해시만 저장하며 사용한 행도 재사용 탐지를 위해 남긴다. */
@Entity
@Table(name = "auth_refresh_tokens", uniqueConstraints = {
        @UniqueConstraint(name = "uk_auth_refresh_hash", columnNames = "token_hash"),
        @UniqueConstraint(name = "uk_auth_refresh_generation", columnNames = {"session_id", "generation"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthRefreshToken extends BaseTimeEntity {

    /** 서비스 내부에서 사용하는 토큰 이력 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 토큰을 발급한 세션이며 이력이 남아 있으면 세션을 삭제할 수 없다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_auth_refresh_session"))
    private AuthSession session;

    /** Refresh 원문의 SHA-256 해시. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 32, columnDefinition = "binary(32)")
    private byte[] tokenHash;

    /** 발급 당시 세션의 Refresh 세대. */
    @Column(name = "generation", nullable = false, updatable = false)
    private int generation;

    /** 소속 세션과 같은 UTC 절대 만료 시각. */
    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "timestamp(6)")
    private LocalDateTime expiresAt;

    /** UTC 기준 갱신에 사용한 시각이며 미사용은 null. */
    @Column(name = "consumed_at", columnDefinition = "timestamp(6)")
    private LocalDateTime consumedAt;

    /** 세션의 현재 세대와 만료 시각으로 새 토큰 이력을 발급한다. */
    public static AuthRefreshToken issue(AuthSession session, byte[] tokenHash) {
        if (tokenHash.length != 32) {
            throw new IllegalArgumentException("Refresh 토큰 해시는 32바이트여야 합니다.");
        }
        AuthRefreshToken token = new AuthRefreshToken();
        token.session = Objects.requireNonNull(session, "session");
        token.tokenHash = tokenHash;
        token.generation = session.getCurrentRefreshGeneration();
        token.expiresAt = session.getExpiresAt();
        return token;
    }

    /** 갱신에 사용한 것으로 표시한다. 이미 사용한 토큰은 최초 시각을 유지한다. */
    public void consume(LocalDateTime now) {
        Objects.requireNonNull(now);
        if (consumedAt == null) {
            consumedAt = now;
        }
    }
}
