package com.sandeep.awsdocumentapi.document;

import java.net.URI;

/**
 * @param uploadUrl where the client must {@code PUT} the raw bytes. Locally this points back at this API;
 *                  on AWS it becomes a presigned S3 URL and the client never touches the API for the upload.
 */
public record DocumentCreatedResponse(DocumentResponse document, URI uploadUrl) {
}
