// Copyright 2020 The Any Sketch Authors
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

package org.wfanet.anysketch;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FarmHashFunctionTest {

  @Test
  public void FarmHashFunctionTest_testFingerPrintWithNullValueFails() {
    FarmHashFunction farmHashFunction = new FarmHashFunction();
    assertThrows(NullPointerException.class, () -> farmHashFunction.fingerprint(null));
  }

  @Test
  public void FarmHashFunctionTest_testTwoSameStringsMatchSucceeds() {
    FarmHashFunction farmHashFunction = new FarmHashFunction();
    String x = new String("Foo");
    String y = new String("Foo");
    assertThat(farmHashFunction.fingerprint(x)).isEqualTo(farmHashFunction.fingerprint(y));
  }

  @Test
  public void FarmHashFunctionTest_testTwoDifferentStringsMatchFails() {
    FarmHashFunction farmHashFunction = new FarmHashFunction();
    String x = new String("Foo");
    String y = new String("Bar");
    assertThat(farmHashFunction.fingerprint(x)).isNotEqualTo(farmHashFunction.fingerprint(y));
  }
}
