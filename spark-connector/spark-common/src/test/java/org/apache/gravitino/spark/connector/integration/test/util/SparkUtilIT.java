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

package org.apache.gravitino.spark.connector.integration.test.util;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.analysis.ResolvedTable;
import org.apache.spark.sql.catalyst.plans.logical.CommandResult;
import org.apache.spark.sql.catalyst.plans.logical.DescribeRelation;
import org.junit.jupiter.api.Assertions;

/**
 * Provides helper methods to execute SparkSQL and get SparkSQL result, will be reused by SparkIT
 * and IcebergRESTServiceIT
 *
 * <p>Referred from spark/v3.4/spark/src/test/java/org/apache/iceberg/spark/SparkTestBase.java
 */
public abstract class SparkUtilIT extends BaseIT {
  protected static final String NULL_STRING = "NULL";

  protected abstract SparkSession getSparkSession();

  protected final String TIME_ZONE_UTC = "UTC";

  protected Set<String> getCatalogs() {
    return convertToStringSet(sql("SHOW CATALOGS"), 0);
  }

  protected Set<String> getDatabases() {
    return convertToStringSet(sql("SHOW DATABASES"), 0);
  }

  protected Set<String> listTableNames() {
    // the first column is namespace, the second column is table name
    return convertToStringSet(sql("SHOW TABLES"), 1);
  }

  protected Set<String> listTableNames(String database) {
    return convertToStringSet(sql("SHOW TABLES in " + database), 1);
  }

  protected void dropDatabaseIfExists(String database) {
    sql("DROP DATABASE IF EXISTS " + database);
  }

  // Specify Location explicitly because the default location is local HDFS, Spark will expand the
  // location to HDFS.
  // However, Paimon does not support create a database with a specified location.
  protected void createDatabaseIfNotExists(String database, String provider) {
    String locationClause =
        "lakehouse-paimon".equalsIgnoreCase(provider) || provider.startsWith("jdbc")
            ? ""
            : String.format("LOCATION '/user/hive/%s'", database);
    sql(String.format("CREATE DATABASE IF NOT EXISTS %s %s", database, locationClause));
  }

  protected Map<String, String> getDatabaseMetadata(String database) {
    return convertToStringMap(sql("DESC DATABASE EXTENDED " + database));
  }

  protected List<Object[]> sql(String query) {
    List<Row> rows = getSparkSession().sql(query).collectAsList();
    return rowsToJava(rows);
  }

  // columns data are joined by ','
  protected List<String> getTableData(String tableName) {
    return getQueryData(getSelectAllSql(tableName));
  }

  private String sparkObjectToString(Object item) {
    if (item instanceof Object[]) {
      return Arrays.stream((Object[]) item)
          .map(i -> sparkObjectToString(i))
          .collect(Collectors.joining(","));
    } else if (item instanceof Timestamp) {
      Timestamp timestamp = (Timestamp) item;
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      sdf.setTimeZone(TimeZone.getTimeZone(TIME_ZONE_UTC));
      return sdf.format(timestamp);
    } else if (item == null) {
      return NULL_STRING;
    } else {
      return item.toString();
    }
  }

  protected List<String> getQueryData(String querySql) {
    return sql(querySql).stream()
        .map(
            line ->
                Arrays.stream(line)
                    .map(item -> sparkObjectToString(item))
                    .collect(Collectors.joining(",")))
        .collect(Collectors.toList());
  }

  // columns data are joined by ','
  protected List<String> getTableMetadata(String getTableMetadataSql) {
    return getQueryData(getTableMetadataSql);
  }

  // Create SparkTableInfo from SparkBaseTable retrieved from LogicalPlan.
  protected SparkTableInfo getTableInfo(String tableName) {
    Dataset ds = getSparkSession().sql("DESC TABLE EXTENDED " + tableName);
    CommandResult result = (CommandResult) ds.logicalPlan();
    DescribeRelation relation = (DescribeRelation) result.commandLogicalPlan();
    ResolvedTable table = (ResolvedTable) relation.child();
    return SparkTableInfo.create(table.table());
  }

  protected List<Object[]> getTablePartitions(String tableName) {
    return sql("SHOW PARTITIONS " + tableName);
  }

  protected void dropTableIfExists(String tableName) {
    sql("DROP TABLE IF EXISTS " + tableName);
  }

  protected boolean tableExists(String tableName) {
    try {
      SparkTableInfo tableInfo = getTableInfo(tableName);
      Assertions.assertEquals(tableName, tableInfo.getTableName());
      return true;
    } catch (Exception e) {
      if (e instanceof AnalysisException) {
        return false;
      }
      throw e;
    }
  }

  protected void createTableAsSelect(String tableName, String newName) {
    sql(String.format("CREATE TABLE %s AS SELECT * FROM %s", newName, tableName));
  }

  protected void insertTableAsSelect(String tableName, String newName) {
    sql(String.format("INSERT INTO TABLE %s SELECT * FROM %s", newName, tableName));
  }

  protected static String getSelectAllSqlWithOrder(String tableName, String orderByColumn) {
    return String.format("SELECT * FROM %s ORDER BY %s", tableName, orderByColumn);
  }

  private static String getSelectAllSql(String tableName) {
    return String.format("SELECT * FROM %s", tableName);
  }

  protected List<Object[]> rowsToJava(List<Row> rows) {
    return rows.stream().map(this::toJava).collect(Collectors.toList());
  }

  private Object[] toJava(Row row) {
    return IntStream.range(0, row.size())
        .mapToObj(
            pos -> {
              if (row.isNullAt(pos)) {
                return null;
              }
              Object value = row.get(pos);
              if (value instanceof Row) {
                return toJava((Row) value);
              } else if (value instanceof scala.collection.Seq) {
                return row.getList(pos);
              } else if (value instanceof scala.collection.Map) {
                return row.getJavaMap(pos);
              }
              return value;
            })
        .toArray(Object[]::new);
  }

  private static Set<String> convertToStringSet(List<Object[]> objects, int index) {
    return objects.stream().map(row -> String.valueOf(row[index])).collect(Collectors.toSet());
  }

  private static Map<String, String> convertToStringMap(List<Object[]> objects) {
    return objects.stream()
        .collect(
            Collectors.toMap(
                row -> String.valueOf(row[0]),
                row -> String.valueOf(row[1]),
                (oldValue, newValue) -> oldValue));
  }
}
