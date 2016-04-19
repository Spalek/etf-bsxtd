/**
 * Copyright 2010-2016 interactive instruments GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.interactive_instruments.etf.testrunner.basex.xml.validation;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import de.interactive_instruments.Releasable;

/**
 * A synchronized collector for the error messages of the MultiThreadedSchemaValidator
 *
 * @author J. Herrmann ( herrmann <aT) interactive-instruments (doT> de )
 */
class ValidatorErrorCollector implements Releasable {

	private final StringBuilder sb = new StringBuilder();
	private int errors = 0;
	private final int maxErrors;
	private final ConcurrentMap<File, Integer> errorsPerFile = new ConcurrentSkipListMap<>();

	/**
	 * Saves a error message
	 *
	 * @param str message
	 */
	public synchronized void collectError(String str) {
		++errors;
		if (errors < maxErrors) {
			sb.append(str);
		}
	}

	/**
	 * Returns all errors as concatenated string.
	 *
	 * @return concatenated error messages
	 */
	public String getErrorMessages() {
		if (!errorsPerFile.isEmpty()) {

			int errorsPerFileCounter = 0;
			for (Map.Entry<File, Integer> e : errorsPerFile.entrySet()) {
				if (errorsPerFileCounter < maxErrors) {
					sb.append(e.getValue());
					sb.append(" errors in file ");
					sb.append(e.getKey().getName());
					errorsPerFileCounter++;
				} else {
					sb.append((errorsPerFileCounter - maxErrors));
					sb.append(" additional error messages skipped!");
					break;
				}
			}
		}
		return sb.toString() + (errors < maxErrors ? "" : System.lineSeparator() +
				(errors - maxErrors) + " additional error messages were skipped!");
	}

	public Set<File> getInvalidFiles() {
		return errorsPerFile.keySet();
	}

	/**
	 * Returns the number of errors.
	 *
	 * @return
	 */
	public int getErrorCount() {
		return errors;
	}

	/**
	 * Default constructor.
	 *
	 * @param maxErrors maximum number of errors that will be saved
	 */
	public ValidatorErrorCollector(int maxErrors) {
		this.maxErrors = maxErrors;
	}

	/**
	 * Inner error handler SAX errors.
	 */
	class ValidatorErrorHandler implements ErrorHandler, Releasable {

		private final File file;
		private int errorsInFile;
		private final StringBuilder lSb = new StringBuilder();

		ValidatorErrorHandler(final File file) {
			this.file = file;
		}

		private void logError(String severity, SAXParseException e) {
			++errorsInFile;
			lSb.append(severity + " in file " + file.getName() +
					" line " + e.getLineNumber() +
					" column " + e.getColumnNumber() +
					" : " + System.lineSeparator() + e.toString() +
					System.lineSeparator());
		}

		@Override
		public void warning(SAXParseException e) throws SAXException {

		}

		@Override
		public void error(SAXParseException e) throws SAXException {
			logError("ERROR", e);
		}

		@Override
		public void fatalError(SAXParseException e) throws SAXException {
			logError("FATAL ERROR", e);
		}

		@Override
		public void release() {
			if (errorsInFile > 0) {
				collectError(lSb.toString());
				errorsPerFile.put(file, errorsInFile);
			}
		}
	}

	@Override
	public void release() {
		errorsPerFile.clear();
	}

	/**
	 * Creates a new error handler
	 *
	 * @param file file which is checked
	 *
	 * @return a SAX error handler
	 */
	public ValidatorErrorHandler newErrorHandler(final File file) {
		return new ValidatorErrorHandler(file);
	}
}
