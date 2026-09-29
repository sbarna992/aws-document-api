package com.sandeep.awsdocumentapi.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against the REAL bucket, as whoever the default credentials provider chain finds (the SSO profile on
 * the laptop). Opt-in: skipped unless DOCAPI_S3_IT=true, so the default build stays offline.
 * <p>
 * Writes under {@code test/<run-id>/} — outside {@code documents/}, so the lifecycle rule and the event
 * notification (both scoped to {@code documents/}) ignore it — and removes every version and delete
 * marker it created. This is the no-Docker substitute for LocalStack, and it proves what an emulator
 * cannot: real IAM, the KMS key, the bucket policy.
 * <p>
 * Run: {@code aws sso login --profile document-api && DOCAPI_S3_IT=true ./mvnw verify}
 */
@EnabledIfEnvironmentVariable(named = "DOCAPI_S3_IT", matches = "true")
class S3DocumentStorageIntegrationTest {

    private static final String BUCKET = System.getenv().getOrDefault("DOCAPI_BUCKET", "docapi-documents-7fb3fd47");
    private static final String PREFIX = "test/" + UUID.randomUUID() + "/";

    private static S3Client s3;
    private static S3Presigner presigner;
    private static S3DocumentStorage storage;

    @BeforeAll
    static void connect() {
        s3 = S3Client.create();
        presigner = S3Presigner.create();
        storage = new S3DocumentStorage(s3, presigner, new S3StorageProperties(BUCKET, PREFIX, Duration.ofMinutes(1)));
    }

    @AfterAll
    static void removeEverythingWeCreated() {
        if (s3 == null) {
            return;
        }
        List<ObjectIdentifier> toDelete = new ArrayList<>();
        s3.listObjectVersionsPaginator(r -> r.bucket(BUCKET).prefix(PREFIX)).forEach(page -> {
            page.versions().forEach(v -> toDelete.add(ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
            page.deleteMarkers().forEach(m -> toDelete.add(ObjectIdentifier.builder().key(m.key()).versionId(m.versionId()).build()));
        });
        if (!toDelete.isEmpty()) {
            s3.deleteObjects(r -> r.bucket(BUCKET).delete(d -> d.objects(toDelete)));
        }
        presigner.close();
        s3.close();
    }

    @Test
    void storeExistsSizeLoadDeleteAgainstTheRealBucket() throws Exception {
        byte[] bytes = "integration test".getBytes(StandardCharsets.UTF_8);
        String key = UUID.randomUUID() + "-it.txt";

        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.sizeOf(key)).isEmpty();

        assertThat(storage.store(key, new ByteArrayInputStream(bytes))).isEqualTo(bytes.length);

        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.sizeOf(key)).contains((long) bytes.length);
        assertThat(storage.load(key).getContentAsByteArray()).isEqualTo(bytes);

        storage.delete(key);
        storage.delete(key); // idempotent: a second delete of a missing key succeeds

        assertThat(storage.exists(key)).isFalse();
        assertThatThrownBy(() -> storage.load(key)).isInstanceOf(StoredObjectNotFoundException.class);
    }
}
