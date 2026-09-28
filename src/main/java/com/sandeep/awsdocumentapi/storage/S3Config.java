package com.sandeep.awsdocumentapi.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The two SDK clients, created only when the S3 adapter is selected.
 * <p>
 * Neither names a credential or a Region. Both come from the SDK's default provider chains:
 * on the laptop the {@code AWS_PROFILE} SSO session and {@code AWS_REGION}; on EC2 (Phase 4) the
 * instance role and the instance's Region — with no code change. Both clients are thread-safe
 * singletons; never create one per request.
 */
@Configuration
@ConditionalOnProperty(name = "documents.storage.type", havingValue = "s3")
class S3Config {

    @Bean
    S3Client s3Client() {
        return S3Client.create();
    }

    @Bean
    S3Presigner s3Presigner() {
        return S3Presigner.create();
    }
}
