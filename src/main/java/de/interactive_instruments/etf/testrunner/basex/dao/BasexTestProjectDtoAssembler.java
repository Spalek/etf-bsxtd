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
package de.interactive_instruments.etf.testrunner.basex.dao;

import de.interactive_instruments.Version;
import de.interactive_instruments.etf.dal.assembler.EntityAssembler;
import de.interactive_instruments.etf.dal.dto.plan.TestProjectDto;
import de.interactive_instruments.etf.model.item.EID;
import de.interactive_instruments.etf.model.item.ModelItem;
import de.interactive_instruments.etf.model.plan.TestObjectResourceType;
import de.interactive_instruments.etf.model.plan.TestProject;
import de.interactive_instruments.properties.PropertyHolder;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembler for assembling Basex testproject entities
 *
 * @author J. Herrmann ( herrmann <aT) interactive-instruments (doT> de )
 */
public class BasexTestProjectDtoAssembler implements EntityAssembler<TestProjectDto, TestProject> {

  @Override
  final public List<TestProject> assembleEntities(Collection<TestProjectDto> collection) {
    return null;
  }

  @Override
  final public TestProject assembleEntity(TestProjectDto dto) {
    return new BasexTestProject(dto.getId(), dto.getLabel(), dto.getUri(), dto.getDescription(),
        dto.getVersionData().toVersionData(), dto.getSupportedResourceTypes(), dto.getProperties());
  }
}
