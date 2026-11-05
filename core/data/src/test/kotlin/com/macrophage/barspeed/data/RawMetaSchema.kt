package com.macrophage.barspeed.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The PUBLISHED `docs/schemas/raw-meta.schema.json` (#326), read from the
 * file a consumer validates against rather than from a copy, and the one
 * question these tests ask of it: which keys, and which words, does a manifest
 * the writer actually produced carry that the schema does not declare?
 *
 * The path arrives as the `barspeed.rawMetaSchema` system property, set in
 * `core/data/build.gradle.kts`, which also declares the file as an input of
 * this module's test tasks so a schema-only change re-runs them.
 *
 * Deliberately narrower than a validator. [undeclaredInManifest] checks every
 * key against the `properties` of the object it sits in -- the top level, each
 * set descriptor, each `sensors` entry -- and every value of a key whose
 * declaration carries a flat `enum`. [unmetInManifest] checks, at the same
 * three levels, the object's `required` keys, its `dependentRequired` rules
 * and its `allOf` if/then rules -- the set descriptor's four, which tie
 * `rollExcursionBasis`, on the set and on each `sensors` entry, to whether
 * the set carries `workStartedAt_ms`. So, ON A PATH THE CALLING TEST'S
 * FIXTURES REACH, a writer change that emits `workStartedAt_ms` without
 * `prepStartedAt_ms`, or a work start without the basis word that must go
 * with it, reds here and not only in a consumer's validator; a path no
 * fixture reaches reds nowhere. The `allOf` reading is deliberately narrow:
 * an `if` may use only `required` and `not`, a `then` only `properties`,
 * `items`, `enum` and `type`, and any other keyword throws rather than being
 * skipped, so a rule this cannot read fails loudly instead of passing. Not
 * checked at all: types, array items outside those rules, and the
 * `sensorsSilent` map's words; CI's ajv step checks those on the published
 * example only, which is hand-built.
 */
internal object RawMetaSchema {
    private val schema: JsonObject by lazy {
        val path =
            checkNotNull(System.getProperty("barspeed.rawMetaSchema")) {
                "barspeed.rawMetaSchema is not set - see core/data/build.gradle.kts"
            }
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    private fun def(name: String): JsonObject = schema.getValue("\$defs").jsonObject.getValue(name).jsonObject

    /** Everything in [manifest], at every level, that the schema does not declare. Empty when it declares it all. */
    fun undeclaredInManifest(manifest: JsonObject): List<String> {
        val found = undeclared(manifest, schema, "")
        manifest["sets"]?.jsonArray?.forEachIndexed { i, set ->
            found += undeclaredInSet(set.jsonObject, "sets[$i].")
        }
        return found
    }

    /** Everything in one set descriptor, and in its `sensors` entries, that the schema does not declare. */
    fun undeclaredInSet(set: JsonObject, at: String = ""): MutableList<String> {
        val found = undeclared(set, def("set"), at)
        set["sensors"]?.jsonArray?.forEachIndexed { j, entry ->
            found += undeclared(entry.jsonObject, def("sensorStream"), "${at}sensors[$j].")
        }
        return found
    }

    /**
     * Every `required` key [manifest] lacks, every `dependentRequired` rule it
     * breaks and every `allOf` rule it breaks, at every level: `at.key` for a
     * missing required key, `at.key needs other` for a broken dependency, and
     * `at.path=value breaks allOf[i]` for a value an if/then rule refuses.
     * Empty when it meets them all.
     */
    fun unmetInManifest(manifest: JsonObject): List<String> {
        val found = unmet(manifest, schema, "")
        manifest["sets"]?.jsonArray?.forEachIndexed { i, set ->
            found += unmetInSet(set.jsonObject, "sets[$i].")
        }
        return found
    }

    /** [unmetInManifest] for one set descriptor and its `sensors` entries. */
    fun unmetInSet(set: JsonObject, at: String = ""): MutableList<String> {
        val found = unmet(set, def("set"), at)
        set["sensors"]?.jsonArray?.forEachIndexed { j, entry ->
            found += unmet(entry.jsonObject, def("sensorStream"), "${at}sensors[$j].")
        }
        return found
    }

    private fun unmet(obj: JsonObject, declaration: JsonObject, at: String): MutableList<String> {
        val found = mutableListOf<String>()
        declaration["required"]?.jsonArray?.forEach { key ->
            val name = key.jsonPrimitive.content
            if (name !in obj) found += "$at$name"
        }
        declaration["dependentRequired"]?.jsonObject?.filterKeys { it in obj }?.forEach { (key, needs) ->
            needs.jsonArray.forEach { need ->
                val name = need.jsonPrimitive.content
                if (name !in obj) found += "$at$key needs $name"
            }
        }
        declaration["allOf"]?.jsonArray?.forEachIndexed { i, rule ->
            val parts = rule.jsonObject
            check(parts.keys == setOf("if", "then")) {
                "allOf[$i] is not an if/then pair this check can read: ${parts.keys}"
            }
            if (matches(obj, parts.getValue("if").jsonObject)) {
                refused(obj, parts.getValue("then").jsonObject, at).forEach { found += "$it breaks allOf[$i]" }
            }
        }
        return found
    }

    /** Whether [obj] meets an `if`, read for `required` and `not` only. */
    private fun matches(obj: JsonObject, condition: JsonObject): Boolean = condition.all { (keyword, value) ->
        when (keyword) {
            "required" -> value.jsonArray.all { it.jsonPrimitive.content in obj }
            "not" -> !matches(obj, value.jsonObject)
            else -> error("an if keyword this check does not read: $keyword")
        }
    }

    /**
     * Every value under [element] that a `then` [fragment] refuses, as
     * `path=value`. A key the fragment names but [element] lacks is not a
     * refusal: in JSON Schema `properties` constrains only what is present.
     * `type` is skipped, as types are everywhere else here.
     */
    private fun refused(element: JsonElement, fragment: JsonObject, at: String): List<String> {
        val found = mutableListOf<String>()
        val path = at.removeSuffix(".")
        for ((keyword, value) in fragment) {
            when (keyword) {
                "type" -> Unit
                "enum" -> if (element !in value.jsonArray) found += "$path=$element"
                "properties" ->
                    (element as? JsonObject)?.let { obj ->
                        value.jsonObject.forEach { (key, sub) ->
                            obj[key]?.let { found += refused(it, sub.jsonObject, "$at$key.") }
                        }
                    }
                "items" ->
                    (element as? JsonArray)?.forEachIndexed { j, item ->
                        found += refused(item, value.jsonObject, "$path[$j].")
                    }
                else -> error("a then keyword this check does not read: $keyword")
            }
        }
        return found
    }

    private fun undeclared(obj: JsonObject, declaration: JsonObject, at: String): MutableList<String> {
        val properties = declaration.getValue("properties").jsonObject
        val found = mutableListOf<String>()
        for ((key, value) in obj) {
            val declared = properties[key]?.jsonObject
            if (declared == null) {
                found += "$at$key"
                continue
            }
            val allowed = declared["enum"]?.jsonArray ?: continue
            if (value !in allowed) found += "$at$key=$value"
        }
        return found
    }
}
