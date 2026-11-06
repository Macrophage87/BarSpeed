package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whole-branch review of 0.2.0, round 1, UI-B3 (L2's #169 split against
 * three older strings). History calls a set the app judged short, rather
 * than one the lifter tapped as failed, "Short of target"
 * ([FailureProvenancePolicy.DERIVED_WORD]); the owner's default for #169 is
 * that split. Three Record and Guide strings still told the lifter such a set
 * "logs as failed", so the screen promised one word and History showed
 * another for one state.
 *
 * Read from the shipped source files, which `core/model/build.gradle.kts`
 * puts on this test classpath: the strings live inside composables and a
 * `when` no test here can call. The export key `failed` is unchanged and
 * the Guide's export-key text may still name it.
 */
class ShortOfTargetCopyContractTest {
    private fun source(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/$name")) {
            "$name is not on the test classpath - see core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    private val record = source("RecordScreen.kt")
    private val guide = source("GuideScreen.kt")

    @Test
    fun `no screen says a set that came up short is logged as failed`() {
        assertFalse("logs as failed" in record, "END SET EARLY's caption still says a short set logs as failed")
        assertFalse("ends the set as a failure" in record, "the exit prompt still says the control ends a failure")
        assertFalse("failed automatically" in guide, "How it works still says a short set is logged as failed")
    }

    @Test
    fun `each says short of target, History's word for it`() {
        assertTrue("a set that came up short is recorded as short of target" in record, "END SET EARLY's caption")
        assertTrue("that control ends the set short of target" in record, "the exit prompt")
        assertTrue("because it is recorded as short of target automatically" in guide, "How it works")
    }
}
