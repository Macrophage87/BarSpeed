package com.macrophage.barspeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macrophage.barspeed.model.ArmedDelivery
import com.macrophage.barspeed.model.ArmedSilencePolicy
import com.macrophage.barspeed.model.ConnectionState
import com.macrophage.barspeed.ui.BarColors
import kotlinx.coroutines.delay

private const val CHIP_BG_ALPHA = 0.15f

/** Small pill for set verdicts: tinted background, saturated text. */
@Composable
fun VerdictChip(text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val color =
        when (tone) {
            ChipTone.OK -> BarColors.Volt
            ChipTone.WARN -> BarColors.Amber
            ChipTone.BAD -> BarColors.Red
            ChipTone.NEUTRAL -> BarColors.Sub
        }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier =
        modifier
            .background(color.copy(alpha = CHIP_BG_ALPHA), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * Watch one link's DELIVERY, re-answered once a second for as long as this is
 * composed (#213).
 *
 * A ticking composable rather than a value, and that is forced: whether a unit
 * has gone quiet changes as time passes with no state change to drive a
 * recomposition, so something has to ask again. One second against
 * `ArmedSilencePolicy`'s three-second window, so the answer is at worst a
 * second late.
 *
 * The JUDGEMENT is `ArmedSilencePolicy.deliveryOf`'s, in `:core:model` where a
 * test runs on it. What is here is the tick, which nothing in this repository
 * can execute -- no test on the CI path composes anything.
 */
@Composable
fun rememberArmedDelivery(state: ConnectionState, frameAtMs: Long?, armedAtMs: Long): ArmedDelivery {
    // remember + LaunchedEffect rather than produceState, and that is lint's
    // call rather than a preference: `:app:lintDebug` reds the produceState
    // form here with ProduceStateDoesNotAssignValue, which its own producer
    // lambda plainly does. The two shapes are equivalent -- one state, one
    // coroutine keyed on the same three inputs -- and this one passes.
    val delivery = remember { mutableStateOf(ArmedDelivery.TOO_SOON) }
    LaunchedEffect(state, frameAtMs, armedAtMs) {
        while (true) {
            delivery.value = ArmedSilencePolicy.deliveryOf(state, frameAtMs, armedAtMs, System.currentTimeMillis())
            delay(1_000)
        }
    }
    return delivery.value
}

/**
 * Status-bar style connection dot: volt when live, amber while reconnecting,
 * red on a failure, grey when never tried. [demoActive] overrides to volt
 * regardless of [state] -- demo mode fabricates samples with no sensor
 * present, and the dot exists to answer "is a sensor talking to me," which
 * demo mode is deliberately lying about everywhere else on screen too.
 *
 * [delivery] is what makes that question answerable rather than merely asked
 * (#213). Without it a volt dot means the app ISSUED a notification subscribe
 * -- `GattClient` publishes Connected before anything comes back down the link
 * -- and field-37's lifter read exactly that as capture on thirteen sets that
 * captured nothing from the unit the dot was drawn for. Passing it makes volt
 * mean FRAMES ARE ARRIVING and amber mean "linked, and nothing has".
 *
 * NULL MEANS THE CALLER DOES NOT OBSERVE DELIVERY, not that the unit is
 * silent, and such a dot renders exactly as it always has. The heart-rate
 * strap passes null: nothing publishes its frame arrivals, and a dot that
 * turned amber because nobody looked would be the same false claim in the
 * other direction.
 */
@Composable
fun SensorDot(
    label: String,
    state: ConnectionState,
    modifier: Modifier = Modifier,
    demoActive: Boolean = false,
    delivery: ArmedDelivery? = null,
) {
    // The when below has a subject, deliberately, so a fifth ConnectionState
    // variant fails this compile instead of falling into a silent else.
    // demoActive is checked outside it because it is not a fact about state.
    val color =
        if (demoActive) {
            BarColors.Volt
        } else {
            when (state) {
                is ConnectionState.Connected -> connectedTone(delivery)
                is ConnectionState.Connecting -> BarColors.Amber
                is ConnectionState.Failed -> BarColors.Red
                is ConnectionState.Disconnected -> BarColors.Ghost
            }
        }
    Text(
        "$label ●",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}

/**
 * Chip summarizing a device's link state, tinted by connection status.
 *
 * Failed and Disconnected used to collapse into one grey "✗": a sensor that
 * refused to connect looked exactly like one that was simply off, so the chip
 * could not say a permission had been denied. They are now separate tones, and
 * the branches are exhaustive over [ConnectionState] so a new state has to be
 * decided rather than falling into the grey. The reason string itself is
 * rendered by the caller — the pill is too narrow for a framework diagnostic.
 * [ConnectionState.Failed] has ten producers in `GattClients.kt` carrying
 * six distinct reasons, and exactly one of them interpolates a raw GATT status
 * integer (the service-discovery failure); an earlier version of this comment
 * said seven producers, before three more call sites gained a catch for the
 * same permission-revoked race `connect()` already guarded against, all three
 * reusing the existing reason string rather than adding a new one.
 */
@Composable
fun ConnectionChip(
    label: String,
    state: ConnectionState,
    modifier: Modifier = Modifier,
    delivery: ArmedDelivery? = null,
) {
    val (text, tone) =
        when (state) {
            is ConnectionState.Connected ->
                if (delivery == ArmedDelivery.LINKED_SILENT) {
                    // The word rather than the tick, because the tick is what
                    // the lifter read as capture. It says what the app can
                    // support -- a link with nothing coming down it -- and not
                    // why, which the app does not know.
                    "$label no data" to ChipTone.WARN
                } else {
                    ("$label ✓" + (state.batteryPct?.let { " $it%" } ?: "")) to ChipTone.OK
                }
            is ConnectionState.Connecting -> "$label …" to ChipTone.WARN
            is ConnectionState.Failed -> "$label ✗" to ChipTone.BAD
            is ConnectionState.Disconnected -> "$label ✗" to ChipTone.NEUTRAL
        }
    VerdictChip(text, tone, modifier)
}

/**
 * What a CONNECTED link's dot is worth, once delivery is known (#213).
 *
 * Volt stays volt where nothing observes delivery, so every dot that passes
 * null renders as it always has. Where delivery IS observed, volt means frames
 * are arriving and amber means the link is up and nothing is: the same amber
 * `Connecting` already uses, which is the honest neighbour -- both are "not
 * feeding the app yet". [ArmedDelivery.TOO_SOON] is amber for the same reason
 * and not grey: the app does not know yet, and a grey dot on a link that is up
 * would be a claim it never made.
 */
private fun connectedTone(delivery: ArmedDelivery?) = when (delivery) {
    null, ArmedDelivery.DELIVERING -> BarColors.Volt
    ArmedDelivery.TOO_SOON, ArmedDelivery.LINKED_SILENT -> BarColors.Amber
    // Reachable only if a link reports Connected while the reading says no
    // link, which is a disagreement between two facts rather than a state.
    // Amber says "not feeding the app", which is the half both agree on.
    ArmedDelivery.NOT_LINKED, ArmedDelivery.LINK_WITHOUT_SENSOR -> BarColors.Amber
}

/** Uppercase, letter-spaced section caption ("LAST SET", "UP NEXT · SET 4 OF 5"). */
@Composable
fun SectionCaption(text: String, color: Color = BarColors.Sub, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}
