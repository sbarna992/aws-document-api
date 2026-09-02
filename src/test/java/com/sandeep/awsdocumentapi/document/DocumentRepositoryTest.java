package com.sandeep.awsdocumentapi.document;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real local PostgreSQL (no Docker/Testcontainers on this machine).
 * {@code @DataJpaTest} wraps each test in a transaction that is rolled back, so nothing is left behind.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DocumentRepositoryTest {

    @Autowired
    private DocumentRepository repository;

    @Test
    void savesWithGeneratedTimestampsAndPendingStatus() {
        Document saved = repository.saveAndFlush(newDocument("alice", "proposal.pdf"));

        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.PENDING_UPLOAD);
        assertThat(saved.getFileSize()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void listsOnlyTheOwnersDocumentsNewestFirst() {
        Document first = repository.saveAndFlush(newDocument("alice", "first.pdf"));
        Document second = repository.saveAndFlush(newDocument("alice", "second.pdf"));
        repository.saveAndFlush(newDocument("bob", "bobs.pdf"));

        assertThat(repository.findByOwnerIdOrderByCreatedAtDesc("alice"))
                .extracting(Document::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void lookupIsScopedToOwner() {
        Document doc = repository.saveAndFlush(newDocument("alice", "secret.pdf"));

        assertThat(repository.findByIdAndOwnerId(doc.getId(), "alice")).isPresent();
        assertThat(repository.findByIdAndOwnerId(doc.getId(), "bob")).isEmpty();
    }

    private static Document newDocument(String owner, String filename) {
        UUID id = UUID.randomUUID();
        return new Document(id, owner, filename, "application/pdf", "documents/" + id + "-" + filename);
    }
}
