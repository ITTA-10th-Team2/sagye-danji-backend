package com.gyejoldanji.domain.jar.service;

import com.gyejoldanji.domain.jar.dto.JarStickerRequest;
import com.gyejoldanji.domain.jar.dto.JarStickerResponse;
import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.entity.JarSticker;
import com.gyejoldanji.domain.jar.repository.JarPageRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

/** 인증 회원의 연도·계절별 단지 스티커 스냅샷을 조회하고 전체 교체한다. */
@Service
@RequiredArgsConstructor
public class JarStickerService {
    private final JarPageRepository pageRepository;

    /** 소유한 단지 페이지의 저장 배치를 반환한다. */
    @Transactional(readOnly = true)
    public JarStickerResponse find(Long memberId, Long jarPageId) {
        return JarStickerResponse.from(findOwnedPage(memberId, jarPageId));
    }

    /** 전달된 최종 화면 상태로 기존 배치를 멱등하게 전체 교체한다. */
    @Transactional
    public JarStickerResponse replace(Long memberId, Long jarPageId, JarStickerRequest request) {
        validateItems(request.items());
        JarPage page = findOwnedPage(memberId, jarPageId);
        List<JarSticker.Spec> specs = request.items().stream()
                .map(item -> new JarSticker.Spec(item.stickerType(), item.xRatio(), item.yRatio(),
                        item.scale(), item.rotation(), item.zIndex()))
                .sorted(Comparator.comparingInt(JarSticker.Spec::zIndex))
                .toList();
        // 같은 (jar_page_id, z_index)를 재사용할 때 신규 INSERT가 orphan DELETE보다 먼저
        // 실행되면 유니크 제약이 충돌한다. 기존 행의 삭제를 먼저 확정한 뒤 새 배치를 저장한다.
        page.clearStickers();
        pageRepository.flush();
        page.addStickers(specs);
        return JarStickerResponse.from(pageRepository.saveAndFlush(page));
    }

    /** 단지 페이지는 유지하고 해당 페이지의 스티커만 모두 삭제한다. */
    @Transactional
    public void delete(Long memberId, Long jarPageId) {
        JarPage page = findOwnedPage(memberId, jarPageId);
        page.replaceStickers(List.of());
    }

    private JarPage findOwnedPage(Long memberId, Long jarPageId) {
        if (memberId == null || memberId <= 0) throw new BusinessException(ErrorCode.AUTH_SESSION_INVALID);
        if (jarPageId == null || jarPageId <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return pageRepository.findByIdAndMemberId(jarPageId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.JAR_PAGE_NOT_FOUND));
    }

    private static void validateItems(List<JarStickerRequest.Item> items) {
        if (items == null || items.size() > 20) throw new BusinessException(ErrorCode.INVALID_INPUT);
        Set<Integer> indexes = new HashSet<>();
        for (JarStickerRequest.Item item : items) {
            if (item == null || !indexes.add(item.zIndex())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
    }
}
