package com.macrophage.barspeed.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v20 -> v21 migration: two columns on `set_records`.
 * `analysisSchemaVersion`, nullable TEXT -- the session-export schema version
 * current in the build that computed the row's `analysisJson` (#38); and
 * `recovered`, `INTEGER NOT NULL DEFAULT 0` -- whether the row was written from
 * an interrupted set's capture rather than by the set ending (#100).
 *
 * `recovered` joined this hop before 21 shipped: no `v*` tag carries
 * `DATABASE_VERSION = 21`, so no installed build has run this hop without it.
 *
 * ## A NEW HOP, not an extension of 20
 *
 * v0.1.56 through v0.1.59 SHIP `DATABASE_VERSION = 20`, read by
 * `git show <tag>:core/data/src/main/kotlin/com/macrophage/barspeed/data/AppDatabase.kt`
 * rather than assumed, so an installed build has run 19 -> 20 and extending
 * that hop would leave a phone that already migrated without the column.
 *
 * ## What this verifies, exactly
 *
 * [Migration19To20Test]'s instrument, one hop later: the real migration body
 * runs against a recording `SupportSQLiteDatabase`, and what it executes is
 * compared with the DIFFERENCE between `schemas/.../20.json` and
 * `schemas/.../21.json`. Nothing here executes SQL -- there is no SQLite on
 * this module's test classpath and no `androidTest` source set anywhere -- so
 * this cannot say that SQLite accepts the statement, that a real file survives
 * it, or that Room's own `TableInfo` check passes afterwards. The two-way
 * emulator exercise at the cut is owed for those.
 *
 * HOW 21.json WAS MADE, said because it differs from its predecessors. No
 * Android SDK exists where this lane ran, so Room's compiler could not write
 * it. It is `20.json` with the two fields added to `set_records` in entity
 * order, that table's `createSql` extended to match, the version set to 21 and
 * `identityHash` recomputed by a re-implementation of Room 2.7.1's
 * `SchemaIdentityKey` (read from `room-compiler-2.7.1-sources.jar`) that
 * reproduces the committed hash of every one of `10.json` through `20.json`
 * exactly. CI's `./gradlew test` runs Room's schema export over the same
 * directory, so a hand-made file that disagreed with the entities would be
 * replaced there; the comparison below then reads whatever Room wrote.
 *
 * NOT A DIFFERENTIAL. Every test here passes at the commit that adds it, which
 * also adds the hop: a declared-and-unregistered hop fails on the lifter's
 * phone rather than anywhere a test can see it, so the migration and its pins
 * arrive together.
 */
class Migration20To21Test {
    private val json = Json { ignoreUnknownKeys = true }

    /** [Migration10To11Test]'s lookup, stated again because it is private there. */
    private fun schema(version: Int): File {
        val relative = "schemas/com.macrophage.barspeed.data.AppDatabase/$version.json"
        val candidates = listOf(File(relative), File("core/data/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: error("no committed schema baseline for v$version; tried ${candidates.map { it.absolutePath }}")
    }

    private fun columnsOf(version: Int, table: String): Map<String, Triple<String, Boolean, String?>> {
        val root = json.parseToJsonElement(schema(version).readText()).jsonObject
        val entity =
            root.getValue("database").jsonObject.getValue("entities").jsonArray
                .map { it.jsonObject }
                .first { it.getValue("tableName").jsonPrimitive.content == table }
        return entity.getValue("fields").jsonArray.associate { field ->
            val f = field.jsonObject
            f.getValue("columnName").jsonPrimitive.content to
                Triple(
                    f.getValue("affinity").jsonPrimitive.content,
                    f["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                    f["defaultValue"]?.jsonPrimitive?.content,
                )
        }
    }

    private fun declaredVersion(version: Int): Int {
        val root = json.parseToJsonElement(schema(version).readText()).jsonObject
        return root.getValue("database").jsonObject.getValue("version").jsonPrimitive.int
    }

    /** Every statement the migration asks for, in the order it asks for them. */
    private fun executedSql(): List<String> {
        val executed = mutableListOf<String>()
        val recorder =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL" && args != null) executed += args[0] as String
                null
            } as SupportSQLiteDatabase
        AppDatabase.MIGRATION_20_21.migrate(recorder)
        return executed
    }

    /**
     * The compiled version, the committed baseline and the migration's own
     * endpoints are one number.
     *
     * The assertion the NEWEST hop owes the constant, moved here from
     * [Migration19To20Test] the way that file took it from its predecessor: a
     * constant left behind at 20 ships a build whose schema the chain cannot
     * reach, and Room throws on the lifter's phone.
     */
    @Test
    fun `the compiled version, the committed baseline and the migration agree on twenty-one`() {
        assertEquals(21, DATABASE_VERSION, "DATABASE_VERSION is not the version this migration ends at")
        assertEquals(21, declaredVersion(21), "21.json does not describe version 21")
        assertEquals(20, declaredVersion(20), "20.json does not describe version 20")
        assertEquals(20, AppDatabase.MIGRATION_20_21.startVersion)
        assertEquals(21, AppDatabase.MIGRATION_20_21.endVersion)
    }

    /**
     * v21 differs from v20 by exactly the two set_records columns: the
     * provenance, nullable TEXT with no default, and the recovered flag, a
     * NOT NULL INTEGER.
     *
     * Read off the schema descriptions, so a third column, a retyped one and a
     * dropped one are all caught. The committed description carries no
     * `defaultValue` for `recovered`, as it carries none for `added` or
     * `voided`: the entity declares no `@ColumnInfo` default, and the SQL
     * default lives in the migration's statement, pinned below.
     */
    @Test
    fun `the schema baselines differ by exactly the provenance and recovered columns`() {
        for (table in listOf("raw_streams", "sessions", "plans", "custom_exercises")) {
            assertEquals(
                emptySet(),
                (columnsOf(21, table) - columnsOf(20, table).keys).keys,
                "$table gained a column this migration does not append",
            )
        }
        assertEquals(
            mapOf(
                "analysisSchemaVersion" to Triple("TEXT", false, null),
                "recovered" to Triple("INTEGER", true, null),
            ),
            columnsOf(21, "set_records") - columnsOf(20, "set_records").keys,
            "set_records gained something other than the provenance and recovered columns",
        )
    }

    /** Nothing that existed at v20 changed its type, nullability or default. */
    @Test
    fun `no column that existed at v20 changed its type, nullability or default`() {
        for (table in listOf("raw_streams", "set_records", "sessions", "plans", "custom_exercises")) {
            val before = columnsOf(20, table)
            val after = columnsOf(21, table)
            assertEquals(emptyList(), before.keys.filter { it !in after }, "$table lost a column between v20 and v21")
            assertEquals(
                emptyMap(),
                before.filter { (name, spec) -> after.getValue(name) != spec },
                "$table changed a column that already existed at v20",
            )
        }
    }

    /** The migration executes exactly the two appends, asserted as a whole list. */
    @Test
    fun `the migration runs the two add-column statements and nothing else`() {
        assertEquals(
            listOf(
                "ALTER TABLE set_records ADD COLUMN analysisSchemaVersion TEXT",
                "ALTER TABLE set_records ADD COLUMN recovered INTEGER NOT NULL DEFAULT 0",
            ),
            executedSql(),
        )
    }

    /**
     * A plain append that cannot rewrite the table -- a recreate would copy
     * every gzipped IMU blob in the lifter's history.
     */
    @Test
    fun `the statement is a plain append and is not destructive`() {
        assertTrue(executedSql().isNotEmpty(), "the migration executes nothing at all")
        for (sql in executedSql()) {
            assertTrue(sql.startsWith("ALTER TABLE "), "not a column append: $sql")
            assertTrue("ADD COLUMN" in sql, "not a column append: $sql")
            assertTrue("DROP" !in sql.uppercase() && "RENAME" !in sql.uppercase(), "destructive statement: $sql")
        }
        val provenance = executedSql().single { "analysisSchemaVersion" in it }
        assertTrue("NOT NULL" !in provenance.uppercase(), "an old row has no provenance to give: $provenance")
        assertTrue(
            "DEFAULT" !in provenance.uppercase(),
            "a default names a version for every set already recorded: $provenance",
        )
    }

    /**
     * Every row that existed at v20 reads as NOT recovered (#100).
     *
     * The opposite rule from the provenance column, and for a measured reason:
     * before v21 nothing could write a set row from an interrupted capture, so
     * every existing row was written by its set ending. NOT NULL with a
     * default of 0 says exactly that; a nullable column would leave every old
     * row "unknown" about a fact that is known.
     */
    @Test
    fun `every row that existed at v20 reads as not recovered`() {
        val recovered = executedSql().single { "recovered" in it }
        assertTrue("NOT NULL" in recovered.uppercase(), "an old row would read as unknown: $recovered")
        assertTrue("DEFAULT 0" in recovered.uppercase(), "an old row would not read as not recovered: $recovered")
    }

    /**
     * No existing row is given a version.
     *
     * The obvious backfill -- today's `SessionExport.SCHEMA_VERSION` -- is the
     * #38 defect itself: it would claim current semantics for figures an older
     * build computed. Null says "before provenance", which is the truth.
     */
    @Test
    fun `the migration writes no value into any existing row`() {
        assertEquals(
            emptyList(),
            executedSql().filter { it.uppercase().startsWith("UPDATE ") || it.uppercase().startsWith("INSERT ") },
            "the migration backfills a version nobody recorded",
        )
    }

    /**
     * The chain is unbroken from 1 to the compiled version. The whole-chain
     * equality moved here from [Migration19To20Test] with the newest hop.
     */
    @Test
    fun `the hop is in the chain the database is built with`() {
        assertEquals(
            (1..20).map { it to it + 1 },
            AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion },
            "the migration chain is not an unbroken 1 -> 21",
        )
    }
}
