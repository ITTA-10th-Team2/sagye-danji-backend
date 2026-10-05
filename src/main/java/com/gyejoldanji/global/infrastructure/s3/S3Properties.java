package com.gyejoldanji.global.infrastructure.s3;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** 기록 이미지 저장소(S3)의 활성화 여부와 버킷·리전 설정을 관리한다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.s3")
public class S3Properties {

    /** S3 연동 활성화 여부. false면 메모리 기반 Fake 저장소를 쓴다. */
    private boolean enabled;

    /** 이미지 버킷 이름. */
    private String bucket;

    /** 버킷 리전. */
    private String region;

    /** 연동 활성화 시 버킷과 리전이 설정되었는지 검증한다. */
    @AssertTrue(message = "S3 연동이 활성화되면 bucket과 region이 필요합니다.")
    public boolean isBucketConfigured() {
        return !enabled || (StringUtils.hasText(bucket) && StringUtils.hasText(region));
    }
}
