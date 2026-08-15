package com.macrophage.barspeed.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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

    private val json = Json { ignoreUnknownKeys = true }

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
        val keys = unknownKeyWarnings(text)
        return Result(
            plan = plan,
            errors = plan.validate(),
            // Ordered by consequence: what was lost, then what was overridden,
            // then what was merely extra. A dropped load outranks a declaration
            // the lifter made on purpose, which outranks a key that carried
            // nothing.
            warnings = keys.lost + plan.warnings() + keys.extraneous,
        )
    }

    private class KeyWarnings(val lost: List<String>, val extraneous: List<String>)

    /** One level of the plan document, with the keys it recognises. */
    @OptIn(ExperimentalSerializationApi::class)
    private enum class Level(val article: String, private val descriptor: () -> SerialDescriptor) {
        PLAN("the plan", { PlanFile.serializer().descriptor }),
        SESSION("a session", { PlanSessionDef.serializer().descriptor }),
        EXERCISE("an exercise", { PlanExerciseDef.serializer().descriptor }),
        SET("a set", { PlanSetDef.serializer().descriptor }),
        ;

        /**
         * Wire names, taken from the serializer rather than retyped, so a
         * `@SerialName` rename moves this and the check keeps matching what the
         * decoder actually accepts.
         */
        val keys: Set<String> by lazy { descriptor().elementNames.toSet() }
    }

    /**
     * Lowercased with underscores removed. Two keys collide here exactly when
     * they are the same word spelled camelCase and snake_case, which is the one
     * mistake worth naming: `PlanSetDef` renames five of its ten keys, so a
     * plan writing `loadKg` for `load_kg` is writing the Kotlin spelling of a
     * real key. No edit distance and no threshold — a key that is merely
     * similar, like `rest` for `rest_s`, is not this mistake and gets the
     * ordinary unknown-key warning instead of a confident correction.
     */
    private fun normalize(key: String) = key.lowercase().replace("_", "")

    private class Finding(val path: String, val level: Level, val key: String)

    private fun unknownKeyWarnings(text: String): KeyWarnings {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return KeyWarnings(emptyList(), emptyList())

        val findings = mutableListOf<Finding>()
        fun visit(obj: JsonObject, level: Level, path: String) {
            obj.keys.filterNot { it in level.keys }.forEach { findings += Finding(path, level, it) }
        }
        visit(root, Level.PLAN, "")
        (root["sessions"] as? JsonArray).orEmpty().forEachIndexed { si, sessionElement ->
            val session = sessionElement as? JsonObject ?: return@forEachIndexed
            visit(session, Level.SESSION, "sessions[$si]")
            (session["exercises"] as? JsonArray).orEmpty().forEachIndexed { ei, exerciseElement ->
                val exercise = exerciseElement as? JsonObject ?: return@forEachIndexed
                visit(exercise, Level.EXERCISE, "sessions[$si].exercises[$ei]")
                (exercise["sets"] as? JsonArray).orEmpty().forEachIndexed { xi, setElement ->
                    val set = setElement as? JsonObject ?: return@forEachIndexed
                    visit(set, Level.SET, "sessions[$si].exercises[$ei].sets[$xi]")
                }
            }
        }

        val lost = mutableListOf<String>()
        val extraneous = mutableListOf<Finding>()
        findings.forEach { finding ->
            val nearMiss = finding.level.keys.firstOrNull { normalize(it) == normalize(finding.key) }
            val elsewhere = Level.entries.filter { it != finding.level && finding.key in it.keys }
            when {
                // Reported one by one rather than aggregated, unlike the merely
                // extraneous: an ignored key carries the same nothing wherever
                // it appears, but a dropped load is a different missing number
                // on every set, and the lifter has to know which sets.
                nearMiss != null -> lost += nearMissWarning(finding, nearMiss)
                elsewhere.isNotEmpty() -> lost += wrongLevelWarning(finding)
                else -> extraneous += finding
            }
        }

        return KeyWarnings(lost, aggregate(extraneous))
    }

    private fun nearMissWarning(finding: Finding, intended: String): String {
        val consequence =
            when (intended) {
                "load_kg", "load_lb" ->
                    "That set imported with no load at all, and records as bodyweight."
                else -> "It was ignored."
            }
        return "${finding.path}: \"${finding.key}\" is not a plan key - " +
            "did you mean \"$intended\"? $consequence"
    }

    private fun wrongLevelWarning(finding: Finding): String {
        // Worded per case, because the consequence differs per case. `notes`
        // on a set is the one that silently drops the lifter's own words: the
        // set-level key is `note`, and the two are not a camelCase/snake_case
        // pair, so nothing above catches it.
        val advice =
            if (finding.key == "notes" && finding.level == Level.SET) {
                "a comment on a single set is \"note\""
            } else {
                "it belongs on ${Level.entries.first { finding.key in it.keys }.article}"
            }
        return "${finding.path}: \"${finding.key}\" is a plan key, but not one " +
            "${finding.level.article} has - $advice. It was ignored."
    }

    /** One line per distinct key, naming where it first appeared and how often. */
    private fun aggregate(findings: List<Finding>): List<String> = findings
        .groupBy { it.key }
        .map { (key, all) ->
            val places = if (all.size > 1) " (${all.size} places in this plan)" else ""
            "${all.first().path}: unknown key \"$key\" was ignored$places."
        }
}
