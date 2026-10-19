package expo.modules.livetrack.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemAutostartTest {

  @Test
  fun `known manufacturers return non-empty candidates`() {
    for (m in listOf("Xiaomi", "xiaomi", "OPPO", "vivo", "Realme")) {
      assertTrue("expected candidates for $m", OemAutostart.candidatesFor(m).isNotEmpty())
    }
  }

  @Test
  fun `unknown manufacturer returns empty list`() {
    assertEquals(emptyList<Any>(), OemAutostart.candidatesFor("Google"))
    assertEquals(emptyList<Any>(), OemAutostart.candidatesFor("samsung"))
  }

  @Test
  fun `isKnownAggressiveOem matches candidatesFor emptiness`() {
    assertTrue(OemAutostart.isKnownAggressiveOem("xiaomi"))
    assertFalse(OemAutostart.isKnownAggressiveOem("google"))
  }

  @Test
  fun `lookup is case-insensitive and trims whitespace`() {
    assertTrue(OemAutostart.isKnownAggressiveOem("  XIAOMI  "))
  }

  @Test
  fun `POCO and Redmi brands map to the Xiaomi settings even if manufacturer differs`() {
    assertTrue(OemAutostart.isKnownAggressiveOem("POCO", "POCO"))
    assertTrue(OemAutostart.isKnownAggressiveOem("unknown", "Redmi"))
    assertEquals(OemAutostart.candidatesFor("xiaomi"), OemAutostart.candidatesFor("poco", "poco"))
    assertFalse(OemAutostart.isKnownAggressiveOem("Google", "google"))
  }

  @Test
  fun `xiaomi family covers the manufacturer and its sub-brands only`() {
    assertTrue(OemAutostart.isXiaomiFamily("Xiaomi", "POCO"))
    assertTrue(OemAutostart.isXiaomiFamily("whatever", "redmi"))
    assertFalse(OemAutostart.isXiaomiFamily("OPPO", "realme"))
  }
}
