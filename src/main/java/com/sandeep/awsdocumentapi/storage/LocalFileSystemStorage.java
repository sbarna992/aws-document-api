package com.sandeep.awsdocumentapi.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(name = "documents.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileSystemStorage implements DocumentStorage {

    private final Path root;

    @Autowired
    public LocalFileSystemStorage(LocalStorageProperties properties) {
        this(Path.of(properties.root()));
    }

    LocalFileSystemStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage root " + this.root, e);
        }
        log.info("Local document storage at {}", this.root);
    }

    @Override
    public long store(String storageKey, InputStream content) {
        Path target = resolve(storageKey);
        // Write to a sibling temp file and move it into place, so a reader never sees a half-written object.
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            long bytes = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return bytes;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new UncheckedIOException("Failed to store " + storageKey, e);
        }
    }

    @Override
    public Resource load(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new StoredObjectNotFoundException(storageKey);
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.isRegularFile(resolve(storageKey));
    }

    @Override
    public Optional<Long> sizeOf(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.size(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read size of " + storageKey, e);
        }
    }

    /** A directory cannot hand out URLs; the API accepts the bytes itself (PUT /documents/{id}/content). */
    @Override
    public Optional<URI> presignedUploadUrl(String storageKey, String contentType) {
        return Optional.empty();
    }

    @Override
    public Optional<URI> presignedDownloadUrl(String storageKey) {
        return Optional.empty();
    }

    /** Maps a key to a path and refuses anything that would escape the root ("../"). */
    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Storage key escapes storage root: " + storageKey);
        }
        return path;
    }
}
