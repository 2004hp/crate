/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.reference.information;

import io.crate.metadata.RowContextCollectorExpression;
import io.crate.metadata.Schemas;
import org.apache.lucene.util.BytesRef;


public abstract class InformationColumnsExpression<T>
        extends RowContextCollectorExpression<ColumnContext, T> {

    public static class ColumnsSchemaNameExpression extends InformationColumnsExpression<BytesRef> {

        static final BytesRef DOC_SCHEMA_INFO = new BytesRef(Schemas.DEFAULT_SCHEMA_NAME);

        @Override
        public BytesRef copyValue() {
            String schema = row.info.ident().tableIdent().schema();
            if (schema == null) {
                return DOC_SCHEMA_INFO;
            }
            return new BytesRef(schema);
        }
    }

    public static class ColumnsTableNameExpression extends InformationColumnsExpression<BytesRef> {

        @Override
        public BytesRef copyValue() {
            assert row.info.ident().tableIdent().name() != null : "table name can't be null";
            return new BytesRef(row.info.ident().tableIdent().name());
        }
    }

    public static class ColumnsColumnNameExpression extends InformationColumnsExpression<BytesRef> {

        @Override
        public BytesRef copyValue() {
            assert row.info.ident().tableIdent().name() != null : "column name name can't be null";
            return new BytesRef(row.info.ident().columnIdent().sqlFqn());
        }
    }

    public static class ColumnsOrdinalExpression extends InformationColumnsExpression<Short> {

        @Override
        public Short copyValue() {
            return row.ordinal;
        }
    }

    public static class ColumnsDataTypeExpression extends InformationColumnsExpression<BytesRef> {

        @Override
        public BytesRef copyValue() {
            assert row.info.type() != null && row.info.type().getName() != null : "columns must always have a type and the type must have a name";
            return new BytesRef(row.info.type().getName());
        }
    }
}
