package com.sandeep.awsdocumentapi.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /documents}: the client tells us what it is about to upload and gets an upload URL back.
 */
public record CreateDocumentRequest(
        @NotBlank @Size(max = 255) String filename,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[\\w.+-]+/[\\w.+-]+$", message = "must be a media type such as application/pdf")
        String contentType) {
}
