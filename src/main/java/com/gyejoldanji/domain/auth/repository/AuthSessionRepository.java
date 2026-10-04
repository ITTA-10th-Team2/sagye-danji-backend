package com.gyejoldanji.domain.auth.repository;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 인증 세션 조회·저장. 인증 API의 ForUpdate 메서드는 회원을 먼저 잠근 트랜잭션 안에서만 호출한다.
 * 만료 이력 정리만 회원을 잠그지 않고 세션(PK 단건씩) → 토큰 순으로 잠근다(회원 잠금을 뒤에 잡지 않으므로 순서 역전이 없다).
 */
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

    /**
     * 만료 이력 정리 후보: cutoff보다 먼저 만료된 세션 ID를 PK 오름차순으로 최대 limit개 조회한다. cutoff와 같으면 후보가 아니다. 폐기
     * 여부·폐기 시각은 보지 않는다.
     *
     * <p>잠그지 않는다. 이 범위 조회에 FOR UPDATE를 붙이면 실행 계획(만료 인덱스 범위 스캔 + 정렬)에 따라 반환하지 않은 만료 세션과 범위
     * 끝의 다른 세션까지 잠근다. 정리는 같은 트랜잭션에서 후보를 {@link #findByIdForUpdate}로 하나씩 잠그고 다시 확인한다.
     *
     * <p>native 쿼리의 LocalDateTime 파라미터는 JVM 시간대를 거쳐 어긋나므로({@code MemberRepository.ensureIdentity} 참고) 절대 시점인
     * Instant로 받는다.
     */
    @Query(value = """
            SELECT id FROM auth_sessions
            WHERE expires_at < :cutoff
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findExpiredIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * 정리할 세션 한 행을 PK로 삭제하고 삭제 행 수를 반환한다. 그 세션을 잠그고 Refresh 이력을 먼저 지운 정리 트랜잭션에서만 호출한다(FK
     * RESTRICT). IN 목록 삭제는 실행 계획에 따라 전체 스캔으로 다른 세션까지 잠글 수 있어 한 행씩 지운다.
     */
    @Modifying
    @Query(value = "DELETE FROM auth_sessions WHERE id = :id", nativeQuery = true)
    int deleteRowById(@Param("id") Long id);
}
