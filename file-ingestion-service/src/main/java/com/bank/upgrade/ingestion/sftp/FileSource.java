package com.bank.upgrade.ingestion.sftp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Where the daily files are picked up from. One {@link Session} is one connection, opened per run. */
public interface FileSource {

    enum Folder { PROCESSED, ERROR }

    Session open() throws IOException;

    interface Session extends AutoCloseable {

        /** Names of the files waiting in the incoming folder. */
        List<String> listIncoming() throws IOException;

        void download(String name, Path target) throws IOException;

        /** Moves a file out of the incoming folder, optionally under a new name. */
        void move(String name, Folder folder, String newName) throws IOException;

        @Override
        void close() throws IOException;
    }
}
