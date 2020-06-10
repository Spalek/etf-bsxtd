/**
 * Copyright 2017-2019 European Union, interactive instruments GmbH
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * This work was supported by the EU Interoperability Solutions for
 * European Public Administrations Programme (http://ec.europa.eu/isa)
 * through Action 1.17: A Reusable INSPIRE Reference Platform (ARE3NA).
 */
package de.interactive_instruments.etf.testdriver.bsx.partitioning;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;

import org.basex.core.BaseXException;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
public class DatabaseInventarization implements DatabaseVisitor {
    private final long maxDbSizeSizePerChunk;
    private long currentDbSize = 0;
    private int currentDbIndex = 0;
    private final Set<String> skippedFiles = new TreeSet<>();
    private long size = 0;
    private long fileCount = 0;

    public DatabaseInventarization(long maxDbSizeSizePerChunk) throws BaseXException {
        currentDbIndex = 0;
        fileCount = 0L;
        this.maxDbSizeSizePerChunk = maxDbSizeSizePerChunk;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            return FileVisitResult.TERMINATE;
        }

        synchronized (this) {
            if (currentDbSize >= maxDbSizeSizePerChunk) {
                currentDbIndex++;
                currentDbSize = 0;
            }
            currentDbSize += attrs.size();
        }

        size += attrs.size();
        fileCount++;
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            throws IOException {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc)
            throws IOException {
        skippedFiles.add(file.getFileName().toString());
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
            throws IOException {
        return FileVisitResult.CONTINUE;
    }

    /**
     * Number of databases.
     *
     * @return number of databases
     */
    public int getDbCount() {
        return currentDbIndex + 1;
    }

    /**
     * Total size of files.
     *
     * @return files size in bytes
     */
    public long getSize() {
        return size;
    }

    /**
     * Number of files.
     *
     * @return number of files
     */
    public long getFileCount() {
        return fileCount;
    }

    public Set<String> getSkippedFiles() {
        return skippedFiles;
    }

    @Override
    public void release() {
        // nothing to do
    }
}
