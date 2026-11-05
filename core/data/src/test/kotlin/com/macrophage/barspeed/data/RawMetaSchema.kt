package com.macrophage.barspeed.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * Deliberately narrower than a validator. It checks every key against the
 * `properties` of the object it sits in -- the top level, each set descriptor,
 * each `sensors` entry -- and every value of a key whose declaration carries a
 * flat `enum`. It does not check types, `dependentRequired`, array items or the
 * `sensorsSilent` map's words; CI's ajv step checks all of those on the
 * published example. What this adds is that the manifests checked here are
 * ones `RawExporter` wrote, which the example is not.
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
