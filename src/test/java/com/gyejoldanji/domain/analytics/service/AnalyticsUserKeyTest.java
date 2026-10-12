package com.gyejoldanji.domain.analytics.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AnalyticsUserKeyTest {

    private static final String SECRET = "sheet-sync-test-secret-0123456789";

    @Test
    void generatesStableSixteenLowercaseHexCharactersFromHmacSha256() {
        String key = AnalyticsUserKey.generate(SECRET, 42L);

        assertThat(key).isEqualTo("e696ad3cca79513b")
                .isEqualTo(AnalyticsUserKey.generate(SECRET, 42L))
                .matches("[0-9a-f]{16}")
                .doesNotContain("42");
    }

    @Test
    void changingSecretOrMemberChangesTheKey() {
        String key = AnalyticsUserKey.generate(SECRET, 42L);

        assertThat(AnalyticsUserKey.generate(SECRET + "-rotated", 42L)).isNotEqualTo(key);
        assertThat(AnalyticsUserKey.generate(SECRET, 43L)).isNotEqualTo(key);
    }

    @Test
    void usesUtf8ForTheSecret() {
        assertThat(AnalyticsUserKey.generate("분석-테스트-비밀-키-012345678901234567890123456789", 42L))
                .isEqualTo("5490770711f529fb");
    }

    @Test
    void rejectsMissingSecretOrMember() {
        assertThatNullPointerException().isThrownBy(() -> AnalyticsUserKey.generate(null, 42L));
        assertThatNullPointerException().isThrownBy(() -> AnalyticsUserKey.generate(SECRET, null));
    }
}
