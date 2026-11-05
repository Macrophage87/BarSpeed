package com.macrophage.barspeed.data

import kotlinx.serialization.json.Json
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
 * three levels, the object's `required` keys and its `dependentRequired`
 * rules, so a writer change that emits `workStartedAt_ms` without
 * `prepStartedAt_ms` reds here and not only in a consumer's validator. Neither
 * checks types, array items, the `sensorsSilent` map's words or the set
 * descriptor's `allOf` rules tying `rollExcursionBasis` to `workStartedAt_ms`;
 * CI's ajv step checks those on the published example only, which is
 * hand-built. What this adds is that the manifests checked here are ones
 * `RawExporter` wrote -- and only for the keys the calling test's fixtures
 * make the writer emit.
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
     * Every `required` key [manifest] lacks and every `dependentRequired` rule
     * it breaks, at every level, as `at.key` for a missing required key and
     * `at.key needs other` for a broken dependency. Empty when it meets them all.
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
