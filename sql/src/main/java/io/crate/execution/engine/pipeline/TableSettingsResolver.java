/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.engine.pipeline;

import io.crate.exceptions.TableUnknownException;
import io.crate.metadata.PartitionName;
import io.crate.metadata.TableIdent;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;

public final class TableSettingsResolver {

    public static Settings get(MetaData metaData, TableIdent tableIdent, boolean partitioned) {
        if (partitioned) {
            return forPartitionedTable(metaData, tableIdent);
        }
        return forTable(metaData, tableIdent);
    }

    private static Settings forTable(MetaData metaData, TableIdent tableIdent) {
        IndexMetaData indexMetaData = metaData.index(tableIdent.indexName());
        if (indexMetaData == null) {
            throw new IndexNotFoundException(tableIdent.indexName());
        }
        return indexMetaData.getSettings();
    }

    private static Settings forPartitionedTable(MetaData metaData, TableIdent tableIdent) {
        String templateName = PartitionName.templateName(tableIdent.schema(), tableIdent.name());
        IndexTemplateMetaData templateMetaData = metaData.templates().get(templateName);
        if (templateMetaData == null) {
            throw new TableUnknownException(tableIdent);
        }
        return templateMetaData.getSettings();
    }

    private TableSettingsResolver() {
    }
}
