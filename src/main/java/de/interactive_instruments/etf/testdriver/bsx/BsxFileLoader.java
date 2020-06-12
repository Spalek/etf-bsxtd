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

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;

import javax.xml.xpath.XPathExpressionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.interactive_instruments.UriUtils;
import de.interactive_instruments.etf.EtfXpathEvaluator;
import de.interactive_instruments.etf.component.loaders.AbstractItemFileLoader;
import de.interactive_instruments.etf.component.loaders.ItemFileLoaderResultListener;
import de.interactive_instruments.etf.dal.dao.Dao;
import de.interactive_instruments.etf.dal.dao.StreamWriteDao;
import de.interactive_instruments.etf.dal.dto.test.ExecutableTestSuiteDto;
import de.interactive_instruments.exceptions.ExcUtils;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;
import de.interactive_instruments.exceptions.StorageException;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
class BsxFileLoader extends AbstractItemFileLoader<ExecutableTestSuiteDto> {

    private final StreamWriteDao<ExecutableTestSuiteDto> writeDao;
    private final static Logger logger = LoggerFactory.getLogger(BsxFileLoader.class);

    BsxFileLoader(final ItemFileLoaderResultListener<ExecutableTestSuiteDto> loaderResultListener,
            final Path path,
            final Dao<ExecutableTestSuiteDto> writeDao) {
        super(loaderResultListener, 400, path.toFile());
        this.writeDao = (StreamWriteDao<ExecutableTestSuiteDto>) writeDao;
    }

    @Override
    protected boolean doPrepare() {
        try {
            dependsOn(EtfXpathEvaluator.evalEids(
                    "/etf:ExecutableTestSuite[1]/etf:dependencies[1]/etf:executableTestSuite/@ref", file));
            dependsOn(EtfXpathEvaluator.evalEids(
                    "/etf:ExecutableTestSuite[1]/etf:translationTemplateBundle/@ref", file));
            dependsOn(EtfXpathEvaluator.evalEids(
                    "/etf:ExecutableTestSuite[1]/etf:tags[1]/etf:tag/@ref", file));
            dependsOn(EtfXpathEvaluator.evalEids(
                    "/etf:ExecutableTestSuite[1]/etf:supportedTestObjectTypes[1]/etf:testObjectType/@ref", file));
        } catch (XPathExpressionException | IOException e) {
            return false;
        }
        return true;
    }

    @Override
    protected ExecutableTestSuiteDto doBuild() {
        try {
            final String hash = UriUtils.hashFromTimestampOrContent(file.toURI());
            final FileInputStream fileInputStream = new FileInputStream(file);
            return writeDao.add(fileInputStream, dto -> {
                dto.setItemHash(hash);
                dto.setLocalPath(file.getAbsolutePath());
                return dto;
            });
        } catch (IOException e) {
            logger.error("Error creating Executable Test Suite from file {}", file, e);
        }
        return null;
    }

    @Override
    protected void doRelease() {
        if (getResult() != null) {
            try {
                writeDao.delete(getResult().getId());
            } catch (StorageException | ObjectWithIdNotFoundException e) {
                ExcUtils.suppress(e);
            }
        }
    }
}
