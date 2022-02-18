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

package org.apache.flink.api.scala.migration

import org.apache.flink.FlinkVersion

import java.util
import org.apache.flink.api.common.accumulators.IntCounter
import org.apache.flink.api.common.functions.RichFlatMapFunction
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor, ValueState, ValueStateDescriptor}
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.configuration.Configuration
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend
import org.apache.flink.runtime.state.memory.MemoryStateBackend
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.watermark.Watermark
import org.apache.flink.test.checkpointing.utils.SnapshotMigrationTestBase
import org.apache.flink.util.Collector
import org.apache.flink.api.java.tuple.Tuple2
import org.apache.flink.runtime.state.{FunctionInitializationContext, FunctionSnapshotContext, StateBackendLoader}
import org.apache.flink.api.scala._
import org.apache.flink.api.scala.migration.CustomEnum.CustomEnum
import org.apache.flink.test.checkpointing.utils.SnapshotMigrationTestBase.ExecutionMode
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.Test

import java.util.stream.Collectors
import scala.util.{Failure, Try}

object StatefulJobSavepointMigrationITCase {

  // TODO increase this to newer version to create and test snapshot migration for newer versions
  val currentVersion = FlinkVersion.v1_14

  // TODO change this to CREATE_SNAPSHOT to (re)create binary snapshots
  // TODO Note: You should generate the snapshot based on the release branch instead of the
  // master.
  val executionMode = ExecutionMode.VERIFY_SNAPSHOT

  @Parameterized.Parameters(name = "Migrate Savepoint / Backend: {0}")
  def parameters: util.Collection[(FlinkVersion, String)] = {
    var parameters = util.Arrays.asList(
      //      (FlinkVersion.v1_3, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      //      (FlinkVersion.v1_4, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      //      (FlinkVersion.v1_6, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      //      (FlinkVersion.v1_7, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      // Note: It is not safe to restore savepoints created in a Scala applications with Flink
      // version 1.7 or below. The reason is that up to version 1.7 the underlying Scala serializer
      // used names of anonymous classes that depend on the relative position/order in code, e.g.,
      // if two anonymous classes, instantiated inside the same class and from the same base class,
      // change order in the code their names are switched.
      // As a consequence, changes in code may result in restore failures.
      // This was fixed in version 1.8, see: https://issues.apache.org/jira/browse/FLINK-10493
      (FlinkVersion.v1_8, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      (FlinkVersion.v1_9, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      (FlinkVersion.v1_10, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      (FlinkVersion.v1_11, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      (FlinkVersion.v1_12, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      (FlinkVersion.v1_13, StateBackendLoader.MEMORY_STATE_BACKEND_NAME),
      (FlinkVersion.v1_14, StateBackendLoader.HASHMAP_STATE_BACKEND_NAME),
      (FlinkVersion.v1_15, StateBackendLoader.HASHMAP_STATE_BACKEND_NAME),
    )
    if (executionMode == ExecutionMode.CREATE_SNAPSHOT) {
      parameters = parameters.stream().filter(x => x._1 == currentVersion)
        .collect(Collectors.toList())
    }
    parameters
  }

  val NUM_ELEMENTS = 4

  def getSnapshotPath(migrationVersionAndBackend: (FlinkVersion, String)): String = {
    s"stateful-scala-udf-migration-itcase" +
      s"-flink${migrationVersionAndBackend._1}" +
      s"-${migrationVersionAndBackend._2}-savepoint"
  }
}

/**
 * ITCase for migration Scala state types across different Flink versions.
 */
@RunWith(classOf[Parameterized])
class StatefulJobSavepointMigrationITCase(
                                           migrationVersionAndBackend: (FlinkVersion, String))
  extends SnapshotMigrationTestBase with Serializable {

  @Test
  def testSavepoint(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    migrationVersionAndBackend._2 match {
      case StateBackendLoader.ROCKSDB_STATE_BACKEND_NAME =>
        env.setStateBackend(new EmbeddedRocksDBStateBackend())
      case StateBackendLoader.MEMORY_STATE_BACKEND_NAME =>
        env.setStateBackend(new MemoryStateBackend())
      case StateBackendLoader.HASHMAP_STATE_BACKEND_NAME =>
        env.setStateBackend(new HashMapStateBackend())
      case _ => throw new UnsupportedOperationException
    }

    env.enableCheckpointing(500)
    env.setParallelism(4)
    env.setMaxParallelism(4)

    env
      .addSource(
        new CheckpointedSource(4)).setMaxParallelism(1).uid("checkpointedSource")
      .keyBy(
        new KeySelector[(Long, Long), Long] {
          override def getKey(value: (Long, Long)): Long = value._1
        }
      )
      .flatMap(new StatefulFlatMapper)
      .addSink(new AccumulatorCountingSink)

    if (StatefulJobSavepointMigrationITCase.executionMode == ExecutionMode.CREATE_SNAPSHOT) {
      executeAndSavepoint(
        env,
        s"src/test/resources/"
          + StatefulJobSavepointMigrationITCase.getSnapshotPath(migrationVersionAndBackend),
        new Tuple2(
          AccumulatorCountingSink.NUM_ELEMENTS_ACCUMULATOR,
          StatefulJobSavepointMigrationITCase.NUM_ELEMENTS
        )
      )
    } else if (
      StatefulJobSavepointMigrationITCase.executionMode == ExecutionMode.VERIFY_SNAPSHOT) {
      restoreAndExecute(
        env,
        SnapshotMigrationTestBase.getResourceFilename(
          StatefulJobSavepointMigrationITCase.getSnapshotPath(migrationVersionAndBackend)),
        new Tuple2(
          AccumulatorCountingSink.NUM_ELEMENTS_ACCUMULATOR,
          StatefulJobSavepointMigrationITCase.NUM_ELEMENTS)
      )
    } else {
      throw new UnsupportedOperationException("Unsupported execution mode.")
    }
  }

  @SerialVersionUID(1L)
  private object CheckpointedSource {
    var CHECKPOINTED_STRING = "Here be dragons!"
  }

  @SerialVersionUID(1L)
  private class CheckpointedSource(val numElements: Int)
    extends SourceFunction[(Long, Long)] with CheckpointedFunction {

    private var isRunning = true
    private var state: ListState[CustomCaseClass] = _

    @throws[Exception]
    override def run(ctx: SourceFunction.SourceContext[(Long, Long)]) {
      ctx.emitWatermark(new Watermark(0))
      ctx.getCheckpointLock synchronized {
        var i = 0
        while (i < numElements) {
          ctx.collect(i, i)
          i += 1
        }
      }
      // don't emit a final watermark so that we don't trigger the registered event-time
      // timers
      while (isRunning) Thread.sleep(20)
    }

    def cancel() {
      isRunning = false
    }

    override def initializeState(context: FunctionInitializationContext): Unit = {
      state = context.getOperatorStateStore.getListState(
        new ListStateDescriptor[CustomCaseClass](
          "sourceState", createTypeInformation[CustomCaseClass]))
    }

    override def snapshotState(context: FunctionSnapshotContext): Unit = {
      state.clear()
      state.add(CustomCaseClass("Here be dragons!", 123))
    }
  }

  @SerialVersionUID(1L)
  private object AccumulatorCountingSink {
    var NUM_ELEMENTS_ACCUMULATOR = classOf[AccumulatorCountingSink[_]] + "_NUM_ELEMENTS"
  }

  @SerialVersionUID(1L)
  private class AccumulatorCountingSink[T] extends RichSinkFunction[T] {

    private var count: Int = 0

    @throws[Exception]
    override def open(parameters: Configuration) {
      super.open(parameters)
      getRuntimeContext.addAccumulator(
        AccumulatorCountingSink.NUM_ELEMENTS_ACCUMULATOR, new IntCounter)
    }

    @throws[Exception]
    override def invoke(value: T) {
      count += 1
      getRuntimeContext.getAccumulator(
        AccumulatorCountingSink.NUM_ELEMENTS_ACCUMULATOR).add(1)
    }
  }

  class StatefulFlatMapper extends RichFlatMapFunction[(Long, Long), (Long, Long)] {

    private var caseClassState: ValueState[CustomCaseClass] = _
    private var caseClassWithNestingState: ValueState[CustomCaseClassWithNesting] = _
    private var collectionState: ValueState[List[CustomCaseClass]] = _
    private var tryState: ValueState[Try[CustomCaseClass]] = _
    private var tryFailureState: ValueState[Try[CustomCaseClass]] = _
    private var optionState: ValueState[Option[CustomCaseClass]] = _
    private var optionNoneState: ValueState[Option[CustomCaseClass]] = _
    private var eitherLeftState: ValueState[Either[CustomCaseClass, String]] = _
    private var eitherRightState: ValueState[Either[CustomCaseClass, String]] = _
    private var enumOneState: ValueState[CustomEnum] = _
    private var enumThreeState: ValueState[CustomEnum] = _

    override def open(parameters: Configuration): Unit = {
      caseClassState = getRuntimeContext.getState(
        new ValueStateDescriptor[CustomCaseClass](
          "caseClassState", createTypeInformation[CustomCaseClass]))
      caseClassWithNestingState = getRuntimeContext.getState(
        new ValueStateDescriptor[CustomCaseClassWithNesting](
          "caseClassWithNestingState", createTypeInformation[CustomCaseClassWithNesting]))
      collectionState = getRuntimeContext.getState(
        new ValueStateDescriptor[List[CustomCaseClass]](
          "collectionState", createTypeInformation[List[CustomCaseClass]]))
      tryState = getRuntimeContext.getState(
        new ValueStateDescriptor[Try[CustomCaseClass]](
          "tryState", createTypeInformation[Try[CustomCaseClass]]))
      tryFailureState = getRuntimeContext.getState(
        new ValueStateDescriptor[Try[CustomCaseClass]](
          "tryFailureState", createTypeInformation[Try[CustomCaseClass]]))
      optionState = getRuntimeContext.getState(
        new ValueStateDescriptor[Option[CustomCaseClass]](
          "optionState", createTypeInformation[Option[CustomCaseClass]]))
      optionNoneState = getRuntimeContext.getState(
        new ValueStateDescriptor[Option[CustomCaseClass]](
          "optionNoneState", createTypeInformation[Option[CustomCaseClass]]))
      eitherLeftState = getRuntimeContext.getState(
        new ValueStateDescriptor[Either[CustomCaseClass, String]](
          "eitherLeftState", createTypeInformation[Either[CustomCaseClass, String]]))
      eitherRightState = getRuntimeContext.getState(
        new ValueStateDescriptor[Either[CustomCaseClass, String]](
          "eitherRightState", createTypeInformation[Either[CustomCaseClass, String]]))
      enumOneState = getRuntimeContext.getState(
        new ValueStateDescriptor[CustomEnum](
          "enumOneState", createTypeInformation[CustomEnum]))
      enumThreeState = getRuntimeContext.getState(
        new ValueStateDescriptor[CustomEnum](
          "enumThreeState", createTypeInformation[CustomEnum]))
    }

    override def flatMap(in: (Long, Long), collector: Collector[(Long, Long)]): Unit = {
      caseClassState.update(CustomCaseClass(in._1.toString, in._2 * 2))
      caseClassWithNestingState.update(
        CustomCaseClassWithNesting(in._1, CustomCaseClass(in._1.toString, in._2 * 2)))
      collectionState.update(List(CustomCaseClass(in._1.toString, in._2 * 2)))
      tryState.update(Try(CustomCaseClass(in._1.toString, in._2 * 5)))
      tryFailureState.update(Failure(new RuntimeException))
      optionState.update(Some(CustomCaseClass(in._1.toString, in._2 * 2)))
      optionNoneState.update(None)
      eitherLeftState.update(Left(CustomCaseClass(in._1.toString, in._2 * 2)))
      eitherRightState.update(Right((in._1 * 3).toString))
      enumOneState.update(CustomEnum.ONE)
      enumOneState.update(CustomEnum.THREE)

      collector.collect(in)
    }
  }

}
