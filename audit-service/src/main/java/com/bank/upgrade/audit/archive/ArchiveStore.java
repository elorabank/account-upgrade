package com.bank.upgrade.audit.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Where archive files are kept. Today a folder ({@link FileSystemArchiveStore}); a cloud bucket would be another
 * implementation of these three operations.
 */
public interface ArchiveStore {

    /** Stores {@code file} as {@code name}, replacing an earlier copy; the stored file is complete or absent. */
    void put(String name, Path file) throws IOException;

    InputStream open(String name) throws IOException;

    /** Where the store keeps {@code name}, for people: a path or a URL. */
    String location(String name);
}
