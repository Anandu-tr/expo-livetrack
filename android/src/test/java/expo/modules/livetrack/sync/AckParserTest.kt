package expo.modules.livetrack.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Executable spec for [AckParser].
 *
 * Runs under Robolectric because the stock JVM `org.json` stub throws
 * "not mocked"; Robolectric supplies the real Android implementation.
 *
 * Each case is annotated with the production failure it guards against. The four
 * shapes referenced are the server behaviours that all produced the same symptom:
 * a batch re-uploaded forever under repeated HTTP 2xx responses.
 */
@RunWith(RobolectricTestRunner::class)
class AckParserTest {

  // --- Shape 1: 201 with no usable body ------------------------------------

  @Test fun nullBody_returnsEmpty() {
    assertEquals(emptyList<Long>(), AckParser.parse(null))
  }

  @Test fun blankBody_returnsEmpty() {
    assertEquals(emptyList<Long>(), AckParser.parse(""))
    assertEquals(emptyList<Long>(), AckParser.parse("   "))
  }

  @Test fun htmlErrorPage_returnsEmptyAndDoesNotThrow() {
    assertEquals(emptyList<Long>(), AckParser.parse("<html><body>502 Bad Gateway</body></html>"))
  }

  // --- Canonical contract ---------------------------------------------------

  @Test fun canonicalStringIds_areParsed() {
    assertEquals(listOf(1L, 2L), AckParser.parse("""{"acceptedIds":["1","2"]}"""))
  }

  @Test fun canonicalNumericIds_areParsed() {
    assertEquals(listOf(1L, 2L), AckParser.parse("""{"acceptedIds":[1,2]}"""))
  }

  // --- Shape 2: envelope ----------------------------------------------------

  @Test fun dataEnvelope_isUnwrapped() {
    assertEquals(
      listOf(1043L),
      AckParser.parse("""{"success":true,"data":{"acceptedIds":["1043"]}}"""),
    )
  }

  @Test fun resultEnvelopeWithFieldVariant_isUnwrapped() {
    assertEquals(listOf(7L), AckParser.parse("""{"result":{"ids":[7]}}"""))
  }

  // --- Shape 3: array of objects -------------------------------------------

  @Test fun objectElements_yieldTheirIdKeys() {
    assertEquals(
      listOf(1043L, 7L),
      AckParser.parse("""{"acceptedIds":[{"id":"1043"},{"rowId":7}]}"""),
    )
  }

  // --- Field-name variants --------------------------------------------------

  @Test fun fieldNameVariants_areAccepted() {
    assertEquals(listOf(5L), AckParser.parse("""{"accepted":["5"]}"""))
    assertEquals(listOf(5L), AckParser.parse("""{"acceptedIDs":["5"]}"""))
    assertEquals(listOf(5L), AckParser.parse("""{"ids":["5"]}"""))
  }

  @Test fun bareTopLevelArray_isAccepted() {
    assertEquals(listOf(1L, 2L), AckParser.parse("""["1","2"]"""))
  }

  // --- Safety invariants ----------------------------------------------------

  @Test fun junkElements_areSkippedAndNeverInvented() {
    // "abc" is not a Long, null/{} carry no id, {"nope":1} has no recognised key.
    assertEquals(listOf(5L), AckParser.parse("""{"acceptedIds":["abc",null,{},{"nope":1},5]}"""))
  }

  @Test fun noArrayAnywhere_returnsEmpty() {
    assertEquals(emptyList<Long>(), AckParser.parse("""{"success":true}"""))
    assertEquals(emptyList<Long>(), AckParser.parse("""{"acceptedIds":null}"""))
  }

  @Test fun duplicateIds_areCollapsed() {
    assertEquals(listOf(1L), AckParser.parse("""{"acceptedIds":["1","1",1]}"""))
  }

  @Test fun rootFieldWinsOverEnvelope() {
    assertEquals(
      listOf(9L),
      AckParser.parse("""{"acceptedIds":["9"],"data":{"acceptedIds":["1"]}}"""),
    )
  }
}
