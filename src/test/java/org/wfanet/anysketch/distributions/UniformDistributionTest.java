package org.wfanet.anysketch.distributions;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UniformDistributionTest {

  @Test
  public void testPositiveFingerprint() {
    UniformDistribution distribution = new UniformDistribution(i -> 7, 1, 5);
    // 7 % 5 + 1 = 2 + 1 = 3
    assertThat(distribution.apply("DoesNotMatter", null)).isEqualTo(3);
  }

  @Test
  public void testNegativeFingerprint() {
    UniformDistribution distribution = new UniformDistribution(i -> -7, 1, 17);
    // -7 signed is bit-equivalent to 2^64-7 unsigned, which is congruent to 11 mod 17.
    assertThat(distribution.apply("DoesNotMatter", null)).isEqualTo(12);
  }

  @Test
  public void testRangeIsClosed() {
    UniformDistribution distribution = new UniformDistribution(i -> 12345, 8, 8);
    assertThat(distribution.apply("DoesNotMatter", null)).isEqualTo(8);
  }
}
