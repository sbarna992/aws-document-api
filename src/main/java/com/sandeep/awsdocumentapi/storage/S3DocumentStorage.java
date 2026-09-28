package com.sandeep.awsdocumentapi.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Document bytes in a private S3 bucket. Clients normally never come through {@link #store} or
 * {@link #load}: the API hands them presigned URLs and they talk to S3 directly. The two methods
 * still work (tests, and the local-style upload path) so the interface contract holds.
 * <p>
 * Every object key is {@code <prefix><storageKey>}: the database keeps the prefix-less key so rows
 * are identical whichever adapter is active (ADR 13, decision A).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "documents.storage.type", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3StorageProperties props;

    public S3DocumentStorage(S3Client s3, S3Presigner presigner, S3StorageProperties props) {
        if (props.bucket() == null || props.bucket().isBlank()) {
            throw new IllegalStateException(
                    "documents.storage.type=s3 but documents.storage.s3.bucket is not set (DOCAPI_BUCKET)");
        }
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
        log.info("S3 document storage: bucket {} prefix '{}' presign TTL {}", props.bucket(), props.prefix(), props.presignTtl());
    }

    /** The full S3 object key for a database storage key. */
    private String objectKey(String storageKey) {
        return props.prefix() + storageKey;
    }

    /**
     * S3 needs the content length up front and the interface hands us a bare stream, so the bytes are
     * spooled to a temp file first. Fine for the sizes this path sees (tests, local-style uploads);
     * real uploads go straight to S3 through the presigned URL and never touch this code.
     */
    @Override
    public long store(String storageKey, InputStream content) {
        Path temp = null;
        try {
            temp = Files.createTempFile("docapi-", ".upload");
            long size = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            s3.putObject(PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(objectKey(storageKey))
                            .build(),
                    RequestBody.fromFile(temp));
            return size;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to spool " + storageKey + " for upload", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    log.warn("Could not delete temp file {}", temp);
                }
            }
        }
    }

    @Override
    public Resource load(String storageKey) {
        try {
            ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(objectKey(storageKey))
                    .build());
            long length = in.response().contentLength();
            // InputStreamResource would otherwise drain the stream to answer contentLength(); S3 already told us.
            return new InputStreamResource(in) {
                @Override
                public long contentLength() {
                    return length;
                }
            };
        } catch (NoSuchKeyException e) {
            throw new StoredObjectNotFoundException(storageKey);
        }
    }

    /**
     * S3's delete is idempotent: deleting a key that does not exist succeeds, so the interface's
     * "no-op if absent" contract is met without any exception handling. On a versioned bucket this
     * adds a delete marker; the previous version remains (recoverable) until the lifecycle rule
     * expires it — nothing is physically removed here.
     */
    @Override
    public void delete(String storageKey) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.bucket())
                .key(objectKey(storageKey))
                .build());
    }

    /**
     * HeadObject fetches metadata only, no body. A 404 surfaces as {@link NoSuchKeyException} and
     * means "not found"; anything else (AccessDenied, throttling, a network fault) propagates — an
     * authorization failure must never be reported as a missing object.
     */
    @Override
    public boolean exists(String storageKey) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(objectKey(storageKey))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * The content type is set on the request being signed, which makes it a signed header: the client
     * must send exactly that value or S3 rejects the signature ({@code SignatureDoesNotMatch}).
     */
    @Override
    public Optional<URI> presignedUploadUrl(String storageKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(objectKey(storageKey))
                .contentType(contentType)
                .build();
        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(props.presignTtl())
                .putObjectRequest(put)
                .build();
        return Optional.of(toUri(presigner.presignPutObject(request).url()));
    }

    @Override
    public Optional<URI> presignedDownloadUrl(String storageKey) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(objectKey(storageKey))
                .build();
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(props.presignTtl())
                .getObjectRequest(get)
                .build();
        return Optional.of(toUri(presigner.presignGetObject(request).url()));
    }

    private static URI toUri(java.net.URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Presigner produced an invalid URL", e);
        }
    }
}
