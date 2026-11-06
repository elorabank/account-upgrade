package com.bank.upgrade.audit.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Archive files in a folder. A file is copied in under a temporary name and then renamed, so the folder never holds
 * half a file under the real name.
 */
public class FileSystemArchiveStore implements ArchiveStore {

    private final Path dir;

    public FileSystemArchiveStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    @Override
    public void put(String name, Path file) throws IOException {
        Files.createDirectories(dir);
        Path target = resolve(name);
        Path partial = dir.resolve(name + ".partial");
        Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING);
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public InputStream open(String name) throws IOException {
        return Files.newInputStream(resolve(name));
    }

    @Override
    public String location(String name) {
        return resolve(name).toString();
    }

    private Path resolve(String name) {
        Path path = dir.resolve(name).normalize();
        if (!path.getParent().equals(dir)) {
            throw new IllegalArgumentException("Not a plain file name: " + name);
        }
        return path;
    }
}
