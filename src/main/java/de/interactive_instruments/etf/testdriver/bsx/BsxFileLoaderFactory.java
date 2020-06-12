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

import static de.interactive_instruments.etf.testdriver.bsx.Types.TEST_ITEM_TYPES;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import de.interactive_instruments.etf.EtfConstants;
import de.interactive_instruments.etf.component.loaders.AbstractItemFileLoaderFactory;
import de.interactive_instruments.etf.component.loaders.LoadingContext;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.test.ExecutableTestSuiteDto;
import de.interactive_instruments.etf.dal.dto.test.TestItemTypeDto;
import de.interactive_instruments.etf.model.DefaultEidSet;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidSet;
import de.interactive_instruments.etf.testdriver.ExecutableTestSuiteLoader;
import de.interactive_instruments.exceptions.ExcUtils;
import de.interactive_instruments.exceptions.InitializationException;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;
import de.interactive_instruments.exceptions.StorageException;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.properties.ConfigProperties;
import de.interactive_instruments.properties.ConfigPropertyHolder;

/**
 * Used to propagate: - Test Object Types (static), - Test Item Types (static), - Component Info (static), - Executable Test Suites (dynamically reloaded on change), - Translation Template Bundles (dynamically reloaded on change)
 *
 * The Component Info is propagated by the Test Driver
 *
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
public class BsxFileLoaderFactory extends AbstractItemFileLoaderFactory<ExecutableTestSuiteDto>
        implements ExecutableTestSuiteLoader {

    private final ConfigProperties configProperties;
    private final DataStorage dataStorageCallback;
    private boolean initialized;

    /**
     * Default constructor.
     */
    public BsxFileLoaderFactory(final DataStorage dataStorageCallback) {
        this.configProperties = new ConfigProperties(EtfConstants.ETF_PROJECTS_DIR);
        this.dataStorageCallback = dataStorageCallback;
    }

    @Override
    public void init()
            throws ConfigurationException, InitializationException {

        this.configProperties.expectAllRequiredPropertiesSet();
        if (this.loadingContext == null) {
            throw new InitializationException("LoadingContext not set");
        }

        this.loadingContext.getItemFileObserverRegistry().register(
                this.configProperties.getPropertyAsFile(EtfConstants.ETF_PROJECTS_DIR).toPath(),
                Collections.singletonList(this));

        // First propagate static types
        final WriteDao<TestItemTypeDto> testItemTypeDao = ((WriteDao<TestItemTypeDto>) dataStorageCallback
                .getDao(TestItemTypeDto.class));

        Collection<EID> eidsToRegister = new ArrayList<EID>();
        for (EID eid : TEST_ITEM_TYPES.keySet()) {
            try {
                loadingContext.getItemRegistry().lookup(Collections.singleton(eid));
            } catch (ObjectWithIdNotFoundException e) {
                eidsToRegister.add(eid);
            }
        }

        try {
            for (EID eid : eidsToRegister) {
                this.loadingContext.getItemRegistry().register(Collections.singleton(TEST_ITEM_TYPES.get(eid)));
            }
            testItemTypeDao.deleteAllExisting(TEST_ITEM_TYPES.keySet());
            testItemTypeDao.addAll(TEST_ITEM_TYPES.values());
        } catch (final StorageException e) {
            try {
                testItemTypeDao.deleteAllExisting(TEST_ITEM_TYPES.keySet());
            } catch (StorageException e3) {
                ExcUtils.suppress(e3);
            }
            throw new InitializationException(e);
        }
        this.initialized = true;
    }

    @Override
    public ConfigPropertyHolder getConfigurationProperties() {
        return configProperties;
    }

    @Override
    public EidSet<ExecutableTestSuiteDto> getExecutableTestSuites() {
        return new DefaultEidSet<>(this.getItems());
    }

    @Override
    public void setLoadingContext(final LoadingContext loadingContext) {
        this.loadingContext = loadingContext;
    }

    @Override
    public boolean isInitialized() {
        return this.initialized;
    }

    @Override
    public void release() {
        this.initialized = false;
        this.loadingContext.getItemFileObserverRegistry().deregister(
                Collections.singletonList(this));
    }

    @Override
    public boolean couldHandle(final Path path) {
        return path.toString().endsWith(BsxConstants.ETS_DEF_FILE_SUFFIX);
    }

    @Override
    public FileChangeListener load(final Path path) {
        return new BsxFileLoader(this, path,
                dataStorageCallback.getDao(ExecutableTestSuiteDto.class)).setItemRegistry(this.getItemRegistry());
    }
}
