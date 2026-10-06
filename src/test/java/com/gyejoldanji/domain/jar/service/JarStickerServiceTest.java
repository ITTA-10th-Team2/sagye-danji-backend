package com.gyejoldanji.domain.jar.service;

import com.gyejoldanji.domain.jar.dto.JarStickerRequest;
import com.gyejoldanji.domain.jar.dto.JarStickerResponse;
import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.enums.StickerType;
import com.gyejoldanji.domain.jar.repository.JarPageRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JarStickerServiceTest {
    @Mock JarPageRepository pageRepository;
    @InjectMocks JarStickerService service;

    @Test
    void replacesOwnedPageStickersInZIndexOrder() {
        JarPage page = page();
        when(pageRepository.findByIdAndMemberId(31L, 42L)).thenReturn(Optional.of(page));
        when(pageRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JarStickerRequest request = new JarStickerRequest(List.of(
                item(StickerType.CLOVER, 0), item(StickerType.CAMERA, 1)));
        JarStickerResponse response = service.replace(42L, 31L, request);
        assertThat(response.items()).extracting(JarStickerResponse.Item::zIndex).containsExactly(0, 1);
        var order = inOrder(pageRepository);
        order.verify(pageRepository).flush();
        order.verify(pageRepository).saveAndFlush(page);
    }

    @Test
    void rejectsDuplicateZIndexBeforeDatabaseAccess() {
        JarStickerRequest request = new JarStickerRequest(List.of(
                item(StickerType.CLOVER, 0), item(StickerType.CAMERA, 0)));
        assertThatThrownBy(() -> service.replace(42L, 31L, request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(pageRepository);
    }

    private static JarPage page() {
        Member member = Member.create("TOSS", "member-42");
        ReflectionTestUtils.setField(member, "id", 42L);
        JarPage page = JarPage.create(member, 2026, SeasonType.AUTUMN, 0);
        ReflectionTestUtils.setField(page, "id", 31L);
        return page;
    }

    private static JarStickerRequest.Item item(StickerType type, int zIndex) {
        return new JarStickerRequest.Item(type, 0.5, 0.5, 1.0, 0.0, zIndex);
    }
}
