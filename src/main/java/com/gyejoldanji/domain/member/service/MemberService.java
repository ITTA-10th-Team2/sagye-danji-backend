package com.gyejoldanji.domain.member.service;

import java.util.Optional;

import com.gyejoldanji.domain.member.dto.MemberResponse;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 현재 회원 조회. 회원은 서버가 인증한 CurrentMember의 ID로만 고른다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    /**
     * 내 정보와 온보딩 상태. 세션 검사 뒤 회원이 사라졌으면 401 AUTH_003, DB 연결·자원·잠금·timeout은 503 COMMON_008이다.
     */
    public MemberResponse getMe(Long memberId) {
        Optional<Member> member;
        try {
            member = memberRepository.findById(memberId);
        } catch (RuntimeException e) {
            if (GlobalExceptionHandler.isDatabaseUnavailable(e)) {
                log.warn("내 정보 조회 실패(DB 일시 장애): type={}", e.getClass().getName());
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            throw e;
        }
        return member.map(MemberResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
    }
}
