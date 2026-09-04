package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `README.md` and `PROMPTS.md` each hard-code the plan schema number, and
 * nothing pinned either of them (#240). Both said 1.8 while the app took 1.11:
 * `PlanFile.SUPPORTED_SCHEMA_VERSIONS` still listed 1.8, so a plan written by
 * following the published docs imported with no warning at all, and the drift
 * was invisible until someone read the two files side by side.
 *
 * `GuidePromptContractTest` is the mechanism this copies. It reads the real
 * `GuideScreen.kt` rather than a copy, because the contract only holds if the
 * document a reader is pointed at is the one the code is pinned to; the same
 * argument applies to the two prose documents, so these are read through the
 * test resources source set from the repository's own files. See the
 * `planDocResources` copy task in `core/model/build.gradle.kts` for why they
 * are copied by name rather than reached with a srcDir on the root.
 *
 * Weaker than the prompt's pin, and said so: the prompt INTERPOLATES
 * [PlanFile.SCHEMA_VERSION], so a bump moves it with no edit at all. These two
 * documents are prose an LLM is never handed and cannot interpolate anything,
 * so the number stays a literal and this test can only fail the bump that
 * forgets one -- it cannot prevent it.
 *
 * The match is anchored on `schemaVersion` and confined to one line: `.` does
 * not match a line terminator unless `DOT_MATCHES_ALL` is set, and it is not,
 * so the whole match lies inside one line and is indifferent to the trailing
 * carriage return `core.autocrlf=true` leaves in a working copy where CI's has
 * none. A size, an offset or a line index would not be.
 *
 * The cardinality is EXACTLY ONE per document, not "at least one". Two sites
 * naming the same number is the shape that drifted in the first place. If a
 * document later gains a legitimate second version site -- an EXPORT skeleton,
 * whose version is `SessionExport.SCHEMA_VERSION`, a different constant -- the
 * fix is to narrow the pattern to the plan site, not to loosen the equality.
 */
class PlanDocVersionContractTest {
    private fun doc(name: String): String = checkNotNull(javaClass.getResourceAsStream("/$name")) {
        "$name is not on the test classpath - see planDocResources and the include filter " +
            "in core/model/build.gradle.kts"
    }.readBytes().decodeToString()

    private fun assertNamesTheAcceptedVersion(name: String) {
        val named = DOC_VERSION.findAll(doc(name)).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(PlanFile.SCHEMA_VERSION),
            named,
            "$name should name the plan contract version exactly once, as ${PlanFile.SCHEMA_VERSION}; " +
                "a plan written from a stale number imports with no warning because " +
                "PlanFile.SUPPORTED_SCHEMA_VERSIONS still accepts it",
        )
    }

    @Test
    fun `README names the plan schema version the app accepts`() = assertNamesTheAcceptedVersion("README.md")

    @Test
    fun `PROMPTS names the plan schema version the app accepts`() = assertNamesTheAcceptedVersion("PROMPTS.md")

    private companion object {
        /**
         * The first quoted `major.minor` after the word `schemaVersion` on the
         * same line. Both documents spell the site differently -- README.md
         * inside a JSON skeleton, `{"schemaVersion": "x.y"`, and PROMPTS.md in
         * prose, `schemaVersion must be "x.y"` -- so the pattern is lazy
         * between the two rather than assuming either punctuation.
         */
        val DOC_VERSION = Regex("""schemaVersion.*?"([0-9]+[.][0-9]+)["]""")
    }
}
