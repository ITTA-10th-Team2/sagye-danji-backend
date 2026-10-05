package com.gyejoldanji.global.config;

import com.gyejoldanji.global.infrastructure.s3.S3Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 기본 자격 증명 체인(EC2 IAM 역할 등)으로 인증된 S3 SDK 객체를 구성한다. 접근 키는 설정 파일에 두지 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.s3", name = "enabled", havingValue = "true")
public class S3Config {

    /** 애플리케이션 전체에서 공유하는 자격 증명 제공자. 컨텍스트 종료 시 닫는다. */
    @Bean(destroyMethod = "close")
    public DefaultCredentialsProvider s3CredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    /** 객체 조회·삭제용 S3 클라이언트. */
    @Bean(destroyMethod = "close")
    public S3Client s3Client(S3Properties properties, DefaultCredentialsProvider s3CredentialsProvider) {
        return S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(s3CredentialsProvider)
                .build();
    }

    /** Presigned URL 서명기. 네트워크 호출 없이 로컬에서 서명한다. */
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(S3Properties properties, DefaultCredentialsProvider s3CredentialsProvider) {
        return S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(s3CredentialsProvider)
                .build();
    }
}
