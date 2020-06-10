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
package de.interactive_instruments.etf.testdriver.bsx;

import static de.interactive_instruments.etf.testdriver.bsx.BsxTestDriver.BSX_TEST_DRIVER_EID;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Date;

import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.LoggerFactory;

import de.interactive_instruments.IFile;
import de.interactive_instruments.etf.EtfConstants;
import de.interactive_instruments.etf.component.ComponentNotLoadedException;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dao.DataStorageRegistry;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dao.basex.BsxDataStorage;
import de.interactive_instruments.etf.dal.dto.capabilities.ResourceDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectTypeDto;
import de.interactive_instruments.etf.dal.dto.result.TestTaskResultDto;
import de.interactive_instruments.etf.dal.dto.run.TestRunDto;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.dal.dto.test.ExecutableTestSuiteDto;
import de.interactive_instruments.etf.dal.dto.translation.TranslationArgumentCollectionDto;
import de.interactive_instruments.etf.detector.TestObjectTypeDetectorManager;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.model.EidMap;
import de.interactive_instruments.etf.testdriver.*;
import de.interactive_instruments.exceptions.*;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.properties.PropertyUtils;

/**
 *
 *
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class BsxTestRunTaskFactoryTest {

    // DO NOT RUN THE TESTS IN THE IDE BUT WITH GRADLE

    private static TestDriverManager testDriverManager = null;
    private static DataStorage DATA_STORAGE;

    private final static EID geometryEtsId = EidFactory.getDefault()
            .createAndPreserveStr("99b8aecd-4082-4773-b316-a138e6ed8b35");

    private static WriteDao<ExecutableTestSuiteDto> etsDao() {
        return ((WriteDao) DATA_STORAGE.getDao(ExecutableTestSuiteDto.class));
    }

    private TestRunDto createTestRunDtoForProject()
            throws ComponentNotLoadedException, ConfigurationException, URISyntaxException,
            ObjectWithIdNotFoundException, IOException {

        final TestObjectDto testObjectDto = new TestObjectDto();
        testObjectDto.setId(
                EidFactory.getDefault().createAndPreserveStr("fcfe9677-7b77-41dd-a17c-56884f608243"));
        testObjectDto.setLabel("Cite 2013 WFS");
        final TestObjectTypeDto wfsTestObjectType = DATA_STORAGE.getDao(TestObjectTypeDto.class).getById(
                EidFactory.getDefault().createAndPreserveStr("9b6ef734-981e-4d60-aa81-d6730a1c6389")).getDto();
        testObjectDto.setTestObjectType(wfsTestObjectType);
        testObjectDto.addResource(new ResourceDto("testData", new IFile("./build/resources/test/testdata").toURI()));
        testObjectDto.setDescription("none");
        testObjectDto.setVersionFromStr("1.0.0");
        testObjectDto.setCreationDate(new Date(0));
        testObjectDto.setAuthor("ii");
        testObjectDto.setRemoteResource(URI.create("http://none"));
        testObjectDto.setItemHash("");
        testObjectDto.setLocalPath("/none");

        ((WriteDao) DATA_STORAGE.getDao(TestObjectDto.class)).deleteAllExisting(Collections.singleton(testObjectDto.getId()));
        ((WriteDao) DATA_STORAGE.getDao(TestObjectDto.class)).add(testObjectDto);

        final ExecutableTestSuiteDto ets = DATA_STORAGE.getDao(ExecutableTestSuiteDto.class).getById(geometryEtsId).getDto();

        final TestTaskDto testTaskDto = new TestTaskDto();
        testTaskDto.setId(EidFactory.getDefault().createAndPreserveStr("aa03825a-2f64-4e52-bdba-90a08adb80c2"));
        testTaskDto.setExecutableTestSuite(ets);
        testTaskDto.setTestObject(testObjectDto);

        final TestRunDto testRunDto = new TestRunDto();
        testRunDto.setDefaultLang("en");
        testRunDto.setId(EidFactory.getDefault().createRandomId());
        testRunDto.setLabel("Run label");
        testRunDto.setStartTimestamp(new Date(0));
        testRunDto.addTestTask(testTaskDto);

        try {
            ((WriteDao) DATA_STORAGE.getDao(TestRunDto.class)).deleteAllExisting(Collections.singleton(testRunDto.getId()));
        } catch (Exception e) {
            ExcUtils.suppress(e);
        }
        return testRunDto;
    }

    private static DataStorage ensureInitialization() throws ConfigurationException, InvalidStateTransitionException,
            InitializationException, IOException {
        if (DATA_STORAGE == null || !DATA_STORAGE.isInitialized()) {
            DATA_STORAGE = new BsxDataStorage();
            final IFile DATA_STORAGE_DIR;
            if (System.getenv("ETF_DS_DIR") != null) {
                DATA_STORAGE_DIR = new IFile(System.getenv("ETF_DS_DIR"));
                DATA_STORAGE_DIR.mkdirs();
            } else if (new IFile("./build").exists()) {
                DATA_STORAGE_DIR = new IFile("./build/tmp/etf-ds");
                DATA_STORAGE_DIR.mkdirs();
            } else {
                DATA_STORAGE_DIR = null;
            }

            if (DATA_STORAGE_DIR == null || !DATA_STORAGE_DIR.exists()) {
                throw new InitializationException("DATA_STORAGE_DIR not set");
            }
            DATA_STORAGE.getConfigurationProperties().setProperty(EtfConstants.ETF_DATASOURCE_DIR,
                    DATA_STORAGE_DIR.getAbsolutePath());
            DATA_STORAGE.getConfigurationProperties().setProperty("etf.webapp.base.url", "http://localhost/etf-webapp");
            DATA_STORAGE.getConfigurationProperties().setProperty("etf.api.base.url", "http://localhost/etf-webapp/v2");
            DATA_STORAGE.init();
            DataStorageRegistry.instance().register(DATA_STORAGE);

            final WriteDao<TestObjectTypeDto> testObjectTypeDao = ((WriteDao<TestObjectTypeDto>) (DATA_STORAGE
                    .getDao(TestObjectTypeDto.class)));

            final EidMap<TestObjectTypeDto> supportedTypes = TestObjectTypeDetectorManager.getSupportedTypes();
            if (supportedTypes != null) {
                testObjectTypeDao.deleteAllExisting(supportedTypes.keySet());
                for (final TestObjectTypeDto testObjectTypeDto : supportedTypes.values()) {
                    testObjectTypeDao.add(testObjectTypeDto);
                }
            }
        }
        return DATA_STORAGE;
    }

    @BeforeClass
    public static void setUp()
            throws IOException, ConfigurationException, InvalidStateTransitionException,
            InitializationException, ObjectWithIdNotFoundException, StorageException {

        // DO NOT RUN THE TESTS IN THE IDE BUT WITH GRADLE

        // Init logger
        LoggerFactory.getLogger(BsxTestRunTaskFactoryTest.class).info("Started");

        DATA_STORAGE = ensureInitialization();

        if (testDriverManager == null) {

            // Delete old ETS
            try {
                etsDao().delete(geometryEtsId);
            } catch (final ObjectWithIdNotFoundException e) {
                ExcUtils.suppress(e);
            }

            final IFile testProjectDir = new IFile(PropertyUtils.getenvOrProperty(
                    "ETF_TESTING_BSX_TP_DIR", "./build/resources/test/ets"));

            final IFile tdDir = new IFile(PropertyUtils.getenvOrProperty(
                    "ETF_TD_DEPLOYMENT_DIR", "./build/tmp/td"));
            tdDir.ensureDir();
            tdDir.expectDirIsReadable();

            // Load driver
            testDriverManager = new DefaultTestDriverManager();
            testDriverManager.getConfigurationProperties().setProperty(
                    EtfConstants.ETF_PROJECTS_DIR, testProjectDir.getAbsolutePath());
            testDriverManager.getConfigurationProperties().setProperty(
                    EtfConstants.ETF_TESTDRIVERS_DIR, tdDir.getAbsolutePath());
            final IFile attachmentDir = new IFile(PropertyUtils.getenvOrProperty(
                    "ETF_DS_DIR", "./build/tmp/etf-ds")).secureExpandPathDown("attachments");
            attachmentDir.deleteDirectory();
            attachmentDir.mkdirs();
            testDriverManager.getConfigurationProperties().setProperty(
                    EtfConstants.ETF_ATTACHMENT_DIR, attachmentDir.getAbsolutePath());
            testDriverManager.getConfigurationProperties().setProperty(
                    EtfConstants.ETF_DATA_STORAGE_NAME,
                    BsxDataStorage.class.getName());

            // Load MetadataFileTypeLoader for TranslationTemplate
            final MetadataFileTypeLoader metadataTypeLoader = new MetadataFileTypeLoader(DATA_STORAGE);
            metadataTypeLoader.getConfigurationProperties().setPropertiesFrom(testDriverManager.getConfigurationProperties(),
                    true);
            metadataTypeLoader.init();

            testDriverManager.init();
            testDriverManager.load(EidFactory.getDefault().createAndPreserveStr(BSX_TEST_DRIVER_EID));
        }
    }

    @Test
    public void runGeometryEtsTest() throws Exception, ComponentNotLoadedException {

        // DO NOT RUN THE TESTS IN THE IDE BUT DIRECTLY WITH GRADLE

        final TestRunDto testRunDto = createTestRunDtoForProject();

        final TestRun testRun = testDriverManager.createTestRun(testRunDto);
        final TaskPoolRegistry<TestRunDto, TestRun> taskPoolRegistry = new TaskPoolRegistry<>(1, 1);
        testRun.init();
        taskPoolRegistry.submitTask(testRun);

        Thread.sleep(1000);
        final TestRunDto runResult = taskPoolRegistry.getTaskById(testRunDto.getId()).waitForResult();

        assertNotNull(runResult);
        assertNotNull(runResult.getTestTaskResults());
        assertFalse(runResult.getTestTaskResults().isEmpty());

        final TestTaskResultDto result = runResult.getTestTaskResults().get(0);
        assertNotNull(result);
        assertNotNull(result.getTestModuleResults());
        assertNotNull(result.getTestModuleResults().get(0).getResultedFrom());

        assertEquals("FAILED", result.getTestModuleResults().get(0).getResultStatus().toString());

        assertEquals("FAILED", result.getTestModuleResults().get(0).getTestCaseResults().get(0).getTestStepResults().get(0)
                .getTestAssertionResults().get(0).getResultStatus().toString());
        assertEquals("FAILED", result.getTestModuleResults().get(0).getTestCaseResults().get(0).getTestStepResults().get(0)
                .getTestAssertionResults().get(1).getResultStatus().toString());
        assertEquals("FAILED", result.getTestModuleResults().get(0).getTestCaseResults().get(0).getTestStepResults().get(0)
                .getTestAssertionResults().get(2).getResultStatus().toString());
        assertEquals("PASSED", result.getTestModuleResults().get(0).getTestCaseResults().get(0).getTestStepResults().get(0)
                .getTestAssertionResults().get(3).getResultStatus().toString());
        assertEquals("PASSED", result.getTestModuleResults().get(0).getTestCaseResults().get(0).getTestStepResults().get(0)
                .getTestAssertionResults().get(4).getResultStatus().toString());

        boolean messageFound = false;
        for (final TranslationArgumentCollectionDto arg : result.getTestModuleResults().get(0).getTestCaseResults().get(0)
                .getTestStepResults().get(0).getTestAssertionResults().get(1).getMessages()) {
            if (arg.getTokenValues().get("text") != null && arg.getTokenValues().get("text").getValue()
                    .contains("Cannot parse 'gml:posList': contains 14 values, but coordinate dimension is 3.")) {
                messageFound = true;
                break;
            }
        }
        assertTrue(messageFound);
    }
}
