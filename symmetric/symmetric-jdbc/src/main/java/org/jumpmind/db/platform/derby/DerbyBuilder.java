package org.jumpmind.db.platform.derby;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.sql.Types;
import java.util.Iterator;
import java.util.List;

import org.jumpmind.db.IDatabasePlatform;
import org.jumpmind.db.alter.AddColumnChange;
import org.jumpmind.db.alter.TableChange;
import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Database;
import org.jumpmind.db.model.IIndex;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.model.TypeMap;
import org.jumpmind.db.platform.AbstractDdlBuilder;
import org.jumpmind.db.platform.PlatformUtils;
import org.jumpmind.log.Log;

/*
 * The SQL Builder for Derby.
 */
public class DerbyBuilder extends AbstractDdlBuilder {

    public DerbyBuilder(Log log, IDatabasePlatform platform) {
        super(log, platform);
        addEscapedCharSequence("'", "''");
    }

    @Override
    protected String getNativeDefaultValue(Column column) {
        if ((column.getTypeCode() == Types.BIT)
                || (PlatformUtils.supportsJava14JdbcTypes() && (column.getTypeCode() == PlatformUtils
                        .determineBooleanTypeCode()))) {
            return getDefaultValueHelper().convert(column.getDefaultValue(), column.getTypeCode(),
                    Types.SMALLINT).toString();
        } else {
            return super.getNativeDefaultValue(column);
        }
    }

    @Override
    public String getSelectLastIdentityValues(Table table) {
        return "VALUES IDENTITY_VAL_LOCAL()";
    }

    @Override
    protected void writeColumnAutoIncrementStmt(Table table, Column column, StringBuilder ddl)  {
        ddl.append("GENERATED BY DEFAULT AS IDENTITY");
    }

    @Override
    public void writeExternalIndexDropStmt(Table table, IIndex index, StringBuilder ddl)  {
        // Index names in Derby are unique to a schema and hence Derby does not
        // use the ON <tablename> clause
        ddl.append("DROP INDEX ");
        printIdentifier(getIndexName(index), ddl);
        printEndOfStatement(ddl);
    }

    @Override
    protected void writeCastExpression(Column sourceColumn, Column targetColumn, StringBuilder ddl)  {
        String sourceNativeType = getBareNativeType(sourceColumn);
        String targetNativeType = getBareNativeType(targetColumn);

        if (sourceNativeType.equals(targetNativeType)) {
            printIdentifier(getColumnName(sourceColumn), ddl);
        } else {
            // Derby currently has the limitation that it cannot convert numeric
            // values
            // to VARCHAR, though it can convert them to CHAR
            if (TypeMap.isNumericType(sourceColumn.getTypeCode())
                    && "VARCHAR".equalsIgnoreCase(targetNativeType)) {
                targetNativeType = "CHAR";
            }

            ddl.append(targetNativeType);
            ddl.append("(");
            printIdentifier(getColumnName(sourceColumn), ddl);
            ddl.append(")");
        }
    }

    @Override
    protected void processTableStructureChanges(Database currentModel, Database desiredModel,
            Table sourceTable, Table targetTable, List<TableChange> changes, StringBuilder ddl)  {
        // Derby provides a way to alter the size of a column but it is limited
        // (no pk or fk columns, only for VARCHAR columns), so we don't use it
        for (Iterator<TableChange> changeIt = changes.iterator(); changeIt.hasNext();) {
            TableChange change = changeIt.next();

            if (change instanceof AddColumnChange) {
                AddColumnChange addColumnChange = (AddColumnChange) change;

                // Derby can only add not insert columns, and the columns
                // cannot be identity columns
                if (addColumnChange.isAtEnd() && !addColumnChange.getNewColumn().isAutoIncrement()) {
                    processChange(currentModel, desiredModel, addColumnChange, ddl);
                    changeIt.remove();
                }
            }
        }
        super.processTableStructureChanges(currentModel, desiredModel, sourceTable, targetTable,
                changes, ddl);
    }

    /*
     * Processes the addition of a column to a table.
     */
    protected void processChange(Database currentModel, Database desiredModel,
            AddColumnChange change, StringBuilder ddl)  {
        ddl.append("ALTER TABLE ");
        printlnIdentifier(getTableName(change.getChangedTable().getName()), ddl);
        printIndent(ddl);
        ddl.append("ADD COLUMN ");
        writeColumn(change.getChangedTable(), change.getNewColumn(), ddl);
        printEndOfStatement(ddl);
        change.apply(currentModel, platform.isDelimitedIdentifierModeOn());
    }
    
}
