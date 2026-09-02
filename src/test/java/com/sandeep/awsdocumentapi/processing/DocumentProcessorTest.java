package com.sandeep.awsdocumentapi.processing;

import com.sandeep.awsdocumentapi.document.Document;
import com.sandeep.awsdocumentapi.document.DocumentRepository;
import com.sandeep.awsdocumentapi.document.DocumentStatus;
import com.sandeep.awsdocumentapi.storage.DocumentStorage;
import com.sandeep.awsdocumentapi.storage.StoredObjectNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentProcessorTest {

    /** echo -n hello | sha256sum */
    private static final String SHA256_OF_HELLO = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Mock
    private DocumentRepository repository;
    @Mock
    private DocumentStorage storage;
    @InjectMocks
    private DocumentProcessor processor;

    @TempDir
    Path tempDir;

    @Test
    void computesChecksumAndMarksProcessed() throws Exception {
        // A real file rather than a ByteArrayResource, to hash through the same kind of stream production uses.
        Path file = Files.writeString(tempDir.resolve("hello.txt"), "hello", StandardCharsets.UTF_8);
        Document document = processingDocument();
        given(repository.findById(document.getId())).willReturn(Optional.of(document));
        given(storage.load(document.getStorageKey())).willReturn(new FileSystemResource(file));

        processor.process(document.getId());

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
        assertThat(document.getChecksumSha256()).isEqualTo(SHA256_OF_HELLO);
        assertThat(document.getFailureReason()).isNull();
        verify(repository).save(document);
    }

    @Test
    void marksFailedWhenContentCannotBeRead() {
        Document document = processingDocument();
        given(repository.findById(document.getId())).willReturn(Optional.of(document));
        given(storage.load(document.getStorageKey()))
                .willThrow(new StoredObjectNotFoundException(document.getStorageKey()));

        processor.process(document.getId());

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getChecksumSha256()).isNull();
        assertThat(document.getFailureReason()).startsWith("StoredObjectNotFoundException:");
        verify(repository).save(document);
    }

    @Test
    void skipsDocumentsNotInProcessingState() {
        Document document = processingDocument();
        document.markUploaded(5); // back to UPLOADED, e.g. re-uploaded in the meantime
        given(repository.findById(document.getId())).willReturn(Optional.of(document));

        processor.process(document.getId());

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        verify(repository, never()).save(document);
    }

    @Test
    void ignoresDeletedDocuments() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        processor.process(id);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static Document processingDocument() {
        UUID id = UUID.randomUUID();
        Document document = new Document(id, "alice", "hello.txt", "text/plain", id + "-hello.txt");
        document.markUploaded(5);
        document.markProcessing();
        return document;
    }
}
