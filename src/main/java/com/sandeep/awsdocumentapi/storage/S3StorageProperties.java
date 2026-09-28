package com.sandeep.awsdocumentapi.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param bucket     the document bucket; required when {@code documents.storage.type=s3}
 * @param prefix     object-key prefix; IAM policy, lifecycle rule and event filters are all scoped to it
 * @param presignTtl how long a presigned URL stays valid (also bounded by the signer's credential lifetime)
 */
@ConfigurationProperties(prefix = "documents.storage.s3")
public record S3StorageProperties(
        String bucket,
        @DefaultValue("documents/") String prefix,
        @DefaultValue("PT15M") Duration presignTtl) {
}
