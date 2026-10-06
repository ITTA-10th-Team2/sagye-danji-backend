package com.gyejoldanji.domain.recommendation.controller;

import com.gyejoldanji.domain.recommendation.dto.TodayRecommendationApiResponse;
import com.gyejoldanji.domain.recommendation.dto.TodayRecommendationResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 개인화 추천 API의 Swagger 성공·실패 계약과 응답 필드 문서를 검증한다. */
class RecommendationControllerSwaggerTest {

    @Test
    void documentsTodayRecommendationSuccessAndNotFoundResponses() throws Exception {
        Method method = RecommendationController.class.getDeclaredMethod("getTodayRecommendation");

        assertThat(method.getAnnotation(Operation.class)).isNotNull();
        ApiResponses responses = method.getAnnotation(ApiResponses.class);
        assertThat(responses).isNotNull();
        assertThat(responses.value()).extracting(response -> response.responseCode())
                .containsExactlyInAnyOrder("200", "404");

        Class<?> successSchema = Arrays.stream(responses.value())
                .filter(response -> response.responseCode().equals("200"))
                .findFirst()
                .map(io.swagger.v3.oas.annotations.responses.ApiResponse::content)
                .stream()
                .flatMap(Arrays::stream)
                .map(Content::schema)
                .map(Schema::implementation)
                .findFirst()
                .orElseThrow();

        assertThat(successSchema).isEqualTo(TodayRecommendationApiResponse.class);
        assertThat(Arrays.stream(TodayRecommendationApiResponse.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("success", "code", "message", "data");
    }

    @Test
    void documentsEveryRecommendationResponseField() {
        Set<String> documented = Arrays.stream(TodayRecommendationResponse.class.getRecordComponents())
                .filter(component -> component.getAccessor().getAnnotation(Schema.class) != null)
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(documented).containsExactlyInAnyOrder(
                "id", "contentCode", "category", "material", "title", "description", "stage", "region");
    }
}
