package com.gyejoldanji.domain.member.repository;

import com.gyejoldanji.domain.member.entity.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** 회원 조회·저장. ForUpdate 메서드는 트랜잭션 안에서만 호출한다. */
public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * 익명 인증 회원이 없으면 ACTIVE로 만들고, 있으면 아무것도 바꾸지 않는다.
     * 동시에 같은 anonKey로 들어와도 고유키가 한 행만 남긴다. native SQL이라 auditing 대신 시각을 직접 넣는다.
     */
    // ponytail: 이미 있는 회원도 매번 AUTO_INCREMENT 값을 하나 소모한다(ID 공백). 문제 되면 잠금 없는 조회로 존재 시 생략.
    @Modifying
    @Query(value = """
            INSERT INTO members (identity_provider, provider_user_id, status, created_at, updated_at)
            VALUES ('TOSS_ANON', :anonKey, 'ACTIVE', :now, :now)
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    void ensureIdentity(@Param("anonKey") String anonKey, @Param("now") LocalDateTime now);

    /** 익명 인증 회원을 행 잠금(FOR UPDATE)으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.identityProvider = 'TOSS_ANON' and m.providerUserId = :anonKey")
    Optional<Member> findIdentityForUpdate(@Param("anonKey") String anonKey);

    /** 회원을 ID로 행 잠금(FOR UPDATE) 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
