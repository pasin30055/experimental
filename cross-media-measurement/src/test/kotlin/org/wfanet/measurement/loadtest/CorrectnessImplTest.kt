// Copyright 2020 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.loadtest

import com.nhaarman.mockitokotlin2.UseConstructor
import com.nhaarman.mockitokotlin2.mock
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v1alpha.ElGamalPublicKey
import org.wfanet.measurement.api.v1alpha.PublisherDataGrpcKt.PublisherDataCoroutineImplBase as PublisherDataCoroutineService
import org.wfanet.measurement.api.v1alpha.PublisherDataGrpcKt.PublisherDataCoroutineStub
import org.wfanet.measurement.api.v1alpha.SketchConfig
import org.wfanet.measurement.common.byteStringOf
import org.wfanet.measurement.common.flatten
import org.wfanet.measurement.common.grpc.testing.GrpcTestServerRule
import org.wfanet.measurement.common.identity.RandomIdGenerator
import org.wfanet.measurement.common.parseTextProto
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.SpannerKingdomRelationalDatabase
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.testing.KingdomDatabaseTestBase
import org.wfanet.measurement.storage.StorageClient
import org.wfanet.measurement.storage.filesystem.FileSystemStorageClient
import org.wfanet.measurement.storage.read

private const val RUN_ID = "TEST"
private val COMBINED_PUBLIC_KEY = ElGamalPublicKey.newBuilder().apply {
  ellipticCurveId = 415
  generator = byteStringOf(
    0x03, 0x6B, 0x17, 0xD1, 0xF2, 0xE1, 0x2C, 0x42, 0x47, 0xF8, 0xBC, 0xE6, 0xE5, 0x63, 0xA4, 0x40,
    0xF2, 0x77, 0x03, 0x7D, 0x81, 0x2D, 0xEB, 0x33, 0xA0, 0xF4, 0xA1, 0x39, 0x45, 0xD8, 0x98, 0xC2,
    0x96
  )
  element = byteStringOf(
    0x02, 0x50, 0x5D, 0x7B, 0x3A, 0xC4, 0xC3, 0xC3, 0x87, 0xC7, 0x41, 0x32, 0xAB, 0x67, 0x7A, 0x34,
    0x21, 0xE8, 0x83, 0xB9, 0x0D, 0x4C, 0x83, 0xDC, 0x76, 0x6E, 0x40, 0x0F, 0xE6, 0x7A, 0xCC, 0x1F,
    0x04
  )
}.build()

@RunWith(JUnit4::class)
class CorrectnessImplTest : KingdomDatabaseTestBase() {

  private lateinit var storageClient: FileSystemStorageClient
  private val publisherDataServiceMock: PublisherDataCoroutineService =
    mock(useConstructor = UseConstructor.parameterless())

  @Rule
  @JvmField
  val tempDirectory = TemporaryFolder()

  @get:Rule
  val grpcTestServerRule = GrpcTestServerRule {
    addService(publisherDataServiceMock)
  }

  private val publisherDataStub: PublisherDataCoroutineStub by lazy {
    PublisherDataCoroutineStub(grpcTestServerRule.channel)
  }

  @Before
  fun init() {
    storageClient = FileSystemStorageClient(tempDirectory.root)
  }

  @Test
  fun `test correctnessimpl process`() = runBlocking {
    val generatedSetSize = 10000000
    val campaignCount = 1
    val correctness = makeCorrectness(
      dataProviderCount = 3,
      campaignCount = campaignCount,
      generatedSetSize = generatedSetSize,
      universeSize = 10_000_000_000L
    )

    val relationalDatabase =
      SpannerKingdomRelationalDatabase(
        Clock.systemUTC(),
        RandomIdGenerator(Clock.systemUTC()),
        databaseClient
      )
    correctness.process(
      relationalDatabase
    )
  }

  private fun makeCorrectness(
    dataProviderCount: Int,
    campaignCount: Int,
    generatedSetSize: Int,
    universeSize: Long
  ): CorrectnessImpl {
    return CorrectnessImpl(
      dataProviderCount = dataProviderCount,
      campaignCount = campaignCount,
      generatedSetSize = generatedSetSize,
      universeSize = universeSize,
      runId = RUN_ID,
      sketchConfig = sketchConfig,
      storageClient = storageClient,
      publisherDataStub = publisherDataStub
    )
  }

  companion object {
    private val sketchConfig: SketchConfig
    init {
      val configPath = "config/liquid_legions_sketch_config.textproto"
      val resource = this::class.java.getResource(configPath)

      sketchConfig = resource.openStream().use { input ->
        parseTextProto(input.bufferedReader(), SketchConfig.getDefaultInstance())
      }
    }
  }
}

private suspend fun StorageClient.Blob.readToString(): String {
  return read().flatten().toStringUtf8()
}
