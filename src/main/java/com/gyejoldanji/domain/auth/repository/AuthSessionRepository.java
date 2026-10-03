package com.gyejoldanji.domain.auth.repository;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** 인증 세션 조회·저장. ForUpdate 메서드는 회원을 먼저 잠근 트랜잭션 안에서만 호출한다. */
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    /** 보호 요청 검사용 읽기 전용 값. 엔티티를 올리지 않고 필요한 컬럼만 가져온다. */
    interface AuthenticationView {
        /** 세션 내부 식별자. */
        Long getSessionId();

        /** 세션 소유 회원 식별자. */
        Long getMemberId();

        /** 조회 시점의 회원 상태. */
        MemberStatus getMemberStatus();

        /** 세션 절대 만료 시각(UTC). */
        LocalDateTime getExpiresAt();

        /** 세션 폐기 시각(UTC)이며 미폐기는 null. */
        LocalDateTime getRevokedAt();
    }

    /** JWT의 sid·sub가 가리키는 세션과 소유 회원 상태를 함께 조회한다. 소유자가 다르면 비어 있다. */
    @Query("""
            select s.id as sessionId, m.id as memberId, m.status as memberStatus,
                   s.expiresAt as expiresAt, s.revokedAt as revokedAt
            from AuthSession s join s.member m
            where s.sessionKey = :sessionKey and m.id = :memberId
            """)
    Optional<AuthenticationView> findAuthenticationView(@Param("sessionKey") String sessionKey,
                                                        @Param("memberId") Long memberId);

    /** 세션을 ID로 행 잠금(FOR UPDATE) 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") Long id);
}
