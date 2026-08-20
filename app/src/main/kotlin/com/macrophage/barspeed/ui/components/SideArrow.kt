package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.ui.BarColors

/** Share of the arrow's length taken by the head; the remainder is shaft. */
private const val HEAD_LENGTH_FRACTION = 0.45f

/** Shaft thickness as a share of the head's height. */
private const val SHAFT_HEIGHT_FRACTION = 0.34f

/**
 * A unilateral set's side, drawn as an arrow beside the word it qualifies.
 *
 * WHAT IT DRAWS IS THE PRESCRIPTION, NOT AN OBSERVATION. On a planned set
 * `RecordViewModel` resolves the side as `slot?.side` and consults
 * `sideInput` -- what the Both/Left/Right selector binds to -- only when the
 * set is ad-hoc, so a planned set stores a copy of what the plan asked for.
 * `SetRecordEntity` pairs `loadKg` with `plannedLoadKg`, `actualReps` with
 * `plannedReps` and `actualDurationS` with `plannedDurationS`; `side` is a
 * single field with no such pair, so there is nowhere for a worked side to be
 * recorded even if something measured it, and nothing here does. This draws
 * that same prescribed value as a shape. It knows no more than the word
 * beside it, and no reading of it can say which limb moved. Recording a
 * deviation is issue #124.
 *
 * The word stays. The export, the review screen and every screen-reader path
 * read the text, and this draws nothing any of them can read: the arrow is
 * decorative, and `clearAndSetSemantics {}` is the Canvas equivalent of the
 * null `contentDescription` issue #91 asks for. Without it a future wrapper
 * that carried semantics would announce a side TalkBack has already spoken.
 *
 * Colour is not part of the signal -- both sides draw in the same [color], and
 * [color] is set by the caller to say how current the set is, not which side it
 * is. Direction is the whole of the difference: the head is the full height of
 * the glyph and the shaft about a third of it, so the two orientations differ
 * in which end is solid rather than only in which way a symmetric shape points.
 *
 * The arrow points the way the word does: "left" points to screen-left. The
 * alternative is to mirror it, on the argument that the lifter's left is the
 * screen's right when the phone faces them. Nothing here has measured which of
 * the two reads better and nothing here can; #91 says to ship one mapping and
 * settle it in a session. Flipping it is the two branches of [pointsLeft].
 *
 * A [side] that is neither "left" nor "right" draws nothing, so a value that
 * never passed `PlanFile.VALID_SIDES` degrades to the text on its own.
 */
@Composable
fun SideArrow(
    side: String?,
    modifier: Modifier = Modifier,
    color: Color = BarColors.Volt,
    length: Dp = 30.dp,
    thickness: Dp = 17.dp,
) {
    val pointsLeft =
        when (side) {
            "left" -> true
            "right" -> false
            else -> return
        }
    Canvas(modifier.size(width = length, height = thickness).clearAndSetSemantics {}) {
        val headLength = size.width * HEAD_LENGTH_FRACTION
        val shaftHeight = size.height * SHAFT_HEIGHT_FRACTION
        val midY = size.height / 2f
        val headBaseX = if (pointsLeft) headLength else size.width - headLength
        val head =
            Path().apply {
                moveTo(if (pointsLeft) 0f else size.width, midY)
                lineTo(headBaseX, 0f)
                lineTo(headBaseX, size.height)
                close()
            }
        drawPath(head, color)
        drawRoundRect(
            color = color,
            topLeft = Offset(if (pointsLeft) headLength else 0f, midY - shaftHeight / 2f),
            size = Size(size.width - headLength, shaftHeight),
            cornerRadius = CornerRadius(shaftHeight / 2f),
        )
    }
}
