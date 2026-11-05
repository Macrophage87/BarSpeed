package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which kinds of set have a stroke for `geometry.startsWith` and
 * `geometry.concentric` to describe (#297). [GeometryExport.publishesStroke] is
 * the one statement of the split, read by the session document and the raw
 * archive's manifest.
 *
 * Over every [ExerciseKind] rather than the two cases #297 names, so a kind
 * added later has to be placed on one side or the other by somebody.
 */
class StrokeGeometryTest {
    @Test
    fun `a hold and a carry have no stroke, and every other kind has one`() {
        assertEquals(
            mapOf(
                ExerciseKind.DYNAMIC to true,
                ExerciseKind.HOLD to false,
                ExerciseKind.CARRY to false,
                ExerciseKind.EXPLOSIVE to true,
            ),
            ExerciseKind.entries.associateWith(GeometryExport::publishesStroke),
        )
    }

    /** The split is the one the velocity-loss regime draws: no word on either where the other has none. */
    @Test
    fun `the kinds with no stroke are exactly the kinds with no velocity-loss regime`() {
        ExerciseKind.entries.forEach { kind ->
            val regime =
                VelocityLossRegime.of(tempoPrescribed = "3010", concentricUp = true, horizontal = false, kind = kind)
            assertEquals(GeometryExport.publishesStroke(kind), regime != null, "the two disagree on $kind")
        }
    }
}
