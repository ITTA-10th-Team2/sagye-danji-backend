package com.gyejoldanji.global.infrastructure.google;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** Google Sheets 연동에 공통으로 사용하는 활성화 및 인증 설정을 관리한다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.google-sheets")
public class GoogleSheetsClientProperties {

    /** Google Sheets 연동 활성화 여부. */
    private boolean enabled;

    /** 서비스 계정 JSON 파일 경로. */
    private String credentialsPath;

    /** 연동 활성화 시 인증 파일 경로가 설정되었는지 검증한다. */
    @AssertTrue(message = "Google Sheets 연동이 활성화되면 credentials-path가 필요합니다.")
    public boolean isCredentialsPathConfigured() {
        return !enabled || StringUtils.hasText(credentialsPath);
    }
}
