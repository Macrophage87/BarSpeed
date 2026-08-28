package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.ui.BarColors

/**
 * How many lines of the visible cue draw before it is cut off, which is the
 * owner's calibration for this card ("still a lot of room on screen") rather
 * than a measurement.
 *
 * It is a backstop, not the mechanism. A plan written against schema 1.8 splits
 * its own cue: `description` is capped at 220 characters so it fits, and
 * `additional_notes` is behind the tap by construction. This cap is what stops
 * the two cases the character limit cannot reach — a plan written before 1.8,
 * whose whole blob is in `notes` and has no limit at all, and any cue at a
 * larger font scale, where four lines hold far fewer characters.
 */
private const val NOTE_COLLAPSED_LINES = 4

/**
 * A plan's coaching cue: the part that shows, and the part one tap reveals.
 *
 * [visible] draws where the note has always drawn. [behindTap] draws only after
 * the lifter expands it, and there is always a LABELLED control to expand with
 * — never an ellipsis alone, which on a phone on the floor mid-session is not
 * an affordance. The whole block is tappable too, so a thumb landing anywhere
 * on the note does something rather than nothing.
 *
 * Never auto-expands, including on the first set of a new exercise: an expanded
 * paragraph pushes the next-set controls off screen, and the lifter did not ask
 * for it. The expansion resets whenever the text changes, so the next
 * exercise's cue starts collapsed rather than inheriting the last one's state,
 * and [rememberSaveable] keeps it expanded across a rotation mid-rest. The
 * overflow latch is saved by the same mechanism, and an open block always
 * carries its own control: an expansion that outlives the reason the control
 * was drawn would strand the note open with no way back.
 *
 * The button also appears when nothing is hidden behind a tap but the visible
 * text itself overflowed [NOTE_COLLAPSED_LINES] — the 1.7 case, where the cue
 * is cut with an ellipsis rather than drawn in full. What is claimed here is
 * what this composable draws; whether a lifter notices the control between
 * sets is a [Field] question on issue #155 and no test in this repository can
 * answer it.
 */
@Composable
fun ExpandableNote(visible: String?, behindTap: String?, color: Color, modifier: Modifier = Modifier) {
    if (visible == null && behindTap == null) return
    var expanded by rememberSaveable(visible, behindTap) { mutableStateOf(false) }
    // Latched from the layout rather than counted from the string: a character
    // count is right at one font scale and one screen width and wrong at every
    // other. Guarded on !expanded because an expanded layout reports no
    // overflow and would clear the latch on the way back.
    //
    // Saved alongside [expanded], not merely remembered. A rotation recreates
    // the activity; an expansion that survives while the latch that justified
    // it does not leaves a note expanded with nothing to collapse it.
    var overflowed by rememberSaveable(visible) { mutableStateOf(false) }
    // `expanded` is a term in its own right, not only through the latch: while
    // the block is open there is a control to close it, whatever the layout
    // last reported.
    val hasMore = behindTap != null || overflowed || expanded

    Column(
        modifier.clickable(enabled = hasMore) { expanded = !expanded },
    ) {
        visible?.let { text ->
            Text(
                "“$text”",
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = if (expanded) Int.MAX_VALUE else NOTE_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout -> if (!expanded) overflowed = layout.hasVisualOverflow },
            )
        }
        if (expanded && behindTap != null) {
            Spacer(Modifier.height(6.dp))
            Text(behindTap, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (hasMore) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    if (expanded) "SHOW LESS" else "SHOW MORE",
                    style = MaterialTheme.typography.labelMedium,
                    color = BarColors.Blue,
                )
            }
        }
    }
}
