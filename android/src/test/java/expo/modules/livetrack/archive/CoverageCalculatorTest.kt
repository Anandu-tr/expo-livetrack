package expo.modules.livetrack.archive

import expo.modules.livetrack.archive.CoverageCalculator.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageCalculatorTest {
  private val min = 60_000L

  @Test fun emptyInput() {
    val r = CoverageCalculator.compute(emptyList())
    assertEquals(0, r.count)
    assertNull(r.firstT)
    assertNull(r.lastT)
    assertTrue(r.gaps.isEmpty())
  }

  @Test fun contiguousPointsHaveNoGaps() {
    val pts = (0 until 30).map { Pt(it * min, 9.0 + it * 0.0001, 76.0) }
    val r = CoverageCalculator.compute(pts)
    assertEquals(30, r.count)
    assertEquals(0L, r.firstT)
    assertEquals(29 * min, r.lastT)
    assertTrue(r.gaps.isEmpty())
  }

  @Test fun oneHoleIsReportedWithDurationAndDistance() {
    val pts = listOf(
      Pt(0, 9.00, 76.0),
      Pt(1 * min, 9.00, 76.0),
      Pt(31 * min, 9.01, 76.0), // 30-min hole, 0.01° lat ≈ 1112 m
      Pt(32 * min, 9.01, 76.0),
    )
    val r = CoverageCalculator.compute(pts)
    assertEquals(1, r.gaps.size)
    val g = r.gaps[0]
    assertEquals(1 * min, g.fromT)
    assertEquals(31 * min, g.toT)
    assertEquals(30 * min, g.durationMs)
    assertEquals(1112.0, g.distanceM, 2.0)
  }

  @Test fun unsortedAndDuplicateTimestampsAreNormalised() {
    val pts = listOf(Pt(20 * min, 9.0, 76.0), Pt(0, 9.0, 76.0), Pt(0, 9.5, 76.0), Pt(5 * min, 9.0, 76.0))
    val r = CoverageCalculator.compute(pts)
    assertEquals(3, r.count)
    assertEquals(0L, r.firstT)
    assertEquals(20 * min, r.lastT)
    assertEquals(1, r.gaps.size) // 5 → 20 min
  }

  @Test fun customGapThreshold() {
    val pts = listOf(Pt(0, 9.0, 76.0), Pt(3 * min, 9.0, 76.0))
    assertTrue(CoverageCalculator.compute(pts).gaps.isEmpty())
    assertEquals(1, CoverageCalculator.compute(pts, gapMs = 2 * min).gaps.size)
  }
}
