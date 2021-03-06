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

package org.wfanet.measurement.kingdom.deploy.common.daemon

import org.wfanet.measurement.common.commandLineMain
import org.wfanet.measurement.kingdom.daemon.Daemon
import org.wfanet.measurement.kingdom.daemon.runRequisitionLinker
import picocli.CommandLine

@CommandLine.Command(
  name = "requisition_linker",
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
private fun run(@CommandLine.Mixin flags: DaemonFlags) {
  runDaemon(flags, Daemon::runRequisitionLinker)
}

fun main(args: Array<String>) = commandLineMain(::run, args)
