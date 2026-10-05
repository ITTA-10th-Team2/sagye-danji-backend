package com.gyejoldanji.domain.image.client.s3;

import com.gyejoldanji.domain.image.client.model.PresignedUrl;
import com.gyejoldanji.global.infrastructure.s3.S3Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 네트워크 없이 로컬 서명만으로 Presigned URL의 만료·서명 헤더를 검증한다. */
class S3ImageStorageClientTest {

    private final S3Client s3Client = mock(S3Client.class);
    private S3Presigner presigner;
    private S3ImageStorageClient client;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build();
        S3Properties properties = new S3Properties();
        properties.setEnabled(true);
        properties.setBucket("test-bucket");
        properties.setRegion("ap-northeast-2");
        client = new S3ImageStorageClient(s3Client, presigner, properties);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void signsContentTypeAndLengthIntoFiveMinuteUploadUrl() {
        Instant before = Instant.now();

        PresignedUrl url = client.createUploadUrl("record-images/42/2026/10/a.jpg", "image/jpeg", 2048L);

        assertThat(url.url()).contains("test-bucket", "record-images/42/2026/10/a.jpg", "X-Amz-Expires=300");
        assertThat(url.expiresAt()).isBetween(before.plus(Duration.ofMinutes(5)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(5));
        assertThat(url.requiredHeaders())
                .containsEntry("content-type", "image/jpeg")
                .containsEntry("content-length", "2048")
                .doesNotContainKey("host");
    }

    @Test
    void signsOneHourViewUrl() {
        PresignedUrl url = client.createViewUrl("record-images/42/2026/10/a.jpg");

        assertThat(url.url()).contains("X-Amz-Expires=3600");
        assertThat(url.requiredHeaders()).isEmpty();
    }

    @Test
    void refusesPrefixThatCouldWipeBucket() {
        for (String prefix : new String[]{"", " ", "record-images/42", null}) {
            assertThatThrownBy(() -> client.deleteAllByPrefix(prefix)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(s3Client);
    }
}
