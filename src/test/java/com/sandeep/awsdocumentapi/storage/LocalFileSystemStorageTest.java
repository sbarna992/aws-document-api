package com.sandeep.awsdocumentapi.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileSystemStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileSystemStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileSystemStorage(tempDir.resolve("docs"));
    }

    @Test
    void createsRootDirectoryOnStartup() {
        assertThat(tempDir.resolve("docs")).isDirectory();
    }

    @Test
    void storesAndLoadsBytes() throws IOException {
        long written = storage.store("abc-hello.txt", bytes("hello"));

        assertThat(written).isEqualTo(5);
        assertThat(storage.exists("abc-hello.txt")).isTrue();
        assertThat(storage.load("abc-hello.txt").getContentAsString(StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(tempDir.resolve("docs/abc-hello.txt")).isRegularFile();
        assertThat(tempDir.resolve("docs/abc-hello.txt.part")).doesNotExist();
    }

    @Test
    void storeReplacesExistingObject() throws IOException {
        storage.store("k", bytes("first"));
        storage.store("k", bytes("second!"));

        assertThat(storage.load("k").getContentAsString(StandardCharsets.UTF_8)).isEqualTo("second!");
    }

    @Test
    void loadOfMissingKeyThrows() {
        assertThatThrownBy(() -> storage.load("missing"))
                .isInstanceOf(StoredObjectNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void deleteIsIdempotent() {
        storage.store("k", bytes("x"));

        storage.delete("k");
        storage.delete("k");

        assertThat(storage.exists("k")).isFalse();
    }

    @Test
    void rejectsKeysThatEscapeTheRoot() throws IOException {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "secret");

        assertThatThrownBy(() -> storage.load("../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("../pwned.txt", bytes("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("..")).isInstanceOf(IllegalArgumentException.class);
        assertThat(outside).hasContent("secret");
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
