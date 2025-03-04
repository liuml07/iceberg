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

package org.apache.iceberg.spark.extensions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.spark.SparkCatalog;
import org.apache.iceberg.spark.SparkSessionCatalog;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoder;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.apache.iceberg.DataOperations.DELETE;
import static org.apache.iceberg.DataOperations.OVERWRITE;
import static org.apache.iceberg.SnapshotSummary.ADDED_DELETE_FILES_PROP;
import static org.apache.iceberg.SnapshotSummary.ADDED_FILES_PROP;
import static org.apache.iceberg.SnapshotSummary.CHANGED_PARTITION_COUNT_PROP;
import static org.apache.iceberg.SnapshotSummary.DELETED_FILES_PROP;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.PARQUET_VECTORIZATION_ENABLED;
import static org.apache.iceberg.TableProperties.WRITE_DISTRIBUTION_MODE;
import static org.apache.iceberg.TableProperties.WRITE_DISTRIBUTION_MODE_HASH;
import static org.apache.iceberg.TableProperties.WRITE_DISTRIBUTION_MODE_NONE;
import static org.apache.iceberg.TableProperties.WRITE_DISTRIBUTION_MODE_RANGE;

@RunWith(Parameterized.class)
public abstract class SparkRowLevelOperationsTestBase extends SparkExtensionsTestBase {

  private static final Random RANDOM = ThreadLocalRandom.current();

  protected final String fileFormat;
  protected final boolean vectorized;
  protected final String distributionMode;

  public SparkRowLevelOperationsTestBase(String catalogName, String implementation,
                                         Map<String, String> config, String fileFormat,
                                         boolean vectorized,
                                         String distributionMode) {
    super(catalogName, implementation, config);
    this.fileFormat = fileFormat;
    this.vectorized = vectorized;
    this.distributionMode = distributionMode;
  }

  @Parameters(name = "catalogName = {0}, implementation = {1}, config = {2}," +
      " format = {3}, vectorized = {4}, distributionMode = {5}")
  public static Object[][] parameters() {
    return new Object[][] {
        { "testhive", SparkCatalog.class.getName(),
            ImmutableMap.of(
                "type", "hive",
                "default-namespace", "default"
            ),
            "orc",
            true,
            WRITE_DISTRIBUTION_MODE_NONE
        },
        { "testhadoop", SparkCatalog.class.getName(),
            ImmutableMap.of(
                "type", "hadoop"
            ),
            "parquet",
            RANDOM.nextBoolean(),
            WRITE_DISTRIBUTION_MODE_HASH
        },
        { "spark_catalog", SparkSessionCatalog.class.getName(),
            ImmutableMap.of(
                "type", "hive",
                "default-namespace", "default",
                "clients", "1",
                "parquet-enabled", "false",
                "cache-enabled", "false" // Spark will delete tables using v1, leaving the cache out of sync
            ),
            "avro",
            false,
            WRITE_DISTRIBUTION_MODE_RANGE
        }
    };
  }

  protected abstract Map<String, String> extraTableProperties();

  protected void initTable() {
    sql("ALTER TABLE %s SET TBLPROPERTIES('%s' '%s')", tableName, DEFAULT_FILE_FORMAT, fileFormat);
    sql("ALTER TABLE %s SET TBLPROPERTIES('%s' '%s')", tableName, WRITE_DISTRIBUTION_MODE, distributionMode);

    switch (fileFormat) {
      case "parquet":
        sql("ALTER TABLE %s SET TBLPROPERTIES('%s' '%b')", tableName, PARQUET_VECTORIZATION_ENABLED, vectorized);
        break;
      case "orc":
        Assert.assertTrue(vectorized);
        break;
      case "avro":
        Assert.assertFalse(vectorized);
        break;
    }

    Map<String, String> props = extraTableProperties();
    props.forEach((prop, value) -> {
      sql("ALTER TABLE %s SET TBLPROPERTIES('%s' '%s')", tableName, prop, value);
    });
  }

  protected void createAndInitTable(String schema) {
    createAndInitTable(schema, null);
  }

  protected void createAndInitTable(String schema, String jsonData) {
    sql("CREATE TABLE %s (%s) USING iceberg", tableName, schema);
    initTable();

    if (jsonData != null) {
      try {
        Dataset<Row> ds = toDS(schema, jsonData);
        ds.writeTo(tableName).append();
      } catch (NoSuchTableException e) {
        throw new RuntimeException("Failed to write data", e);
      }
    }
  }

  protected void append(String table, String jsonData) {
    append(table, null, jsonData);
  }

  protected void append(String table, String schema, String jsonData) {
    try {
      Dataset<Row> ds = toDS(schema, jsonData);
      ds.coalesce(1).writeTo(table).append();
    } catch (NoSuchTableException e) {
      throw new RuntimeException("Failed to write data", e);
    }
  }

  protected void createOrReplaceView(String name, String jsonData) {
    createOrReplaceView(name, null, jsonData);
  }

  protected void createOrReplaceView(String name, String schema, String jsonData) {
    Dataset<Row> ds = toDS(schema, jsonData);
    ds.createOrReplaceTempView(name);
  }

  protected <T> void createOrReplaceView(String name, List<T> data, Encoder<T> encoder) {
    spark.createDataset(data, encoder).createOrReplaceTempView(name);
  }

  private Dataset<Row> toDS(String schema, String jsonData) {
    List<String> jsonRows = Arrays.stream(jsonData.split("\n"))
        .filter(str -> str.trim().length() > 0)
        .collect(Collectors.toList());
    Dataset<String> jsonDS = spark.createDataset(jsonRows, Encoders.STRING());

    if (schema != null) {
      return spark.read().schema(schema).json(jsonDS);
    } else {
      return spark.read().json(jsonDS);
    }
  }

  protected void validateDelete(Snapshot snapshot, String changedPartitionCount, String deletedDataFiles) {
    validateSnapshot(snapshot, DELETE, changedPartitionCount, deletedDataFiles, null, null);
  }

  protected void validateCopyOnWrite(Snapshot snapshot, String changedPartitionCount,
                                     String deletedDataFiles, String addedDataFiles) {
    validateSnapshot(snapshot, OVERWRITE, changedPartitionCount, deletedDataFiles, null, addedDataFiles);
  }

  protected void validateMergeOnRead(Snapshot snapshot, String changedPartitionCount,
                                     String addedDeleteFiles, String addedDataFiles) {
    validateSnapshot(snapshot, OVERWRITE, changedPartitionCount, null, addedDeleteFiles, addedDataFiles);
  }

  protected void validateSnapshot(Snapshot snapshot, String operation, String changedPartitionCount,
                                  String deletedDataFiles, String addedDeleteFiles, String addedDataFiles) {
    Assert.assertEquals("Operation must match", operation, snapshot.operation());
    validateProperty(snapshot, CHANGED_PARTITION_COUNT_PROP, changedPartitionCount);
    validateProperty(snapshot, DELETED_FILES_PROP, deletedDataFiles);
    validateProperty(snapshot, ADDED_DELETE_FILES_PROP, addedDeleteFiles);
    validateProperty(snapshot, ADDED_FILES_PROP, addedDataFiles);
  }

  protected void validateProperty(Snapshot snapshot, String property, Set<String> expectedValues) {
    String actual = snapshot.summary().get(property);
    Assert.assertTrue("Snapshot property " + property + " has unexpected value, actual = " +
            actual + ", expected one of : " + String.join(",", expectedValues),
        expectedValues.contains(actual));
  }

  protected void validateProperty(Snapshot snapshot, String property, String expectedValue) {
    String actual = snapshot.summary().get(property);
    Assert.assertEquals("Snapshot property " + property + " has unexpected value.", expectedValue, actual);
  }

  protected void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
