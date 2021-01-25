// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.wfanet.measurement.duchy.daemon.mill.liquidlegionsv2

import java.nio.file.Paths
import java.time.Clock
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flattenConcat
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseOneAtAggregatorRequest
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseOneAtAggregatorResponse
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseOneRequest
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseOneResponse
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseThreeAtAggregatorRequest
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseThreeAtAggregatorResponse
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseThreeRequest
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseThreeResponse
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseTwoAtAggregatorRequest
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseTwoAtAggregatorResponse
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseTwoRequest
import org.wfanet.measurement.common.crypto.CompleteExecutionPhaseTwoResponse
import org.wfanet.measurement.common.crypto.CompleteSetupPhaseRequest
import org.wfanet.measurement.common.crypto.CompleteSetupPhaseResponse
import org.wfanet.measurement.common.crypto.liquidlegionsv2.LiquidLegionsV2Encryption
import org.wfanet.measurement.common.flatten
import org.wfanet.measurement.common.loadLibrary
import org.wfanet.measurement.common.throttler.MinimumIntervalThrottler
import org.wfanet.measurement.duchy.daemon.mill.CRYPTO_LIB_CPU_DURATION
import org.wfanet.measurement.duchy.daemon.mill.CryptoKeySet
import org.wfanet.measurement.duchy.daemon.mill.LiquidLegionsConfig
import org.wfanet.measurement.duchy.daemon.mill.MillBase
import org.wfanet.measurement.duchy.daemon.mill.PermanentComputationError
import org.wfanet.measurement.duchy.daemon.mill.toMetricRequisitionKey
import org.wfanet.measurement.duchy.db.computation.ComputationDataClients
import org.wfanet.measurement.duchy.service.internal.computation.outputPathList
import org.wfanet.measurement.duchy.service.system.v1alpha.advanceComputationHeader
import org.wfanet.measurement.duchy.toProtocolStage
import org.wfanet.measurement.internal.duchy.ComputationDetails.CompletedReason
import org.wfanet.measurement.internal.duchy.ComputationStage
import org.wfanet.measurement.internal.duchy.ComputationStatsGrpcKt.ComputationStatsCoroutineStub
import org.wfanet.measurement.internal.duchy.ComputationToken
import org.wfanet.measurement.internal.duchy.ComputationTypeEnum.ComputationType
import org.wfanet.measurement.internal.duchy.MetricValuesGrpcKt.MetricValuesCoroutineStub
import org.wfanet.measurement.internal.duchy.UpdateComputationDetailsRequest
import org.wfanet.measurement.protocol.LiquidLegionsSketchAggregationV2.ComputationDetails.RoleInComputation.AGGREGATOR
import org.wfanet.measurement.protocol.LiquidLegionsSketchAggregationV2.ComputationDetails.RoleInComputation.NON_AGGREGATOR
import org.wfanet.measurement.protocol.LiquidLegionsSketchAggregationV2.Stage
import org.wfanet.measurement.system.v1alpha.ComputationControlGrpcKt.ComputationControlCoroutineStub
import org.wfanet.measurement.system.v1alpha.ConfirmGlobalComputationRequest
import org.wfanet.measurement.system.v1alpha.FinishGlobalComputationRequest
import org.wfanet.measurement.system.v1alpha.GlobalComputationsGrpcKt.GlobalComputationsCoroutineStub
import org.wfanet.measurement.system.v1alpha.LiquidLegionsV2

/**
 * Mill works on computations using the LiquidLegionSketchAggregationProtocol.
 *
 * @param millId The identifier of this mill, used to claim a work.
 * @param dataClients clients that have access to local computation storage, i.e., spanner
 *    table and blob store.
 * @param metricValuesClient client of the own duchy's MetricValuesService.
 * @param globalComputationsClient client of the kingdom's GlobalComputationsService.
 * @param computationStatsClient client of the duchy's ComputationStatsService.
 * @param throttler A throttler used to rate limit the frequency of the mill polling from the
 *    computation table.
 * @param requestChunkSizeBytes The size of data chunk when sending result to other duchies.
 * @param clock A clock
 * @param workerStubs A map from other duchies' Ids to their corresponding
 *    computationControlClients, used for passing computation to other duchies.
 * @param cryptoKeySet The set of crypto keys used in the computation.
 * @param cryptoWorker The cryptoWorker that performs the actual computation.
 * @param liquidLegionsConfig The configuration of the LiquidLegions sketch.
 */
class LiquidLegionsV2Mill(
  millId: String,
  dataClients: ComputationDataClients,
  metricValuesClient: MetricValuesCoroutineStub,
  globalComputationsClient: GlobalComputationsCoroutineStub,
  computationStatsClient: ComputationStatsCoroutineStub,
  throttler: MinimumIntervalThrottler,
  requestChunkSizeBytes: Int = 1024 * 32, // 32 KiB
  clock: Clock = Clock.systemUTC(),
  private val workerStubs: Map<String, ComputationControlCoroutineStub>,
  private val cryptoKeySet: CryptoKeySet,
  private val cryptoWorker: LiquidLegionsV2Encryption,
  private val liquidLegionsConfig: LiquidLegionsConfig = LiquidLegionsConfig(
    12.0,
    10_000_000L,
    10
  )
) : MillBase(
  millId,
  dataClients,
  globalComputationsClient,
  metricValuesClient,
  computationStatsClient,
  throttler,
  ComputationType.LIQUID_LEGIONS_SKETCH_AGGREGATION_V2,
  requestChunkSizeBytes,
  clock
) {
  override val endingStage: ComputationStage = ComputationStage.newBuilder().apply {
    liquidLegionsSketchAggregationV2 = Stage.COMPLETE
  }.build()

  private val actions = mapOf(
    Pair(Stage.CONFIRM_REQUISITIONS_PHASE, AGGREGATOR)
      to ::confirmRequisitions,
    Pair(Stage.CONFIRM_REQUISITIONS_PHASE, NON_AGGREGATOR)
      to ::confirmRequisitions,
    Pair(Stage.SETUP_PHASE, AGGREGATOR)
      to ::completeSetupPhaseAtAggregator,
    Pair(Stage.SETUP_PHASE, NON_AGGREGATOR)
      to ::completeSetupPhaseAtNonAggregator,
    Pair(Stage.EXECUTION_PHASE_ONE, AGGREGATOR)
      to ::completeExecutionPhaseOneAtAggregator,
    Pair(Stage.EXECUTION_PHASE_ONE, NON_AGGREGATOR)
      to ::completeExecutionPhaseOneAtNonAggregator,
    Pair(Stage.EXECUTION_PHASE_TWO, AGGREGATOR)
      to ::completeExecutionPhaseTwoAtAggregator,
    Pair(Stage.EXECUTION_PHASE_TWO, NON_AGGREGATOR)
      to ::completeExecutionPhaseTwoAtNonAggregator,
    Pair(Stage.EXECUTION_PHASE_THREE, AGGREGATOR)
      to ::completeExecutionPhaseThreeAtAggregator,
    Pair(Stage.EXECUTION_PHASE_THREE, NON_AGGREGATOR)
      to ::completeExecutionPhaseThreeAtNonAggregator
  )

  override suspend fun processComputationImpl(token: ComputationToken) {
    require(token.computationDetails.hasLiquidLegionsV2()) {
      "Only Liquid Legions V2 computation is supported in this mill."
    }
    val stage = token.computationStage.liquidLegionsSketchAggregationV2
    val role = token.computationDetails.liquidLegionsV2.role
    val action = actions[Pair(stage, role)] ?: error("Unexpected stage or role: ($stage, $role)")
    action(token)
  }

  /** Process computation in the confirm requisitions phase*/
  @OptIn(FlowPreview::class) // For `flattenConcat`.
  private suspend fun confirmRequisitions(token: ComputationToken): ComputationToken {
    val requisitionsToConfirm =
      token.stageSpecificDetails.liquidLegionsV2.toConfirmRequisitionsStageDetails.keysList
    val availableRequisitions = requisitionsToConfirm.filter { metricValueExists(it) }

    globalComputationsClient.confirmGlobalComputation(
      ConfirmGlobalComputationRequest.newBuilder().apply {
        addAllReadyRequisitions(availableRequisitions.map { it.toMetricRequisitionKey() })
        keyBuilder.globalComputationId = token.globalComputationId.toString()
      }.build()
    )

    if (availableRequisitions.size != requisitionsToConfirm.size) {
      val errorMessage =
        """
        @Mill $millId:
          Computation ${token.globalComputationId} failed due to missing requisitions.
        Expected:
          $requisitionsToConfirm
        Actual:
          $availableRequisitions
        """.trimIndent()
      throw PermanentComputationError(Exception(errorMessage))
    }

    // cache the combined local requisitions to blob store.
    val concatenatedContents = streamMetricValueContents(availableRequisitions).flattenConcat()
    val nextToken = dataClients.writeSingleOutputBlob(token, concatenatedContents)
    return dataClients.transitionComputationToStage(
      nextToken,
      passThroughBlobs = nextToken.outputPathList(),
      stage = when (checkNotNull(nextToken.computationDetails.liquidLegionsV2.role)) {
        AGGREGATOR -> Stage.WAIT_SETUP_PHASE_INPUTS.toProtocolStage()
        NON_AGGREGATOR -> Stage.WAIT_TO_START.toProtocolStage()
        else ->
          error("Unknown role: ${nextToken.computationDetails.liquidLegionsV2.role}")
      }
    )
  }

  private suspend fun completeSetupPhaseAtAggregator(token: ComputationToken): ComputationToken {
    require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val inputCount = workerStubs.size + 1
      val cryptoResult: CompleteSetupPhaseResponse =
        cryptoWorker.completeSetupPhase(
          // TODO: set other parameters when we start to add noise.
          //  Now it just shuffles the registers.
          CompleteSetupPhaseRequest.newBuilder()
            .setCombinedRegisterVector(readAndCombineAllInputBlobs(token, inputCount))
            .build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.combinedRegisterVector
    }

    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.EXECUTION_PHASE_ONE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_EXECUTION_PHASE_ONE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeSetupPhaseAtNonAggregator(token: ComputationToken): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val inputCount = 1
      val cryptoResult: CompleteSetupPhaseResponse =
        cryptoWorker.completeSetupPhase(
          // TODO: set other parameters when we start to add noise.
          //  Now it just shuffles the registers.
          CompleteSetupPhaseRequest.newBuilder()
            .setCombinedRegisterVector(readAndCombineAllInputBlobs(token, inputCount))
            .build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.combinedRegisterVector
    }

    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.SETUP_PHASE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = aggregatorDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_EXECUTION_PHASE_ONE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeExecutionPhaseOneAtAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteExecutionPhaseOneAtAggregatorResponse =
        cryptoWorker.completeExecutionPhaseOneAtAggregator(
          CompleteExecutionPhaseOneAtAggregatorRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
            curveId = cryptoKeySet.curveId.toLong()
            combinedRegisterVector = readAndCombineAllInputBlobs(token, 1)
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.flagCountTuples
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.EXECUTION_PHASE_TWO_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_EXECUTION_PHASE_TWO_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeExecutionPhaseOneAtNonAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteExecutionPhaseOneResponse =
        cryptoWorker.completeExecutionPhaseOne(
          CompleteExecutionPhaseOneRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
            curveId = cryptoKeySet.curveId.toLong()
            combinedRegisterVector = readAndCombineAllInputBlobs(token, 1)
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.combinedRegisterVector
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.EXECUTION_PHASE_ONE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_EXECUTION_PHASE_TWO_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeExecutionPhaseTwoAtAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    var reach = 0L
    val (bytes, tempToken) = existingOutputOr(token) {
      val cryptoResult: CompleteExecutionPhaseTwoAtAggregatorResponse =
        cryptoWorker.completeExecutionPhaseTwoAtAggregator(
          CompleteExecutionPhaseTwoAtAggregatorRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
            curveId = cryptoKeySet.curveId.toLong()
            flagCountTuples = readAndCombineAllInputBlobs(token, 1)
            maximumFrequency = liquidLegionsConfig.maxFrequency
            liquidLegionsParametersBuilder.apply {
              decayRate = liquidLegionsConfig.decayRate
              size = liquidLegionsConfig.size
            }
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      reach = cryptoResult.reach
      cryptoResult.sameKeyAggregatorMatrix
    }

    val nextToken = if (token.computationDetails.liquidLegionsV2.hasReachEstimate()) {
      // Do nothing if the token has already contained the ReachEstimate
      tempToken
    } else {
      // Update the newly calculated reach to the ComputationDetails.
      dataClients.computationsClient.updateComputationDetails(
        UpdateComputationDetailsRequest.newBuilder().also {
          it.token = tempToken
          it.details = token.computationDetails.toBuilder().apply {
            liquidLegionsV2Builder.reachEstimateBuilder.reach = reach
          }.build()
        }.build()
      ).token
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.EXECUTION_PHASE_THREE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_EXECUTION_PHASE_THREE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeExecutionPhaseTwoAtNonAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteExecutionPhaseTwoResponse =
        cryptoWorker.completeExecutionPhaseTwo(
          CompleteExecutionPhaseTwoRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
            curveId = cryptoKeySet.curveId.toLong()
            flagCountTuples = readAndCombineAllInputBlobs(token, 1)
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.flagCountTuples
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.EXECUTION_PHASE_TWO_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_EXECUTION_PHASE_THREE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeExecutionPhaseThreeAtAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    require(token.computationDetails.liquidLegionsV2.hasReachEstimate()) {
      "Reach estimate is missing."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteExecutionPhaseThreeAtAggregatorResponse =
        cryptoWorker.completeExecutionPhaseThreeAtAggregator(
          CompleteExecutionPhaseThreeAtAggregatorRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            curveId = cryptoKeySet.curveId.toLong()
            sameKeyAggregatorMatrix = readAndCombineAllInputBlobs(token, 1)
            maximumFrequency = liquidLegionsConfig.maxFrequency
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.toByteString()
    }

    val frequencyDistributionMap =
      CompleteExecutionPhaseThreeAtAggregatorResponse.parseFrom(bytes.flatten())
        .frequencyDistributionMap
    globalComputationsClient.finishGlobalComputation(
      FinishGlobalComputationRequest.newBuilder().apply {
        keyBuilder.globalComputationId = token.globalComputationId.toString()
        resultBuilder.apply {
          reach = nextToken.computationDetails.liquidLegionsV2.reachEstimate.reach
          putAllFrequency(frequencyDistributionMap)
        }
      }.build()
    )

    return completeComputation(nextToken, CompletedReason.SUCCEEDED)
  }

  private suspend fun completeExecutionPhaseThreeAtNonAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteExecutionPhaseThreeResponse =
        cryptoWorker.completeExecutionPhaseThree(
          CompleteExecutionPhaseThreeRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            curveId = cryptoKeySet.curveId.toLong()
            sameKeyAggregatorMatrix = readAndCombineAllInputBlobs(token, 1)
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.sameKeyAggregatorMatrix
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.EXECUTION_PHASE_THREE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    // This duchy's responsibility for the computation is done. Mark it COMPLETED locally.
    return completeComputation(nextToken, CompletedReason.SUCCEEDED)
  }

  private fun nextDuchyStub(token: ComputationToken): ComputationControlCoroutineStub {
    val nextDuchy = token.computationDetails.liquidLegionsV2.outgoingNodeId
    return workerStubs[nextDuchy]
      ?: throw PermanentComputationError(
        IllegalArgumentException("No ComputationControlService stub for next duchy '$nextDuchy'")
      )
  }

  private fun aggregatorDuchyStub(token: ComputationToken): ComputationControlCoroutineStub {
    val aggregatorDuchy = token.computationDetails.liquidLegionsV2.aggregatorNodeId
    return workerStubs[aggregatorDuchy]
      ?: throw PermanentComputationError(
        IllegalArgumentException(
          "No ComputationControlService stub for primary duchy '$aggregatorDuchy'"
        )
      )
  }

  companion object {
    init {
      loadLibrary(
        name = "estimators",
        directoryPath = Paths.get("any_sketch_java/src/main/java/org/wfanet/estimation")
      )
    }
  }
}