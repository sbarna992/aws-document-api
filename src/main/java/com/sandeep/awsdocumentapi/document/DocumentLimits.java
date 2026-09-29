package com.sandeep.awsdocumentapi.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param maxFileSize the largest document accepted. On S3 it is enforced after the upload, by the
 *                    confirm endpoint (presigned PUT cannot make S3 enforce it — ADR 13, decision C);
 *                    on the local adapter the API sees the bytes and refuses them directly.
 */
@ConfigurationProperties(prefix = "documents")
public record DocumentLimits(@DefaultValue("10MB") DataSize maxFileSize) {
}
