package org.wfanet.measurement.db.duchy.gcp

import com.google.cloud.spanner.Statement
import com.google.cloud.spanner.Struct
import org.wfanet.measurement.db.gcp.getProtoMessage
import org.wfanet.measurement.internal.duchy.ComputationStageAttemptDetails

/** Queries for the attempts of stages for a computation that do not have an end time. */
class UnfinishedAttemptQuery<StageT>(
  val parseStageEnum: (Long) -> StageT,
  val localId: Long
) : SqlBasedQuery<UnfinishedAttemptQueryResult<StageT>> {
  companion object {
    private const val parameterizedQueryString =
      """
      SELECT ComputationStage, Attempt, Details
      FROM ComputationStageAttempts
      WHERE ComputationId = @local_id
        AND EndTime IS NULL
      """
  }
  override val sql: Statement =
    Statement.newBuilder(parameterizedQueryString).bind("local_id").to(localId).build()
  override fun asResult(struct: Struct): UnfinishedAttemptQueryResult<StageT> =
    UnfinishedAttemptQueryResult(
      computationId = localId,
      stage = parseStageEnum(struct.getLong("ComputationStage")),
      attempt = struct.getLong("Attempt"),
      details = struct.getProtoMessage("Details", ComputationStageAttemptDetails.parser())
    )
}
/** @see [UnfinishedAttemptQuery.asResult] .*/
data class UnfinishedAttemptQueryResult<StageT>(
  val computationId: Long,
  val stage: StageT,
  val attempt: Long,
  val details: ComputationStageAttemptDetails
)
