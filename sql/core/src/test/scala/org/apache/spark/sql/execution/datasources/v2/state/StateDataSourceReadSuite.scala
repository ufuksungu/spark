/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.datasources.v2.state

import java.io.{File, FileWriter}

import org.scalatest.Assertions

import org.apache.spark.SparkUnsupportedOperationException
import org.apache.spark.io.CompressionCodec
import org.apache.spark.sql.{AnalysisException, DataFrame, Encoders, Row}
import org.apache.spark.sql.catalyst.expressions.{BoundReference, GenericInternalRow}
import org.apache.spark.sql.catalyst.plans.physical.HashPartitioning
import org.apache.spark.sql.execution.datasources.v2.state.utils.SchemaUtil
import org.apache.spark.sql.execution.streaming.{CommitLog, MemoryStream, OffsetSeqLog}
import org.apache.spark.sql.execution.streaming.state.{HDFSBackedStateStoreProvider, RocksDBStateStoreProvider, StateStore}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.{IntegerType, StructType}

class StateDataSourceNegativeTestSuite extends StateDataSourceTestBase {
  import testImplicits._

  test("ERROR: read the state from stateless query") {
    withTempDir { tempDir =>
      val inputData = MemoryStream[Int]
      val df = inputData.toDF()
        .selectExpr("value", "value % 2 AS value2")

      testStream(df)(
        StartStream(checkpointLocation = tempDir.getAbsolutePath),
        AddData(inputData, 1, 2, 3, 4, 5),
        CheckLastBatch((1, 1), (2, 0), (3, 1), (4, 0), (5, 1)),
        AddData(inputData, 6, 7, 8),
        CheckLastBatch((6, 0), (7, 1), (8, 0))
      )

      intercept[IllegalArgumentException] {
        spark.read.format("statestore").load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: no committed batch on default batch ID") {
    withTempDir { tempDir =>
      runLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)

      val offsetLog = new OffsetSeqLog(spark,
        new File(tempDir.getAbsolutePath, "offsets").getAbsolutePath)
      val commitLog = new CommitLog(spark,
        new File(tempDir.getAbsolutePath, "commits").getAbsolutePath)

      offsetLog.purgeAfter(0)
      commitLog.purgeAfter(-1)

      intercept[IllegalStateException] {
        spark.read.format("statestore").load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: corrupted state schema file") {
    withTempDir { tempDir =>
      runLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)

      def rewriteStateSchemaFileToDummy(): Unit = {
        // Refer to the StateSchemaCompatibilityChecker for the path of state schema file
        val pathForSchema = Seq(
          "state", "0", StateStore.PARTITION_ID_TO_CHECK_SCHEMA.toString,
          "_metadata", "schema"
        ).foldLeft(tempDir) { case (file, dirName) =>
          new File(file, dirName)
        }

        assert(pathForSchema.exists())
        assert(pathForSchema.delete())

        val fileWriter = new FileWriter(pathForSchema)
        fileWriter.write("lol dummy corrupted schema file")
        fileWriter.close()

        assert(pathForSchema.exists())
      }

      rewriteStateSchemaFileToDummy()

      intercept[IllegalArgumentException] {
        spark.read.format("statestore").load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: path is not specified") {
    intercept[IllegalArgumentException] {
      spark.read.format("statestore").load()
    }
  }

  test("ERROR: operator ID specified to negative") {
    withTempDir { tempDir =>
      intercept[IllegalArgumentException] {
        spark.read.format("statestore")
          .option(StateSourceOptions.OPERATOR_ID, -1)
          // trick to bypass getting the last committed batch before validating operator ID
          .option(StateSourceOptions.BATCH_ID, 0)
          .load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: batch ID specified to negative") {
    withTempDir { tempDir =>
      intercept[IllegalArgumentException] {
        spark.read.format("statestore")
          .option(StateSourceOptions.BATCH_ID, -1)
          .load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: store name is empty") {
    withTempDir { tempDir =>
      intercept[IllegalArgumentException] {
        spark.read.format("statestore")
          .option(StateSourceOptions.STORE_NAME, "")
          // trick to bypass getting the last committed batch before validating operator ID
          .option(StateSourceOptions.BATCH_ID, 0)
          .load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: invalid value for joinSide option") {
    withTempDir { tempDir =>
      intercept[IllegalArgumentException] {
        spark.read.format("statestore")
          .option(StateSourceOptions.JOIN_SIDE, "both")
          // trick to bypass getting the last committed batch before validating operator ID
          .option(StateSourceOptions.BATCH_ID, 0)
          .load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: both options `joinSide` and `storeName` are specified") {
    withTempDir { tempDir =>
      intercept[IllegalArgumentException] {
        spark.read.format("statestore")
          .option(StateSourceOptions.JOIN_SIDE, "right")
          .option(StateSourceOptions.STORE_NAME, "right-keyToNumValues")
          // trick to bypass getting the last committed batch before validating operator ID
          .option(StateSourceOptions.BATCH_ID, 0)
          .load(tempDir.getAbsolutePath)
      }
    }
  }

  test("ERROR: trying to read state data as stream") {
    withTempDir { tempDir =>
      runLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)

      intercept[SparkUnsupportedOperationException] {
        spark.readStream.format("statestore").load(tempDir.getAbsolutePath)
          .writeStream.format("noop").start()
      }
    }
  }
}

/**
 * Here we build a combination of test criteria for
 * 1) number of shuffle partitions
 * 2) state store provider
 * 3) compression codec
 * and run one of the test to verify that above configs work.
 *
 * We are building 3 x 2 x 4 = 24 different test criteria, and it's probably waste of time
 * and resource to run all combinations for all times, hence we will randomly pick 5 tests
 * per run.
 */
class StateDataSourceSQLConfigSuite extends StateDataSourceTestBase {

  private val TEST_SHUFFLE_PARTITIONS = Seq(1, 3, 5)
  private val TEST_PROVIDERS = Seq(
    classOf[HDFSBackedStateStoreProvider].getName,
    classOf[RocksDBStateStoreProvider].getName
  )
  private val TEST_COMPRESSION_CODECS = CompressionCodec.ALL_COMPRESSION_CODECS

  private val ALL_COMBINATIONS = {
    val comb = for (
      part <- TEST_SHUFFLE_PARTITIONS;
      provider <- TEST_PROVIDERS;
      codec <- TEST_COMPRESSION_CODECS
    ) yield {
      (part, provider, codec)
    }
    scala.util.Random.shuffle(comb)
  }

  ALL_COMBINATIONS.take(5).foreach { case (part, provider, codec) =>
    val testName = s"Verify the read with config [part=$part][provider=$provider][codec=$codec]"
    test(testName) {
      withTempDir { tempDir =>
        withSQLConf(
          SQLConf.SHUFFLE_PARTITIONS.key -> part.toString,
          SQLConf.STATE_STORE_PROVIDER_CLASS.key -> provider,
          SQLConf.STATE_STORE_COMPRESSION_CODEC.key -> codec) {

          runLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)

          verifyLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)
        }
      }
    }
  }

  test("Use different configs than session config") {
    withTempDir { tempDir =>
      withSQLConf(
        SQLConf.SHUFFLE_PARTITIONS.key -> "3",
        SQLConf.STATE_STORE_PROVIDER_CLASS.key -> classOf[RocksDBStateStoreProvider].getName,
        SQLConf.STATE_STORE_COMPRESSION_CODEC.key -> "zstd") {

        runLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)
      }

      // Set the different values in session config, to validate whether state data source refers
      // to the config in offset log.
      withSQLConf(
        SQLConf.SHUFFLE_PARTITIONS.key -> "5",
        SQLConf.STATE_STORE_PROVIDER_CLASS.key -> classOf[HDFSBackedStateStoreProvider].getName,
        SQLConf.STATE_STORE_COMPRESSION_CODEC.key -> "lz4") {

        verifyLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)
      }
    }
  }

  private def verifyLargeDataStreamingAggregationQuery(checkpointLocation: String): Unit = {
    val operatorId = 0
    val batchId = 2

    val stateReadDf = spark.read
      .format("statestore")
      .option(StateSourceOptions.PATH, checkpointLocation)
      // explicitly specifying batch ID and operator ID to test out the functionality
      .option(StateSourceOptions.BATCH_ID, batchId)
      .option(StateSourceOptions.OPERATOR_ID, operatorId)
      .load()

    val resultDf = stateReadDf
      .selectExpr("key.groupKey AS key_groupKey", "value.count AS value_cnt",
        "value.sum AS value_sum", "value.max AS value_max", "value.min AS value_min")

    checkAnswer(
      resultDf,
      Seq(
        Row(0, 5, 60, 30, 0), // 0, 0, 10, 20, 30
        Row(1, 5, 65, 31, 1), // 1, 1, 11, 21, 31
        Row(2, 5, 70, 32, 2), // 2, 2, 12, 22, 32
        Row(3, 4, 72, 33, 3), // 3, 13, 23, 33
        Row(4, 4, 76, 34, 4), // 4, 14, 24, 34
        Row(5, 4, 80, 35, 5), // 5, 15, 25, 35
        Row(6, 4, 84, 36, 6), // 6, 16, 26, 36
        Row(7, 4, 88, 37, 7), // 7, 17, 27, 37
        Row(8, 4, 92, 38, 8), // 8, 18, 28, 38
        Row(9, 4, 96, 39, 9) // 9, 19, 29, 39
      )
    )
  }
}

class HDFSBackedStateDataSourceReadSuite extends StateDataSourceReadSuite {
  override def beforeAll(): Unit = {
    super.beforeAll()
    spark.conf.set(SQLConf.STATE_STORE_PROVIDER_CLASS.key,
      classOf[HDFSBackedStateStoreProvider].getName)
  }
}

class RocksDBStateDataSourceReadSuite extends StateDataSourceReadSuite {
  override def beforeAll(): Unit = {
    super.beforeAll()
    spark.conf.set(SQLConf.STATE_STORE_PROVIDER_CLASS.key,
      classOf[RocksDBStateStoreProvider].getName)
    spark.conf.set("spark.sql.streaming.stateStore.rocksdb.changelogCheckpointing.enabled",
      "false")
  }
}

class RocksDBWithChangelogCheckpointStateDataSourceReaderSuite extends StateDataSourceReadSuite {
  override def beforeAll(): Unit = {
    super.beforeAll()
    spark.conf.set(SQLConf.STATE_STORE_PROVIDER_CLASS.key,
      classOf[RocksDBStateStoreProvider].getName)
    spark.conf.set("spark.sql.streaming.stateStore.rocksdb.changelogCheckpointing.enabled",
      "true")
  }
}

abstract class StateDataSourceReadSuite extends StateDataSourceTestBase with Assertions {

  test("simple aggregation, state ver 1") {
    testStreamingAggregation(1)
  }

  test("simple aggregation, state ver 2") {
    testStreamingAggregation(2)
  }

  test("composite key aggregation, state ver 1") {
    testStreamingAggregationWithCompositeKey(1)
  }

  test("composite key aggregation, state ver 2") {
    testStreamingAggregationWithCompositeKey(2)
  }

  private def testStreamingAggregation(stateVersion: Int): Unit = {
    withSQLConf(SQLConf.STREAMING_AGGREGATION_STATE_FORMAT_VERSION.key -> stateVersion.toString) {
      withTempDir { tempDir =>
        runLargeDataStreamingAggregationQuery(tempDir.getAbsolutePath)

        val operatorId = 0
        val batchId = 2

        val stateReadDf = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          // explicitly specifying batch ID and operator ID to test out the functionality
          .option(StateSourceOptions.BATCH_ID, batchId)
          .option(StateSourceOptions.OPERATOR_ID, operatorId)
          .load()

        val resultDf = stateReadDf
          .selectExpr("key.groupKey AS key_groupKey", "value.count AS value_cnt",
            "value.sum AS value_sum", "value.max AS value_max", "value.min AS value_min")

        checkAnswer(
          resultDf,
          Seq(
            Row(0, 5, 60, 30, 0), // 0, 10, 20, 30
            Row(1, 5, 65, 31, 1), // 1, 11, 21, 31
            Row(2, 5, 70, 32, 2), // 2, 12, 22, 32
            Row(3, 4, 72, 33, 3), // 3, 13, 23, 33
            Row(4, 4, 76, 34, 4), // 4, 14, 24, 34
            Row(5, 4, 80, 35, 5), // 5, 15, 25, 35
            Row(6, 4, 84, 36, 6), // 6, 16, 26, 36
            Row(7, 4, 88, 37, 7), // 7, 17, 27, 37
            Row(8, 4, 92, 38, 8), // 8, 18, 28, 38
            Row(9, 4, 96, 39, 9) // 9, 19, 29, 39
          )
        )
      }
    }
  }

  private def testStreamingAggregationWithCompositeKey(stateVersion: Int): Unit = {
    withSQLConf(SQLConf.STREAMING_AGGREGATION_STATE_FORMAT_VERSION.key -> stateVersion.toString) {
      withTempDir { tempDir =>
        runCompositeKeyStreamingAggregationQuery(tempDir.getAbsolutePath)

        val stateReadDf = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          // skip version and operator ID to test out functionalities
          .load()

        val resultDf = stateReadDf
          .selectExpr("key.groupKey AS key_groupKey", "key.fruit AS key_fruit",
            "value.count AS value_cnt", "value.sum AS value_sum", "value.max AS value_max",
            "value.min AS value_min")

        checkAnswer(
          resultDf,
          Seq(
            Row(0, "Apple", 2, 6, 6, 0),
            Row(1, "Banana", 3, 9, 7, 1),
            Row(0, "Strawberry", 3, 12, 8, 2),
            Row(1, "Apple", 3, 15, 9, 3),
            Row(0, "Banana", 2, 14, 10, 4),
            Row(1, "Strawberry", 1, 5, 5, 5)
          )
        )
      }
    }
  }

  test("dropDuplicates") {
    withTempDir { tempDir =>
      runDropDuplicatesQuery(tempDir.getAbsolutePath)

      val stateReadDf = spark.read
        .format("statestore")
        .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
        // skip version and operator ID to test out functionalities
        .load()

      val resultDf = stateReadDf
        .selectExpr("key.value AS key_value", "CAST(key.eventTime AS LONG) AS key_eventTime_long")

      checkAnswer(resultDf, Seq(Row(45, 45)))

      val stateReadDf2 = spark.read
        .format("statestore")
        .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
        .option(StateSourceOptions.BATCH_ID, 0)
        .load()

      val resultDf2 = stateReadDf2
        .selectExpr("key.value AS key_value", "CAST(key.eventTime AS LONG) AS key_eventTime_long")

      checkAnswer(resultDf2,
        (10 to 15).map(idx => Row(idx, idx))
      )
    }
  }

  test("dropDuplicates with column specified") {
    withTempDir { tempDir =>
      runDropDuplicatesQueryWithColumnSpecified(tempDir.getAbsolutePath)

      val stateReadDf = spark.read
        .format("statestore")
        .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
        // skip version and operator ID to test out functionalities
        .load()

      val resultDf = stateReadDf
        .selectExpr("key.col1 AS key_col1")

      checkAnswer(resultDf, Seq(Row("A"), Row("B"), Row("C"), Row("D")))

      val stateReadDf2 = spark.read
        .format("statestore")
        .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
        .option(StateSourceOptions.BATCH_ID, 0)
        .load()

      val resultDf2 = stateReadDf2
        .selectExpr("key.col1 AS key_col1")

      checkAnswer(resultDf2, Seq(Row("A"), Row("B"), Row("C")))
    }
  }

  test("dropDuplicatesWithinWatermark") {
    withTempDir { tempDir =>
      runDropDuplicatesWithinWatermarkQuery(tempDir.getAbsolutePath)

      val stateReadDf = spark.read
        .format("statestore")
        .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
        // skip version and operator ID to test out functionalities
        .load()

      val resultDf = stateReadDf
        .selectExpr("key._1 AS key_1", "value.expiresAtMicros AS value_expiresAtMicros")

      checkAnswer(resultDf,
        Seq(Row("b", 24000000), Row("d", 27000000)))

      val stateReadDf2 = spark.read
        .format("statestore")
        .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
        .option(StateSourceOptions.BATCH_ID, 4)
        .load()

      val resultDf2 = stateReadDf2
        .selectExpr("key._1 AS key_1", "value.expiresAtMicros AS value_expiresAtMicros")

      checkAnswer(resultDf2,
        Seq(
          Row("a", 19000000),
          Row("b", 24000000),
          Row("c", 23000000)
        )
      )
    }
  }

  test("Session window aggregation") {
    withTempDir { checkpointDir =>
      runSessionWindowAggregationQuery(checkpointDir.getAbsolutePath)

      val df = spark.read.format("statestore").load(checkpointDir.toString)
      checkAnswer(df.selectExpr("key.sessionId", "CAST(key.sessionStartTime AS LONG)",
        "CAST(value.session_window.start AS LONG)", "CAST(value.session_window.end AS LONG)",
        "value.sessionId", "value.count"),
        Seq(Row("hello", 40, 40, 51, "hello", 2),
          Row("spark", 40, 40, 50, "spark", 1),
          Row("streaming", 40, 40, 51, "streaming", 2),
          Row("world", 40, 40, 51, "world", 2),
          Row("structured", 41, 41, 51, "structured", 1)))
    }
  }

  test("flatMapGroupsWithState, state ver 1") {
    testFlatMapGroupsWithState(1)
  }

  test("flatMapGroupsWithState, state ver 2") {
    testFlatMapGroupsWithState(2)
  }

  private def testFlatMapGroupsWithState(stateVersion: Int): Unit = {
    withSQLConf(SQLConf.FLATMAPGROUPSWITHSTATE_STATE_FORMAT_VERSION.key -> stateVersion.toString) {
      withTempDir { tempDir =>
        runFlatMapGroupsWithStateQuery(tempDir.getAbsolutePath)

        val stateReadDf = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .load()

        val resultDf = if (stateVersion == 1) {
          stateReadDf
            .selectExpr("key.value AS key_value", "value.numEvents AS value_numEvents",
              "value.startTimestampMs AS value_startTimestampMs",
              "value.endTimestampMs AS value_endTimestampMs",
              "value.timeoutTimestamp AS value_timeoutTimestamp")
        } else { // stateVersion == 2
          stateReadDf
            .selectExpr("key.value AS key_value", "value.groupState.numEvents AS value_numEvents",
              "value.groupState.startTimestampMs AS value_startTimestampMs",
              "value.groupState.endTimestampMs AS value_endTimestampMs",
              "value.timeoutTimestamp AS value_timeoutTimestamp")
        }

        checkAnswer(
          resultDf,
          Seq(
            Row("hello", 4, 1000, 4000, 12000),
            Row("world", 2, 1000, 3000, 12000),
            Row("scala", 2, 2000, 4000, 12000)
          )
        )

        // try to read the value via case class provided in actual query
        implicit val encoder = Encoders.product[SessionInfo]
        val df = if (stateVersion == 1) {
          stateReadDf.selectExpr("value.*").drop("timeoutTimestamp").as[SessionInfo]
        } else { // state version == 2
          stateReadDf.selectExpr("value.groupState.*").as[SessionInfo]
        }

        val expected = Array(
          SessionInfo(4, 1000, 4000),
          SessionInfo(2, 1000, 3000),
          SessionInfo(2, 2000, 4000)
        )
        assert(df.collect().toSet === expected.toSet)
      }
    }
  }

  test("stream-stream join, state ver 1") {
    testStreamStreamJoin(1)
  }

  test("stream-stream join, state ver 2") {
    testStreamStreamJoin(2)
  }

  private def testStreamStreamJoin(stateVersion: Int): Unit = {
    def assertInternalColumnIsNotExposed(df: DataFrame): Unit = {
      val valueSchema = SchemaUtil.getSchemaAsDataType(df.schema, "value")
        .asInstanceOf[StructType]

      intercept[AnalysisException] {
        SchemaUtil.getSchemaAsDataType(valueSchema, "matched")
      }
    }

    withSQLConf(SQLConf.STREAMING_JOIN_STATE_FORMAT_VERSION.key -> stateVersion.toString) {
      withTempDir { tempDir =>
        runStreamStreamJoinQuery(tempDir.getAbsolutePath)
        val stateReaderForLeft = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .option(StateSourceOptions.JOIN_SIDE, "left")

        val stateReadDfForLeft = stateReaderForLeft.load()
        assertInternalColumnIsNotExposed(stateReadDfForLeft)

        val resultDf = stateReadDfForLeft
          .selectExpr("key.field0 As key_0", "value.leftId AS leftId",
            "CAST(value.leftTime AS integer) AS leftTime")

        checkAnswer(
          resultDf,
          Seq(Row(2, 2, 2L), Row(4, 4, 4L), Row(6, 6, 6L), Row(8, 8, 8L), Row(10, 10, 10L))
        )

        val stateReaderForRight = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .option(StateSourceOptions.JOIN_SIDE, "right")

        val stateReadDfForRight = stateReaderForRight.load()
        assertInternalColumnIsNotExposed(stateReadDfForRight)

        val resultDf2 = stateReadDfForRight
          .selectExpr("key.field0 As key_0", "value.rightId AS rightId",
            "CAST(value.rightTime AS integer) AS rightTime")

        checkAnswer(
          resultDf2,
          Seq(Row(6, 6, 6L), Row(8, 8, 8L), Row(10, 10, 10L))
        )

        val stateReaderForRightKeyToNumValues = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .option(StateSourceOptions.STORE_NAME,
            "right-keyToNumValues")

        val stateReadDfForRightKeyToNumValues = stateReaderForRightKeyToNumValues.load()
        val resultDf3 = stateReadDfForRightKeyToNumValues
          .selectExpr("key.field0 AS key_0", "value.value")

        checkAnswer(
          resultDf3,
          Seq(Row(6, 1L), Row(8, 1L), Row(10, 1L))
        )

        val stateReaderForRightKeyWithIndexToValue = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .option(StateSourceOptions.STORE_NAME,
            "right-keyWithIndexToValue")

        val stateReadDfForRightKeyWithIndexToValue = stateReaderForRightKeyWithIndexToValue.load()

        if (stateVersion == 2) {
          val resultDf4 = stateReadDfForRightKeyWithIndexToValue
            .selectExpr("key.field0 AS key_0", "key.index AS key_index",
              "value.rightId AS rightId", "CAST(value.rightTime AS integer) AS rightTime",
              "value.matched As matched")

          checkAnswer(
            resultDf4,
            Seq(Row(6, 0, 6, 6L, true), Row(8, 0, 8, 8L, true), Row(10, 0, 10, 10L, true))
          )
        } else {
          // stateVersion == 1
          val resultDf4 = stateReadDfForRightKeyWithIndexToValue
            .selectExpr("key.field0 AS key_0", "key.index AS key_index",
              "value.rightId AS rightId", "CAST(value.rightTime AS integer) AS rightTime")

          checkAnswer(
            resultDf4,
            Seq(Row(6, 0, 6, 6L), Row(8, 0, 8, 8L), Row(10, 0, 10, 10L))
          )
        }
      }
    }
  }

  test("metadata column") {
    withTempDir { tempDir =>
      import testImplicits._
      val stream = MemoryStream[Int]

      val df = stream.toDF()
        .groupBy("value")
        .count()

      stream.addData(1 to 10000: _*)

      val query = df.writeStream.format("noop")
        .option("checkpointLocation", tempDir.getAbsolutePath)
        .outputMode(OutputMode.Update())
        .start()

      query.processAllAvailable()
      query.stop()

      val stateReadDf = spark.read
        .format("statestore")
        .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
        // skip version and operator ID to test out functionalities
        .load()

      assert(!stateReadDf.schema.exists(_.name == "_partition_id"),
      "metadata column should not be exposed until it is explicitly specified!")

      val numShufflePartitions = spark.conf.get(SQLConf.SHUFFLE_PARTITIONS)

      val resultDf = stateReadDf
        .selectExpr("key.value AS key_value", "value.count AS value_count", "_partition_id")
        .where("_partition_id % 2 = 0")

      // NOTE: This is a hash function of distribution for stateful operator.
      val hash = HashPartitioning(
        Seq(BoundReference(0, IntegerType, nullable = true)),
        numShufflePartitions)
      val partIdExpr = hash.partitionIdExpression

      checkAnswer(resultDf,
        (1 to 10000).map { idx =>
          val rowForPartition = new GenericInternalRow(Array(idx.asInstanceOf[Any]))
          Row(idx, 1L, partIdExpr.eval(rowForPartition).asInstanceOf[Int])
        }.filter { r =>
          r.getInt(2) % 2 == 0
        }
      )
    }
  }

  test("metadata column with stream-stream join") {
    val numShufflePartitions = spark.conf.get(SQLConf.SHUFFLE_PARTITIONS)

    withTempDir { tempDir =>
      runStreamStreamJoinQueryWithOneThousandInputs(tempDir.getAbsolutePath)

      def assertPartitionIdColumnIsNotExposedByDefault(df: DataFrame): Unit = {
        assert(!df.schema.exists(_.name == "_partition_id"),
          "metadata column should not be exposed until it is explicitly specified!")
      }

      def assertPartitionIdColumn(df: DataFrame): Unit = {
        // NOTE: This is a hash function of distribution for stateful operator.
        // stream-stream join uses the grouping key for the equality match in the join condition.
        // partitioning is bound to the operator, hence all state stores in stream-stream join
        // will have the same partition ID, regardless of the key in the internal state store.
        val hash = HashPartitioning(
          Seq(BoundReference(0, IntegerType, nullable = true)),
          numShufflePartitions)
        val partIdExpr = hash.partitionIdExpression

        val dfWithPartition = df.selectExpr("key.field0 As key_0", "_partition_id")
          .where("_partition_id % 2 = 0")

        checkAnswer(dfWithPartition,
          Range.inclusive(2, 1000, 2).map { idx =>
            val rowForPartition = new GenericInternalRow(Array(idx.asInstanceOf[Any]))
            Row(idx, partIdExpr.eval(rowForPartition).asInstanceOf[Int])
          }.filter { r =>
            r.getInt(1) % 2 == 0
          }
        )
      }

      def testForSide(side: String): Unit = {
        val stateReaderForLeft = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .option(StateSourceOptions.JOIN_SIDE, side)
          .load()

        assertPartitionIdColumnIsNotExposedByDefault(stateReaderForLeft)
        assertPartitionIdColumn(stateReaderForLeft)

        val stateReaderForKeyToNumValues = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .option(StateSourceOptions.STORE_NAME,
            s"$side-keyToNumValues")
          .load()

        assertPartitionIdColumnIsNotExposedByDefault(stateReaderForKeyToNumValues)
        assertPartitionIdColumn(stateReaderForKeyToNumValues)

        val stateReaderForKeyWithIndexToValue = spark.read
          .format("statestore")
          .option(StateSourceOptions.PATH, tempDir.getAbsolutePath)
          .option(StateSourceOptions.STORE_NAME,
            s"$side-keyWithIndexToValue")
          .load()

        assertPartitionIdColumnIsNotExposedByDefault(stateReaderForKeyWithIndexToValue)
        assertPartitionIdColumn(stateReaderForKeyWithIndexToValue)
      }

      testForSide("left")
      testForSide("right")
    }
  }
}
