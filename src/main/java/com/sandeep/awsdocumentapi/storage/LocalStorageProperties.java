package com.sandeep.awsdocumentapi.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param root directory under which document bytes are written; created on startup if missing
 */
@Validated
@ConfigurationProperties(prefix = "documents.storage.local")
public record LocalStorageProperties(@NotBlank String root) {
}
