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

package org.wfanet.measurement.db.kingdom.gcp

import com.google.cloud.spanner.Mutation
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import java.time.Instant
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.common.toJson
import org.wfanet.measurement.common.toProtoTime
import org.wfanet.measurement.db.gcp.toProtoEnum
import org.wfanet.measurement.db.kingdom.gcp.testing.KingdomDatabaseTestBase
import org.wfanet.measurement.internal.kingdom.Requisition
import org.wfanet.measurement.internal.kingdom.Requisition.RequisitionState

@RunWith(JUnit4::class)
class FulfillRequisitionTransactionTest : KingdomDatabaseTestBase() {
  companion object {
    const val DATA_PROVIDER_ID = 1L
    const val EXTERNAL_DATA_PROVIDER_ID = 2L
    const val CAMPAIGN_ID = 3L
    const val EXTERNAL_CAMPAIGN_ID = 4L
    const val REQUISITION_ID = 5L
    const val EXTERNAL_REQUISITION_ID = 6L
    const val ADVERTISER_ID = 7L
    const val EXTERNAL_REPORT_CONFIG_ID = 10L

    val WINDOW_START_TIME: Instant = Instant.ofEpochSecond(123)
    val WINDOW_END_TIME: Instant = Instant.ofEpochSecond(456)

    val REQUISITION_DETAILS = buildRequisitionDetails(10101)

    val REQUISITION: Requisition = Requisition.newBuilder().apply {
      externalDataProviderId = EXTERNAL_DATA_PROVIDER_ID
      externalCampaignId = EXTERNAL_CAMPAIGN_ID
      externalRequisitionId = EXTERNAL_REQUISITION_ID
      windowStartTime = WINDOW_START_TIME.toProtoTime()
      windowEndTime = WINDOW_END_TIME.toProtoTime()
      state = RequisitionState.UNFULFILLED
      requisitionDetails = REQUISITION_DETAILS
      requisitionDetailsJson = REQUISITION_DETAILS.toJson()
    }.build()
  }

  private fun updateExistingRequisitionState(state: RequisitionState) {
    databaseClient.write(
      listOf(
        Mutation
          .newUpdateBuilder("Requisitions")
          .set("DataProviderId").to(DATA_PROVIDER_ID)
          .set("CampaignId").to(CAMPAIGN_ID)
          .set("RequisitionId").to(REQUISITION_ID)
          .set("State").toProtoEnum(state)
          .build()
      )
    )
  }

  @Before
  fun populateDatabase() {
    insertDataProvider(DATA_PROVIDER_ID, EXTERNAL_DATA_PROVIDER_ID)
    insertCampaign(DATA_PROVIDER_ID, CAMPAIGN_ID, EXTERNAL_CAMPAIGN_ID, ADVERTISER_ID)
    insertRequisition(
      DATA_PROVIDER_ID,
      CAMPAIGN_ID,
      REQUISITION_ID,
      EXTERNAL_REQUISITION_ID,
      state = RequisitionState.UNFULFILLED,
      windowStartTime = WINDOW_START_TIME,
      windowEndTime = WINDOW_END_TIME,
      requisitionDetails = REQUISITION_DETAILS
    )
  }

  @Test
  fun success() {
    updateExistingRequisitionState(RequisitionState.UNFULFILLED)
    val requisition: Requisition? =
      databaseClient.readWriteTransaction().run { transactionContext ->
        FulfillRequisitionTransaction()
          .execute(transactionContext, ExternalId(EXTERNAL_REQUISITION_ID), "some-duchy")
      }

    val expectedRequisition: Requisition =
      REQUISITION
        .toBuilder()
        .setState(RequisitionState.FULFILLED)
        .setDuchyId("some-duchy")
        .build()

    assertThat(requisition).comparingExpectedFieldsOnly().isEqualTo(expectedRequisition)
    assertThat(readAllRequisitionsInSpanner())
      .comparingExpectedFieldsOnly()
      .containsExactly(expectedRequisition)
  }

  @Test
  fun `already fulfilled`() {
    updateExistingRequisitionState(RequisitionState.FULFILLED)
    val existingRequisitions = readAllRequisitionsInSpanner()
    databaseClient.readWriteTransaction().run { transactionContext ->
      assertFails {
        FulfillRequisitionTransaction()
          .execute(transactionContext, ExternalId(EXTERNAL_REQUISITION_ID), "some-duchy")
      }
    }
    assertThat(readAllRequisitionsInSpanner())
      .comparingExpectedFieldsOnly()
      .containsExactlyElementsIn(existingRequisitions)
  }

  @Test
  fun `missing requisition`() {
    val existingRequisitions = readAllRequisitionsInSpanner()
    databaseClient.readWriteTransaction().run { transactionContext ->
      assertFailsWith<NoSuchElementException> {
        FulfillRequisitionTransaction()
          .execute(transactionContext, ExternalId(EXTERNAL_REQUISITION_ID + 1), "some-duchy")
      }
    }
    assertThat(readAllRequisitionsInSpanner())
      .comparingExpectedFieldsOnly()
      .containsExactlyElementsIn(existingRequisitions)
  }
}
