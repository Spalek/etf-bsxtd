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

import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.util.Set;

import de.interactive_instruments.Releasable;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
public interface DatabaseVisitor extends Releasable, FileVisitor<Path> {

    Set<String> getSkippedFiles();

    /**
     * Number of databases.
     *
     * @return number of databases
     */
    int getDbCount();

    /**
     * Total size of files.
     *
     * @return files size in bytes
     */
    long getSize();

    /**
     * Number of files.
     *
     * @return number of files
     */
    long getFileCount();
}
