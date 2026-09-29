package com.sandeep.awsdocumentapi.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline: signing is pure local computation, so presigned URLs can be tested with no network and no
 * AWS account. The credentials below are deliberately fake and passed explicitly — the production
 * beans pass none and let the SDK's default provider chain decide. The S3Client is built but never
 * called.
 */
class S3DocumentStorageTest {

    private static final StaticCredentialsProvider FAKE =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATESTONLY", "test-secret"));

    private S3Client s3;
    private S3Presigner presigner;
    private S3DocumentStorage storage;

    @BeforeEach
    void setUp() {
        s3 = S3Client.builder().region(Region.US_EAST_1).credentialsProvider(FAKE).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).credentialsProvider(FAKE).build();
        storage = new S3DocumentStorage(s3, presigner,
                new S3StorageProperties("test-bucket", "documents/", Duration.ofMinutes(15)));
    }

    @AfterEach
    void tearDown() {
        presigner.close();
        s3.close();
    }

    @Test
    void uploadUrlTargetsTheBucketUnderThePrefixAndPinsContentType() {
        URI url = storage.presignedUploadUrl("abc-hello.txt", "text/plain").orElseThrow();

        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).startsWith("test-bucket.s3");
        assertThat(url.getPath()).isEqualTo("/documents/abc-hello.txt");
        assertThat(url.getRawQuery())
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-SignedHeaders=content-type%3Bhost")
                .contains("X-Amz-Credential=AKIATESTONLY");
    }

    @Test
    void downloadUrlSignsOnlyTheHost() {
        URI url = storage.presignedDownloadUrl("abc-hello.txt").orElseThrow();

        assertThat(url.getPath()).isEqualTo("/documents/abc-hello.txt");
        assertThat(url.getRawQuery())
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-SignedHeaders=host");
    }

    @Test
    void ttlComesFromConfiguration() {
        S3DocumentStorage shortLived = new S3DocumentStorage(s3, presigner,
                new S3StorageProperties("test-bucket", "documents/", Duration.ofSeconds(10)));

        assertThat(shortLived.presignedUploadUrl("k", "text/plain").orElseThrow().getRawQuery())
                .contains("X-Amz-Expires=10");
    }
}
