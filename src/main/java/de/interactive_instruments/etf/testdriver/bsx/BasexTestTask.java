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
package de.interactive_instruments.etf.testdriver.bsx;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.basex.core.BaseXException;
import org.basex.core.Context;
import org.basex.core.cmd.*;
import org.basex.query.QueryException;
import org.basex.query.QueryProcessor;

import de.interactive_instruments.IFile;
import de.interactive_instruments.SUtils;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dao.StreamWriteDao;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.Dto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.result.TestTaskResultDto;
import de.interactive_instruments.etf.dal.dto.run.TestRunDto;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.testdriver.AbstractTestTask;
import de.interactive_instruments.etf.testdriver.AbstractTestTaskProgress;
import de.interactive_instruments.etf.testdriver.bsx.partitioning.DatabaseInventarization;
import de.interactive_instruments.etf.testdriver.bsx.partitioning.DatabasePartitioner;
import de.interactive_instruments.etf.testdriver.bsx.partitioning.DatabaseVisitor;
import de.interactive_instruments.exceptions.ExcUtils;
import de.interactive_instruments.exceptions.InitializationException;
import de.interactive_instruments.exceptions.InvalidParameterException;
import de.interactive_instruments.exceptions.InvalidStateTransitionException;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.io.FileHashVisitor;
import de.interactive_instruments.io.MultiThreadedFilteredFileVisitor;
import de.interactive_instruments.io.PathFilter;
import de.interactive_instruments.validation.ParalellSchemaValidationManager;

/**
 * BaseX test run task for executing XQuery on a BaseX database.
 *
 * @author J. Herrmann ( herrmann <aT) interactive-instruments (doT> de )
 */
class BasexTestTask<T extends Dto> extends AbstractTestTask {

	private final String dbName;
	private final DataStorage dataStorageCallback;
	private final Context ctx;
	// private final IFile dsDir;
	private QueryProcessor proc;
	// The basex project file
	private final IFile projectFile;
	private final IFile projDir;
	private final long maxDbChunkSize;

	static class BasexTaskProgress extends AbstractTestTaskProgress {
		void doInit(final long maxSteps) {
			initMaxSteps(maxSteps);
		}

		void doAdvance() {
			advance();
		}
	}

	/**
	 * Default constructor.
	 *
	 * @param maxDbChunkSize maximum size of one database chunk
	 * @throws IOException I/O error
	 * @throws QueryException database error
	 */
	public BasexTestTask(final TestTaskDto testTaskDto, final long maxDbChunkSize, final DataStorage dataStorageCallback) {
		super(testTaskDto, new BasexTaskProgress(), BasexTestTask.class.getClassLoader());

		this.maxDbChunkSize = maxDbChunkSize;

		this.dbName = BsxConstants.ETF_TESTDB_PREFIX + testTaskDto.getTestObject().getId().toString();
		this.dataStorageCallback = dataStorageCallback;
		this.ctx = new Context();
		try {
			this.projectFile = new IFile(new File(testTaskDto.getExecutableTestSuite().getLocalPath(),
					"../" + testTaskDto.getExecutableTestSuite().getReference()).getCanonicalFile());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		// this.projectFile = new IFile(testTaskDto.getExecutableTestSuite().getLocalPath());
		this.projDir = new IFile(projectFile.getParentFile());

	}

	@Override
	protected void doRun() throws Exception {
		Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
		this.projDir.expectDirIsReadable();
		this.projectFile.expectIsReadable();

		final TestObjectDto testObject = testTaskDto.getTestObject();

		// Todo multiple dirs
		final IFile testDataDirDir = new IFile(
				testTaskDto.getTestObject().getResourceCollection().iterator().next().getUri(), this.dbName);
		testDataDirDir.expectDirIsReadable();

		advance();
		checkUserParameters();

		// Init file filter
		final String regex = testTaskDto.getArguments().value("regex");
		final PathFilter filter;
		if (regex != null && !regex.isEmpty()) {
			filter = new BasexTestObjectFileFilter(new RegexFileFilter(regex));
		} else {
			filter = new BasexTestObjectFileFilter();
		}
		// Init file hash visitor
		final FileHashVisitor fileHashVisitor = new FileHashVisitor(filter);
		Files.walkFileTree(testDataDirDir.toPath(),
				EnumSet.of(FileVisitOption.FOLLOW_LINKS), 5, fileHashVisitor);

		final boolean testObjectChanged;
		if ("false".equals(testTaskDto.getTestObject().properties().getPropertyOrDefault("indexed", "false"))) {
			testObjectChanged = true;
			getLogger().info("Creating new tests databases to speed up tests.");
		} else if (!Arrays.equals(fileHashVisitor.getHash(), testObject.getItemHash())) {
			// Delete old databases
			getLogger().info("Recreating new tests databases as the Test Object has changed!");
			for (int i = 0; i < 10000; i++) {
				boolean dropped = Boolean.valueOf(new DropDB(this.dbName + "-" + i).execute(ctx));
				if (dropped) {
					getLogger().info("Database " + i + " dropped");
				} else {
					break;
				}
			}
			testObjectChanged = true;
		} else {
			testObjectChanged = false;
		}

		// Validate against schema if schema file is set
		// First of all get the schema file
		final String schemaFilePath;
		final String schemaFilePathArg = this.testTaskDto.getArguments().value("Schema_file");
		if (!SUtils.isNullOrEmpty(schemaFilePathArg)) {
			schemaFilePath = schemaFilePathArg;
		} else {
			// STD fallback: check for a schema.xsd named file
			final String stdSchemaFile = "schema.xsd";
			if (projDir.secureExpandPathDown(stdSchemaFile).exists()) {
				schemaFilePath = stdSchemaFile;
			} else {
				schemaFilePath = null;
			}
		}

		// Initialize the validator
		final ParalellSchemaValidationManager schemaValidatorManager;
		if (!SUtils.isNullOrEmpty(schemaFilePath)) {
			final IFile schemaFile = projDir.secureExpandPathDown(schemaFilePath);
			schemaFile.expectIsReadable();
			getLogger().info("Initializing parallel schema validation.");
			schemaValidatorManager = new ParalellSchemaValidationManager(schemaFile);
		} else {
			schemaValidatorManager = new ParalellSchemaValidationManager();
			getLogger().info("Skipping schema validation because no schema file has been set in the test suite. Data are only checked for well-formedness.");
		}

		// Initialize Database Partitioner
		final DatabaseVisitor databaseVisitor;
		if (testObjectChanged) {
			databaseVisitor = new DatabasePartitioner(maxDbChunkSize, getLogger(), this.dbName, testDataDirDir.getAbsolutePath().length());
		} else {
			databaseVisitor = new DatabaseInventarization(maxDbChunkSize);
		}

		// Combine filters and visitors
		final MultiThreadedFilteredFileVisitor multiThreadedFileVisitor = new MultiThreadedFilteredFileVisitor(
				filter, schemaValidatorManager, Collections.singleton(databaseVisitor));
		Files.walkFileTree(testDataDirDir.toPath(), EnumSet.of(FileVisitOption.FOLLOW_LINKS), 5, multiThreadedFileVisitor);
		multiThreadedFileVisitor.startWorkers();
		multiThreadedFileVisitor.awaitTermination();
		databaseVisitor.release();
		if (testObjectChanged) {
			testObject.properties().setProperty("indexed", "true");
			testObject.setItemHash(fileHashVisitor.getHash());
			testObject.properties().setProperty("files", String.valueOf(fileHashVisitor.getFileCount()));
			testObject.properties().setProperty("size", String.valueOf(fileHashVisitor.getSize()));
			testObject.properties().setProperty("sizeHR", FileUtils.byteCountToDisplaySize(fileHashVisitor.getSize()));
			// Todo: use preparation task and update the DTO in the higher layer
			((WriteDao<TestObjectDto>) dataStorageCallback.getDao(TestObjectDto.class)).updateWithoutEidChange(testObject);
			// FIXME
			final TestRunDto testRunDto = ((TestRunDto) testTaskDto.getParent());
			for (final TestTaskDto taskDto : testRunDto.getTestTasks()) {
				if (taskDto.getTestObject().getId().equals(testObject.getId())) {
					taskDto.setTestObject(testObject);
				}
			}
		}

		getLogger().info("Validation ended with {} error(s)", schemaValidatorManager.getErrorCount());

		final StringBuilder skippedFiles = new StringBuilder("");
		final int skippedFilesSize = schemaValidatorManager.getSkippedFiles().size();
		if (skippedFilesSize > 0) {
			skippedFiles.append("Skipped " + skippedFilesSize +
					" invalid file(s): " + SUtils.ENDL);
			for (File f : schemaValidatorManager.getSkippedFiles()) {
				skippedFiles.append(f.getName() + SUtils.ENDL);
			}
		}

		advance();
		advance();
		checkCancelStatus();

		// Load the test project as XQuery
		proc = new QueryProcessor(projectFile.readContent().toString(), ctx);
		proc.job().listener = info -> getLogger().info(info);

		// Bind script variables
		// Workaround: Wrap File around URI for a clean path or basex will
		// throw an exception
		final File tmpResultFile = new File(resultCollector.getTempDir(), "TestTaskResult-" + this.getId() + ".xml");
		proc.bind("$outputFile", tmpResultFile);
		proc.bind("$testTaskResultId", testTaskDto.getTestTaskResult().getId().getId());
		proc.bind("$attachmentDir", resultCollector.getAttachmentDir());
		proc.bind("$projDir", projDir);
		proc.bind("$dbBaseName", this.dbName);
		proc.bind("$tmpDir", this.resultCollector.getTempDir());
		proc.bind("$dbDir", testDataDirDir.getPath());
		proc.bind("$etsFile", testTaskDto.getExecutableTestSuite().getLocalPath());
		proc.bind("$dbCount", databaseVisitor.getDbCount());
		proc.bind("$reportLabel", ((TestRunDto) testTaskDto.getParent()).getLabel());
		proc.bind("$reportStartTimestamp", getProgress().getStartTimestamp().getTime());

		final EID testTaskResultId = EidFactory.getDefault().createRandomId();
		proc.bind("$testObjectId", "EID" + testTaskResultId);
		proc.bind("$testTaskResultId", "EID" + testTaskDto.getTestTaskResult().getId());

		proc.bind("$testObjectId", "EID" + this.testTaskDto.getTestObject().getId());
		proc.bind("$testTaskId", "EID" + this.testTaskDto.getId());
		proc.bind("$testTaskResultId", "EID" + this.testTaskDto.getTestTaskResult().getId());
		proc.bind("$testRunId", "EID" + this.testTaskDto.getParent().getId());
		proc.bind("$executableTestSuiteId", "EID" + this.testTaskDto.getExecutableTestSuite().getId());
		proc.bind("$translationTemplateBundleId", "EID" + this.testTaskDto.getExecutableTestSuite().getTranslationTemplateBundle().getId());

		// Add errors about not well-formed or invalid XML
		final String validationErrors = skippedFiles.toString() + schemaValidatorManager.getErrorMessages();
		proc.bind("validationErrors", validationErrors);

		setUserParameters();

		getLogger().info("Compiling test script");
		proc.compile();
		advance();
		checkCancelStatus();

		getLogger().info("Starting XQuery tests");
		proc.value();

		final FileInputStream fileStream = new FileInputStream(tmpResultFile);
		// TODO: remove, when result collector will persist the results (see doRun in SUI TD)
		// automatically with resultCollector.end()
		final TestTaskResultDto testTaskResult = ((StreamWriteDao<TestTaskResultDto>) dataStorageCallback.getDao(TestTaskResultDto.class)).add(fileStream);

		testTaskDto.setTestTaskResult(testTaskResult);
	}

	private void advance() {
		((BasexTaskProgress) progress).doAdvance();
	}

	@Override
	protected void doInit() throws ConfigurationException, InitializationException {
		Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
		((BasexTaskProgress) progress).doInit(10);
	}

	/**
	 * Check the user parameters by executing the Project check file
	 *
	 * @throws IOException I/O error reading check file
	 * @throws QueryException error executing check file
	 * @throws InvalidParameterException invalid user parameter detected
	 */
	private void checkUserParameters() throws IOException, QueryException, InvalidParameterException {
		// Check parameters by executing the xquery script
		final String checkParamXqFileName = projectFile.getName().replace(BsxConstants.BSX_ETS_FILE, BsxConstants.PROJECT_CHECK_FILE_SUFFIX);
		final IFile checkParamXqFile = projDir.secureExpandPathDown(checkParamXqFileName);
		if (checkParamXqFile.exists()) {
			proc = new QueryProcessor(checkParamXqFile.readContent().toString(), ctx);
			try {
				setUserParameters();
				proc.compile();
				// Version 8
				proc.value();
			} catch (QueryException e) {
				getLogger().info("Invalid user parameters. Error message: " + e.getMessage());
				throw e;
			}
			getLogger().info("User parameters accepted");
			proc.close();
		}
	}

	/**
	 * Bind user parameters
	 *
	 * @throws QueryException database error
	 */
	private void setUserParameters() throws QueryException {
		// Bind additional user specified properties
		for (Map.Entry<String, String> property : this.testTaskDto.getArguments().values().entrySet()) {
			proc.bind(property.getKey(), property.getValue());
		}
	}

	@Override
	public void doRelease() {
		try {
			if (proc != null) {
				proc.close();
			}
			new Close().execute(ctx);
		} catch (BaseXException e) {
			ExcUtils.suppress(e);
		}
	}

	@Override
	protected void doCancel() throws InvalidStateTransitionException {
		if (proc != null) {
			proc.stop();
		}
	}
}
