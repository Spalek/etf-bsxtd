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

import static de.interactive_instruments.etf.EtfConstants.ETF_DATA_STORAGE_NAME;
import static de.interactive_instruments.etf.testdriver.bsx.BsxTestDriver.BSX_TEST_DRIVER_EID;
import static de.interactive_instruments.etf.testdriver.bsx.Types.BSX_SUPPORTED_TEST_OBJECT_TYPES;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

import org.apache.commons.io.FileUtils;
import org.basex.BaseX;
import org.basex.core.Context;
import org.basex.query.QueryException;
import org.basex.query.util.pkg.RepoManager;
import org.deegree.cs.persistence.CRSManager;

import de.interactive_instruments.IFile;
import de.interactive_instruments.IoUtils;
import de.interactive_instruments.JarUtils;
import de.interactive_instruments.etf.component.ComponentInfo;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dao.DataStorageRegistry;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.IncompleteDtoException;
import de.interactive_instruments.etf.dal.dto.capabilities.ComponentDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectTypeDto;
import de.interactive_instruments.etf.dal.dto.result.TestTaskResultDto;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.testdriver.AbstractTestDriver;
import de.interactive_instruments.etf.testdriver.ComponentInitializer;
import de.interactive_instruments.etf.testdriver.TestTask;
import de.interactive_instruments.etf.testdriver.TestTaskInitializationException;
import de.interactive_instruments.exceptions.*;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.exceptions.config.InvalidPropertyException;
import de.interactive_instruments.properties.ConfigProperties;

/**
 * BaseX test driver component
 *
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@ComponentInitializer(id = BSX_TEST_DRIVER_EID)
public class BsxTestDriver extends AbstractTestDriver {

    public static final String BSX_TEST_DRIVER_EID = "4dddc9e2-1b21-40b7-af70-6a2d156ad130";
    public static final long DEFAULT_MAX_CHUNK_SIZE = 33500000000L;
    private static final String GMLGEOX_SRSCONFIG_DIR = "etf.testdrivers.bsx.gmlgeox.srsconfig.dir";
    private DataStorage dataStorageCallback;

    private final ComponentInfo info = new ComponentInfo() {
        @Override
        public String getName() {
            return "BaseX test driver";
        }

        @Override
        public EID getId() {
            return EidFactory.getDefault().createAndPreserveStr(BSX_TEST_DRIVER_EID);
        }

        @Override
        public String getVersion() {
            return this.getClass().getPackage().getImplementationVersion();
        }

        @Override
        public String getVendor() {
            return this.getClass().getPackage().getImplementationVendor();
        }

        @Override
        public String getDescription() {
            return "Test driver for BaseX " + BaseX.class.getPackage().getImplementationVersion();
        }
    };

    public BsxTestDriver() {
        super(new ConfigProperties(ETF_DATA_STORAGE_NAME, BsxConstants.PROJECT_DIR_KEY));
    }

    @Override
    public Collection<TestObjectTypeDto> getTestObjectTypes() {
        return BSX_SUPPORTED_TEST_OBJECT_TYPES.values();
    }

    @Override
    final public ComponentInfo getInfo() {
        return info;
    }

    @Override
    public TestTask createTestTask(final TestTaskDto testTaskDto) throws TestTaskInitializationException {
        try {
            Objects.requireNonNull(testTaskDto, "Test Task not set").ensureBasicValidity();

            // Get ETS
            testTaskDto.getTestObject().ensureBasicValidity();
            testTaskDto.getExecutableTestSuite().ensureBasicValidity();
            final TestTaskResultDto testTaskResult = new TestTaskResultDto();
            testTaskResult.setId(EidFactory.getDefault().createRandomId());
            testTaskDto.setTestTaskResult(testTaskResult);
            return new BasexTestTask(testTaskDto, ((WriteDao) dataStorageCallback.getDao(TestObjectDto.class)),
                    configProperties.getPropertyOrDefaultAsLong(BsxConstants.DB_MAX_CHUNK_SIZE, DEFAULT_MAX_CHUNK_SIZE));
        } catch (InvalidPropertyException | IncompleteDtoException e) {
            throw new TestTaskInitializationException(e);
        }

    }

    @Override
    protected void doInit() throws ConfigurationException, InitializationException, InvalidStateTransitionException {
        dataStorageCallback = DataStorageRegistry.instance().get(configProperties.getProperty(ETF_DATA_STORAGE_NAME));
        if (dataStorageCallback == null) {
            throw new InvalidStateTransitionException("Data Storage not set");
        }

        if (configProperties.getProperty("org.basex.path") != null) {
            System.setProperty("org.basex.path", configProperties.getProperty("org.basex.path"));
        }

        configureDeegree();
        propagateComponents(installGmlGeoX());

        loader = new BsxFileLoaderFactory(dataStorageCallback);
        loader.getConfigurationProperties().setPropertiesFrom(configProperties, true);
    }

    private void propagateComponents(ComponentInfo gmlGeoXInfo) throws InitializationException {
        // Propagate Component info from here
        final WriteDao<ComponentDto> componentDao = ((WriteDao<ComponentDto>) dataStorageCallback.getDao(ComponentDto.class));
        try {
            // Remove existing Test Driver
            try {
                componentDao.delete(this.getInfo().getId());
            } catch (final ObjectWithIdNotFoundException e) {
                ExcUtils.suppress(e);
            }
            // Remove existing GmlGeoX
            try {
                componentDao.delete(gmlGeoXInfo.getId());
            } catch (final ObjectWithIdNotFoundException e) {
                ExcUtils.suppress(e);
            }
            componentDao.add(new ComponentDto(this.getInfo()));
            componentDao.add(new ComponentDto(gmlGeoXInfo));
        } catch (StorageException e) {
            throw new InitializationException(e);
        }
    }

    private void configureDeegree() throws ConfigurationException, InitializationException {
        final String srsConfigDirPath = configProperties.getProperty(GMLGEOX_SRSCONFIG_DIR);

        // Temporary switch the context classloader to a classloader that can access the deegree libs
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CRSManager.class.getClassLoader());

        final CRSManager crsMgr = new CRSManager();
        if (srsConfigDirPath != null) {
            final IFile srsConfigDirectory = new IFile(srsConfigDirPath, GMLGEOX_SRSCONFIG_DIR);

            try {
                srsConfigDirectory.expectDirIsWritable();
                crsMgr.init(srsConfigDirectory);
            } catch (IOException e) {
                throw new ConfigurationException(
                        "Could not load SRS configuration files from directory referenced from GmlGeoX property '"
                                + GMLGEOX_SRSCONFIG_DIR + "'. Reference is: " + srsConfigDirPath
                                + " Exception message is: " + e.getMessage());
            }
        } else {
            try {
                final String tempDirPath = System.getProperty("java.io.tmpdir");
                final File tempDir = new File(tempDirPath, "gmlGeoXSrsConfig");

                if (tempDir.exists()) {
                    FileUtils.deleteQuietly(tempDir);
                }
                tempDir.mkdirs();
                IoUtils.copyResourceToFile(this, "/srsconfig/default.xml", new IFile(tempDir, "default.xml"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/config/ntv2/beta2007.gsb",
                        new IFile(tempDir, "deegree/d3/config/ntv2/beta2007.gsb"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/parser-files.xml",
                        new IFile(tempDir, "deegree/d3/parser-files.xml"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/config/crs-definitions.xml",
                        new IFile(tempDir, "deegree/d3/config/crs-definitions.xml"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/config/datum-definitions.xml",
                        new IFile(tempDir, "deegree/d3/config/datum-definitions.xml"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/config/ellipsoid-definitions.xml",
                        new IFile(tempDir, "deegree/d3/config/ellipsoid-definitions.xml"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/config/pm-definitions.xml",
                        new IFile(tempDir, "deegree/d3/config/pm-definitions.xml"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/config/projection-definitions.xml",
                        new IFile(tempDir, "deegree/d3/config/projection-definitions.xml"));
                IoUtils.copyResourceToFile(this, "/srsconfig/deegree/d3/config/transformation-definitions.xml",
                        new IFile(tempDir, "deegree/d3/config/transformation-definitions.xml"));
                crsMgr.init(tempDir);
            } catch (IOException e) {
                throw new InitializationException(
                        "Exception occurred while extracting the SRS configuration files provided by GmlGeoX to a temporary "
                                + "directory and loading them from there. Exception message is: "
                                + e.getMessage());
            }
        }
        Thread.currentThread().setContextClassLoader(cl);
    }

    private ComponentInfo installGmlGeoX() throws InitializationException {
        final Context ctx = new Context();
        final RepoManager repoManger = new RepoManager(ctx);
        try {
            repoManger.delete("de.interactive_instruments.etf.bsxm.GmlGeoX");
        } catch (QueryException e) {
            ExcUtils.suppress(e);
        }
        try {
            // Extract gmlGeoX to temporary directory and install it
            final IFile tmpGmlGeoXFile = IFile.createTempFile("gmlgeox", "etf.jar");
            IoUtils.copyResourceToFile(this, "/plugins/etf-gmlgeox.jar", tmpGmlGeoXFile);
            String v;
            try {
                v = JarUtils.getManifest(tmpGmlGeoXFile).getMainAttributes().getValue("Implementation-Version");
            } catch (IOException e) {
                v = "unknown";
            }
            final String version = v;

            final ComponentInfo gmlGeoXInfo = new ComponentInfo() {
                @Override
                public EID getId() {
                    return EidFactory.getDefault().createUUID("etf-GmlGeoX");
                }

                @Override
                public String getName() {
                    return "GmlGeoX";
                }

                @Override
                public String getVersion() {
                    return version;
                }

                @Override
                public String getVendor() {
                    return "interactive instruments GmbH";
                }

                @Override
                public String getDescription() {
                    return "BaseX test driver extension module "
                            + "to validate GML geometries within XML documents, "
                            + "perform geometry operations and index GML geometries";
                }
            };
            repoManger.install(tmpGmlGeoXFile.getAbsolutePath());
            tmpGmlGeoXFile.delete();

            return gmlGeoXInfo;
        } catch (IOException | QueryException e) {
            throw new InitializationException("GmlGeoX installation failed: ", e);
        }
    }

    @Override
    protected void doRelease() {

    }
}
