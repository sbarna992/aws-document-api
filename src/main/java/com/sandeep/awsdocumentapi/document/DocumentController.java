package com.sandeep.awsdocumentapi.document;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    /**
     * Stand-in for real authentication. The caller simply declares who they are; on AWS this
     * becomes an identity established by API Gateway / Cognito and the header goes away.
     */
    static final String OWNER_HEADER = "X-User-Id";

    private final DocumentService service;

    @PostMapping
    public ResponseEntity<DocumentCreatedResponse> create(@RequestHeader(OWNER_HEADER) String ownerId,
                                                          @Valid @RequestBody CreateDocumentRequest request) {
        Document document = service.create(ownerId, request);
        URI location = currentRequest().path("/{id}").buildAndExpand(document.getId()).toUri();
        // Presigned S3 URL when storage can take the bytes directly; otherwise this API's own upload endpoint.
        URI uploadUrl = service.uploadUrl(document)
                .orElseGet(() -> currentRequest().path("/{id}/content").buildAndExpand(document.getId()).toUri());
        return ResponseEntity.created(location)
                .body(new DocumentCreatedResponse(DocumentResponse.from(document), uploadUrl));
    }

    /** Raw bytes in the body, exactly as a presigned S3 PUT would receive them. */
    @PutMapping("/{id}/content")
    public DocumentResponse upload(@RequestHeader(OWNER_HEADER) String ownerId,
                                   @PathVariable UUID id,
                                   InputStream body) {
        return DocumentResponse.from(service.storeContent(ownerId, id, body));
    }

    @GetMapping
    public List<DocumentResponse> list(@RequestHeader(OWNER_HEADER) String ownerId) {
        return service.list(ownerId).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public DocumentDetailResponse get(@RequestHeader(OWNER_HEADER) String ownerId, @PathVariable UUID id) {
        Document document = service.get(ownerId, id);
        URI downloadUrl = document.getStatus() == DocumentStatus.PENDING_UPLOAD
                ? null
                : service.downloadUrl(document)
                        .orElseGet(() -> currentRequest().path("/content").build().toUri());
        return new DocumentDetailResponse(DocumentResponse.from(document), downloadUrl);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> download(@RequestHeader(OWNER_HEADER) String ownerId, @PathVariable UUID id) {
        DocumentService.DocumentContent content = service.loadContent(ownerId, id);
        Document document = content.document();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.getOriginalFilename(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if (document.getFileSize() != null) {
            response.contentLength(document.getFileSize());
        }
        return response.body(content.resource());
    }

    /** Accepted, not done: processing runs asynchronously. Poll GET /documents/{id} for the outcome. */
    @PostMapping("/{id}/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentResponse process(@RequestHeader(OWNER_HEADER) String ownerId, @PathVariable UUID id) {
        return DocumentResponse.from(service.requestProcessing(ownerId, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(OWNER_HEADER) String ownerId, @PathVariable UUID id) {
        service.delete(ownerId, id);
    }

    private static ServletUriComponentsBuilder currentRequest() {
        return ServletUriComponentsBuilder.fromCurrentRequest();
    }
}
