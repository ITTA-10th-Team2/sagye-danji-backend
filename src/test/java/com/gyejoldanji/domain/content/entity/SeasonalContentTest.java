package com.gyejoldanji.domain.content.entity;

import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeasonalContentTest {

    @Test
    void rejectsBlankContentCode() {
        assertThatThrownBy(() -> SeasonalContent.create(
                " ", SeasonType.SPRING, ContentCategory.SCENERY,
                "벚꽃", "20260301", "20260430",
                OptimalPeriod.PEAK, "전국", "산림청", "20260201",
                "벚꽃 보기", "벚꽃을 감상해요."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("콘텐츠 코드는 비어 있을 수 없습니다.");
    }

    @Test
    void updatesAndChangesActiveState() {
        SeasonalContent content = SeasonalContent.create(
                "69cf3d37-b793-4f6c-a7f8-e46491fe00bf",
                SeasonType.SPRING, ContentCategory.SCENERY,
                "벚꽃", "20260301", "20260430",
                OptimalPeriod.PEAK, "전국", "산림청", "20260201",
                "벚꽃 보기", "벚꽃을 감상해요.");

        content.update(
                SeasonType.SUMMER, ContentCategory.ACTIVITY_LIFESTYLE,
                "물놀이", "20260701", "20260831",
                OptimalPeriod.PEAK, "전국", "기상청", "20260601",
                "물놀이", "시원한 물놀이를 즐겨요.");
        content.deactivate();

        assertThat(content.getSeason()).isEqualTo(SeasonType.SUMMER);
        assertThat(content.getCategory()).isEqualTo(ContentCategory.ACTIVITY_LIFESTYLE);
        assertThat(content.getTitle()).isEqualTo("물놀이");
        assertThat(content.isActive()).isFalse();

        content.activate();
        assertThat(content.isActive()).isTrue();
    }

    @Test
    void allowsNullableDateFields() {
        SeasonalContent content = SeasonalContent.create(
                "69cf3d37-b793-4f6c-a7f8-e46491fe00bf",
                SeasonType.SPRING, ContentCategory.SCENERY,
                "벚꽃", null, " ",
                OptimalPeriod.PEAK, "전국", "산림청", null,
                "벚꽃 보기", "벚꽃을 감상해요.");

        assertThat(content.getAvailableStartDate()).isNull();
        assertThat(content.getAvailableEndDate()).isNull();
        assertThat(content.getSourceCheckedDate()).isNull();
    }
}
