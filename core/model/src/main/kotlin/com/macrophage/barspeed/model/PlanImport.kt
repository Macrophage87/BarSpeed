package com.macrophage.barspeed.model

import kotlinx.serialization.json.Json

/**
 * The one way a plan document becomes a [PlanFile].
 *
 * This lives here rather than beside its caller in `:core:data` for the reason
 * [SetLoadPolicy] gives: no plain JVM test can reach it where it was written.
 * `:core:data` has no test source directory, and its one caller is a
 * `suspend` method on a Room-backed repository. Nothing in this file touches
 * Android, Room or a sensor — text in, a plan and two lists of sentences out.
 *
 * Having exactly one entry point is the point. `PlanRepository` used to decode
 * in two places against one shared `Json`, and the shared instance was the only
 * thing keeping them in step; a second decoder added to either would have been
 * invisible. Routing both through here makes the two paths the same code rather
 * than two paths that happen to agree.
 *
 * The warning list is deliberately not the error list. An extra key, a
 * misspelled key, or a declaration that disagrees with the built-in definition
 * are all things the lifter should see and be able to ask their plan's author
 * about — none of them is a reason to refuse the whole file. Contradictions
 * are: two mutually exclusive instructions for one set cannot both be honoured,
 * so those stay errors.
 */
object PlanImport {
    /**
     * [plan] is null only when the document could not be decoded at all.
     * A non-null plan with a non-empty [errors] is a plan that parsed and then
     * failed validation, which is the state `PlanRepository.decode` has always
     * returned to its callers.
     */
    data class Result(
        val plan: PlanFile?,
        val errors: List<String>,
        val warnings: List<String>,
    )

    // Structural stub. This decoder still rejects unknown keys, so both call
    // sites behave exactly as they did before they were routed through here,
    // and `warnings` carries only what PlanFile itself already reports. The
    // key-level warnings and the lenient decode arrive together, later.
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): Result {
        val plan =
            try {
                json.decodeFromString(PlanFile.serializer(), text)
            } catch (e: Exception) {
                return Result(
                    plan = null,
                    errors = listOf("Not valid plan JSON: ${e.message?.lineSequence()?.first() ?: "parse error"}"),
                    warnings = emptyList(),
                )
            }
        return Result(plan = plan, errors = plan.validate(), warnings = plan.warnings())
    }
}
