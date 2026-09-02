package com.sandeep.awsdocumentapi.document;

import java.net.URI;

/**
 * @param downloadUrl where the bytes can be fetched from, or {@code null} while the upload is still pending.
 *                    Locally this points back at this API; on AWS it becomes a presigned S3 URL.
 */
public record DocumentDetailResponse(DocumentResponse document, URI downloadUrl) {
}
