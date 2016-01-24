/**
 * Copyright 2016 interactive instruments GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.interactive_instruments.etf.testrunner.basex.xml.validation;

import de.interactive_instruments.IFile;
import de.interactive_instruments.exceptions.ExcUtils;
import de.interactive_instruments.io.PathFilter;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParserFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.ValidatorHandler;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Multi threaded schema validator.
 *
 * @author J. Herrmann ( herrmann <aT) interactive-instruments (doT> de )
 */
public class MultiThreadedSchemaValidator {

  private static final int MAX_ERRORS = 1000;
  private static final int MAX_DIR_DEPTH = 5;
  private static final int MAX_VAL_TIMEOUT_H = 36;

  private final IFile dir;
  private final ValidatorErrorCollector collHandler;
  private final Schema schema;
  private PathFilter filter;

  /**
   * Default constructor.
   *
   * @param dir        directory with files for validation
   * @param filter     file filter
   * @param schemaFile schema file
   *
   * @throws SAXException
   */
  public MultiThreadedSchemaValidator(final IFile dir, final PathFilter filter,
      final IFile schemaFile) throws SAXException {
    this.filter = filter;
    this.dir = dir;
    this.collHandler = new ValidatorErrorCollector(MAX_ERRORS);
    schema = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema").newSchema(schemaFile);
  }

  /**
   * Removes the problematic UTF-8 byte order mark
   *
   * @param reader Reader
   * @param bom    bom sequence
   *
   * @return true if bom has been removed
   *
   * @throws IOException reader error
   */
  private static boolean removeBOM(Reader reader, char[] bom) throws IOException {
    int bomLength = bom.length;
    reader.mark(bomLength);
    char[] possibleBOM = new char[bomLength];
    reader.read(possibleBOM);
    for (int x = 0; x < bomLength; x++) {
      if ((int) bom[x] != (int) possibleBOM[x]) {
        reader.reset();
        return false;
      }
    }
    return true;
  }

  private static final char[] UTF32BE = {0x0000, 0xFEFF};
  private static final char[] UTF32LE = {0xFFFE, 0x0000};
  private static final char[] UTF16BE = {0xFEFF};
  private static final char[] UTF16LE = {0xFFFE};
  private static final char[] UTF8 = {0xEFBB, 0xBF};

  /**
   * Removes the problematic UTF-8 byte order mark
   *
   * @param reader Reader
   *
   * @return true if bom has been removed
   *
   * @throws IOException reader error
   */
  private static void removeBOM(Reader reader) throws IOException {
    if (removeBOM(reader, UTF32BE)) {
      return;
    }
    if (removeBOM(reader, UTF32LE)) {
      return;
    }
    if (removeBOM(reader, UTF16BE)) {
      return;
    }
    if (removeBOM(reader, UTF16LE)) {
      return;
    }
    if (removeBOM(reader, UTF8)) {
      return;
    }
  }

  /**
   * New reader from a file without BOM
   *
   * @param file file to read
   *
   * @return cleaned reader
   *
   * @throws IOException Reader error
   */
  private BufferedReader getRemovedBomReader(final File file) throws IOException {
    final InputStream inputStream = new FileInputStream(file);
    final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
    removeBOM(bufferedReader);
    return bufferedReader;
  }

  /**
   * Validate file with the SAX parser.
   * <p>
   * The results are collected in the ValidatorErrorCollector of the parent class.
   *
   * @param inputFile
   */
  private void valWithSax(final File inputFile) {
    BufferedReader bufferedReader = null;
    try {
      final SAXParserFactory spf = SAXParserFactory.newInstance();
      spf.setNamespaceAware(true);
      final XMLReader reader = spf.newSAXParser().getXMLReader();
      final ValidatorHandler vh = schema.newValidatorHandler();
      ValidatorErrorCollector.ValidatorErrorHandler eh =
          collHandler.newErrorHandler(inputFile.getName());
      vh.setErrorHandler(eh);
      reader.setContentHandler(vh);
      bufferedReader = getRemovedBomReader(inputFile);
      reader.parse(new InputSource(bufferedReader));
      eh.release();
    } catch (Exception e) {
      ExcUtils.supress(e);
    } finally {
      if (bufferedReader != null) {
        IFile.closeQuietly(bufferedReader);
      }
    }
  }

  /**
   * A runnable which is executed by the managing ValThreadMgr
   * and which validates a file.
   */
  class ValidatorRunnable implements Runnable {
    private final File inputFile;

    /**
     * Default constructor.
     *
     * @param inputFile file to validate
     */
    ValidatorRunnable(final File inputFile) {
      this.inputFile = inputFile;
    }

    @Override public void run() {
      valWithSax(inputFile);
    }
  }


  /**
   * A managing class which creates a new validator thread
   * for every visited file.
   */
  class ValThreadMgr extends SimpleFileVisitor<Path> {

    private ExecutorService exec;

    /**
     * Default constructor.
     *
     * @param exec
     */
    ValThreadMgr(final ExecutorService exec) {
      this.exec = exec;
    }

    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
        throws IOException {
      if (Thread.currentThread().isInterrupted()) {
        return FileVisitResult.TERMINATE;
      }
      if (filter.accept(path)) {
        exec.execute(new ValidatorRunnable(path.toFile()));
      }
      return FileVisitResult.CONTINUE;
    }
  }

  /**
   * Starts the validation process by creating a validation thread for every CPU core.
   *
   * @throws InterruptedException progress interrupted
   * @throws IOException          I/O error in file visitor
   */
  final public void validate() throws InterruptedException, IOException {
    final ExecutorService exec = Executors.newWorkStealingPool();

    // Create for each file a thread
    Files.walkFileTree(dir.toPath(), EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DIR_DEPTH,
        new ValThreadMgr(exec));

    // Wait for all submitted threads to finish
    exec.shutdown();
    exec.awaitTermination(MAX_VAL_TIMEOUT_H, TimeUnit.HOURS);
  }

  /**
   * Returns all concatenated error messages.
   *
   * @return concatenated error messages
   */
  final public String getErrors() {
    return collHandler.getErrors();
  }

  /**
   * Returns the number of errors.
   *
   * @return number of errors.
   */
  final public int getErrorCount() {
    return collHandler.getErrorCount();
  }

}



