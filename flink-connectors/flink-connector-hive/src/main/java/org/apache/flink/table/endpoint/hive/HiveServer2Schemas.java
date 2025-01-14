/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.endpoint.hive;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;

import java.util.Arrays;
import java.util.Collections;

/** Schemas for the HiveServer2 Endpoint result. */
public class HiveServer2Schemas {

    /** Schema for {@link HiveServer2Endpoint#GetCatalogs}. */
    public static final ResolvedSchema GET_CATALOGS_SCHEMA =
            new ResolvedSchema(
                    Collections.singletonList(
                            Column.physical("TABLE_CAT", DataTypes.STRING())
                                    .withComment("Catalog name. NULL if not applicable.")),
                    Collections.emptyList(),
                    null);

    /** Schema for {@link HiveServer2Endpoint#GetSchemas}. */
    public static final ResolvedSchema GET_SCHEMAS_SCHEMA =
            new ResolvedSchema(
                    Arrays.asList(
                            Column.physical("TABLE_SCHEMA", DataTypes.STRING())
                                    .withComment("Schema name. NULL if not applicable."),
                            Column.physical("TABLE_CAT", DataTypes.STRING())
                                    .withComment("Catalog name. NULL if not applicable")),
                    Collections.emptyList(),
                    null);

    /** Schema for {@link HiveServer2Endpoint#GetTables}. */
    public static final ResolvedSchema GET_TABLES_SCHEMA =
            new ResolvedSchema(
                    Collections.unmodifiableList(
                            Arrays.asList(
                                    Column.physical("TABLE_CAT", DataTypes.STRING())
                                            .withComment("Catalog name. NULL if not applicable."),
                                    Column.physical("TABLE_SCHEMA", DataTypes.STRING())
                                            .withComment("Schema name. NULL if not applicable."),
                                    Column.physical("TABLE_NAME", DataTypes.STRING())
                                            .withComment("Table name. NULL if not applicable."),
                                    Column.physical("TABLE_TYPE", DataTypes.STRING())
                                            .withComment(
                                                    "The table type, e.g. \"TABLE\", \"VIEW\", etc."),
                                    Column.physical("REMARKS", DataTypes.STRING())
                                            .withComment("Comments about the table."),
                                    Column.physical("TYPE_CAT", DataTypes.STRING())
                                            .withComment("The types catalog."),
                                    Column.physical("TYPE_SCHEM", DataTypes.STRING())
                                            .withComment("The types schema."),
                                    Column.physical("TYPE_NAME", DataTypes.STRING())
                                            .withComment("Type name."),
                                    Column.physical("SELF_REFERENCING_COL_NAME", DataTypes.STRING())
                                            .withComment(
                                                    "Name of the designated \"identifier\" column of a typed table."),
                                    Column.physical("REF_GENERATION", DataTypes.STRING())
                                            .withComment(
                                                    "Specifies how values in SELF_REFERENCING_COL_NAME are created."))),
                    Collections.emptyList(),
                    null);
}
