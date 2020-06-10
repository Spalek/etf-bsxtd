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
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.io.FileUtils;
import org.basex.core.BaseXException;
import org.basex.core.Context;
import org.basex.core.cmd.*;
import org.slf4j.Logger;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
public class DatabasePartitioner implements DatabaseVisitor {

    private final long maxDbSizeSizePerChunk;
    private Context ctx = new Context();
    private final String dbBaseName;
    private final Set<String> skippedFiles = new TreeSet<>();
    private final Logger logger;

    // Needs to be syn
    // chronized in block if a new chunk is generated
    private int currentDbIndex = 0;
    // Needs to be synchronized in block if a new chunk is generated
    private String currentDbName;

    // Needs to be synchronized in any case
    private AtomicLong currentDbSize = new AtomicLong(0);

    // Needs to be synchronized in block if new file is added
    private long fileCount = 0L;
    // Needs to be synchronized in block if new file is added
    private long size = 0L;

    // Cut the first part of the added file name
    private final int filenameCutIndex;

    public DatabasePartitioner(long maxDbSizeSizePerChunk, final Logger logger,
            final String dbName, final int filenameCutIndex) throws BaseXException {
        this.dbBaseName = dbName;
        this.logger = logger;
        this.filenameCutIndex = filenameCutIndex;
        this.maxDbSizeSizePerChunk = maxDbSizeSizePerChunk;
        this.currentDbName = dbBaseName + "-0";

        new org.basex.core.cmd.Set("AUTOFLUSH", "false").execute(ctx);
        new org.basex.core.cmd.Set("TEXTINDEX", "true").execute(ctx);
        new org.basex.core.cmd.Set("ATTRINDEX", "true").execute(ctx);
        new org.basex.core.cmd.Set("FTINDEX", "true").execute(ctx);
        new org.basex.core.cmd.Set("MAXLEN", "160").execute(ctx);
        // already filtered
        new org.basex.core.cmd.Set("SKIPCORRUPT", "false").execute(ctx);
        new CreateDB(currentDbName).execute(ctx);
    }

    private static void flushAndOptimize(final Context ctx) throws BaseXException {
        new Flush().execute(ctx);
        new OptimizeAll().execute(ctx);
        new Close().execute(ctx);
    }

    /**
     * Returns 0 if no update is required
     *
     * @return 0 if no update is required, the old size otherwise
     */
    private synchronized long checkDbSizeAndGetOld() {
        if (currentDbSize.get() >= maxDbSizeSizePerChunk) {
            return currentDbSize.getAndSet(0);
        }
        return 0;
    }

    private synchronized Context createDbAndExchangeContext() throws BaseXException {
        final Context oldContext = this.ctx;
        currentDbName = dbBaseName + "-" + ++currentDbIndex;
        final Context newCtx = new Context(this.ctx);
        new CreateDB(currentDbName).execute(newCtx);
        this.ctx = newCtx;
        logger.info("Creating next database {} ", currentDbName);
        return oldContext;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            return FileVisitResult.TERMINATE;
        }
        final long oldDbSize = checkDbSizeAndGetOld();
        if (oldDbSize > 0) {
            final String tOldDbName = this.currentDbName;
            final Context oldContext = createDbAndExchangeContext();
            // Flush and optimize the current database
            logger.info(
                    "Added {} to test database {}",
                    FileUtils.byteCountToDisplaySize(oldDbSize),
                    tOldDbName);
            logger.info("Flushing the current test database due to the size limit of {}", FileUtils
                    .byteCountToDisplaySize(maxDbSizeSizePerChunk));
            logger.info("Optimizing database {} ", tOldDbName);
            flushAndOptimize(oldContext);
        }

        try {
            final String fileName = file.toAbsolutePath().toString().substring(filenameCutIndex);
            new Add(fileName, file.toString()).execute(ctx);
            currentDbSize.addAndGet(attrs.size());
            synchronized (this) {
                fileCount++;
                size += attrs.size();
            }
        } catch (BaseXException bsxEx) {
            // Skip not well-formed files
            logger.warn("Data import of file " + file.toString() + " failed : " + bsxEx.getMessage());
            skippedFiles.add(file.getFileName().toString());
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException exc) throws IOException {
        skippedFiles.add(file.getFileName().toString());
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public void release() {
        try {
            logger.info("Optimizing last database {} ", currentDbName);
            flushAndOptimize(ctx);
            new Open(currentDbName).execute(ctx);
            new Close().execute(ctx);
            ctx.close();
            logger.info("Import completed");
        } catch (final BaseXException e) {
            logger.error("Database import failed: ", e);
        }
    }

    public Set<String> getSkippedFiles() {
        return skippedFiles;
    }

    public int getDbCount() {
        return currentDbIndex + 1;
    }

    public long getSize() {
        return size;
    }

    public long getFileCount() {
        return fileCount;
    }
}
