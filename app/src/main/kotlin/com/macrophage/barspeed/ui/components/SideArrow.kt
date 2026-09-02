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
 * WHAT IT DRAWS IS WHAT THE SET WILL WORK, which since #215 is not always the
 * prescription. Every caller passes
 * `SideChoicePolicy.carriedIntoNextSet(slot.side, state.statedSide)` or the
 * value that function already wrote into the slot at the bake: the lifter's
 * own choice on the change-next-set control where they made one, and the
 * plan's declaration otherwise. `SetRecordEntity` now pairs `side` with
 * `plannedSide` the way it pairs `loadKg` with `plannedLoadKg`, so the arrow,
 * the row and the export all say the same thing and a deviation is readable
 * in the record -- which is #144, closed by #215 rather than still open
 * beside it.
 *
 * THE PARAGRAPH THAT STOOD HERE IS DELETED RATHER THAN REWORDED. It said this
 * drew the prescription, that `side` had no planned/actual pair, and that
 * recording a deviation was issue #144's to do. The first two are false as of
 * #215 and the third names an issue this change closes. What is still true and
 * kept: this knows no more than the word beside it, and it is not a
 * measurement of anything -- nothing in this app observes which limb moved,
 * and both the word and the arrow are statements a lifter or a plan made.
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
