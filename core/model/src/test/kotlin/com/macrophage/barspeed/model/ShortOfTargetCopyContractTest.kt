package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whole-branch review of 0.2.0, round 1, UI-B3 (L2's #169 split against
 * older strings). History calls a set the app judged short, rather than one
 * the lifter tapped as failed, "Short of target"
 * ([FailureProvenancePolicy.DERIVED_WORD]); the owner's default for #169 is
 * that split. The Guide's How-it-works still told the lifter such a set is
 * "logged as failed automatically", so the Guide promised one word and
 * History showed another for one state.
 *
 * Read from the shipped GuideScreen.kt, which `core/model/build.gradle.kts`
 * puts on this test classpath (the only Kotlin source it admits). The two
 * Record strings are pinned in `app/src/test`, `ShortOfTargetCopyTest`. The
 * export key `failed` is unchanged and the Guide's export-key text may still
 * name it.
 */
class ShortOfTargetCopyContractTest {
    private val guide: String =
        checkNotNull(javaClass.getResourceAsStream("/kotlin/com/macrophage/barspeed/ui/screens/GuideScreen.kt")) {
            "GuideScreen.kt is not on the test classpath - see core/model/build.gradle.kts"
        }.readBytes().decodeToString()

    @Test
    fun `the guide says a set stopped short is recorded as short of target`() {
        assertFalse("failed automatically" in guide, "How it works still says a short set is logged as failed")
        assertTrue("because it is recorded as short of target automatically" in guide, "How it works")
    }
}
