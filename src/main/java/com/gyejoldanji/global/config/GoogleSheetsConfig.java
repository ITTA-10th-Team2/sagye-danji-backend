package com.gyejoldanji.global.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsClientProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;

/** 서비스 계정으로 인증된 Google Sheets SDK 객체를 구성한다. */
@Configuration
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class GoogleSheetsConfig {

    /** 콘텐츠 동기화에서 사용할 UTC 기준 시계를 생성한다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 읽기·쓰기가 가능한 Google Sheets SDK 객체를 생성한다. */
    @Bean
    public Sheets googleSheets(GoogleSheetsClientProperties properties)
            throws IOException, GeneralSecurityException {
        Path credentialsPath = Path.of(properties.getCredentialsPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(credentialsPath)) {
            throw new IllegalStateException("Google 서비스 계정 파일을 찾을 수 없습니다: " + credentialsPath);
        }

        GoogleCredentials credentials;
        try (InputStream inputStream = Files.newInputStream(credentialsPath)) {
            credentials = GoogleCredentials.fromStream(inputStream)
                    .createScoped(SheetsScopes.SPREADSHEETS);
        }

        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("GYEJOL-DANJI")
                .build();
    }
}
