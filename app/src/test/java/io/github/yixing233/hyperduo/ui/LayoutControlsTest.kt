package io.github.yixing233.hyperduo.ui

import io.github.yixing233.hyperduo.Prefs
import io.github.yixing233.hyperduo.R
import io.github.yixing233.hyperduo.TrioAppearance
import io.github.yixing233.hyperduo.TrioSettings
import io.github.yixing233.hyperduo.TrioZones
import org.junit.Assert.*
import org.junit.Test

/** Execute the same row selectors and slider specs that the Compose sheets use. */
class LayoutControlsTest {
    private fun settings(style: Int = Prefs.STYLE_RING) = TrioSettings.defaults().apply {
        trioStyle = style
        enabled = true
        signalMode = Prefs.SIGNAL_IN_RING
    }

    @Test fun capsuleStructureContainsOnlyItsFiveEditableParameters() {
        val a = TrioAppearance.of(settings(Prefs.STYLE_CAPSULE))
        val rows = capsuleContainerSliders()
        val expected = setOf(R.string.capsule_length_title, R.string.capsule_nudge_left_dx_title,
            R.string.capsule_nudge_left_dy_title, R.string.capsule_nudge_right_dx_title,
            R.string.capsule_nudge_right_dy_title)
        assertEquals(5, rows.size)
        assertEquals(expected, rows.map { it.titleRes(a) }.toSet())
        assertTrue(rows.all { it.enabled(a) })
    }

    @Test fun splitParametersFollowTheActiveStructureAndLinkMode() {
        val s = settings().apply { splitMode = 1 }
        val linked = slidersForEditor(TrioSettingsPart.Appearance, TrioAppearance.of(s))
        assertEquals(6, linked.count { it.scope == SliderScope.SplitLeft })
        assertFalse(linked.any { it.scope == SliderScope.SplitRight })
        s.splitLink = 0
        val independent = slidersForEditor(TrioSettingsPart.Appearance, TrioAppearance.of(s))
        assertEquals(6, independent.count { it.scope == SliderScope.SplitRight })
        s.trioStyle = Prefs.STYLE_CAPSULE
        assertTrue(slidersForEditor(TrioSettingsPart.Appearance, TrioAppearance.of(s))
            .all { it.scope == SliderScope.Shared })
        assertFalse(contentControlsFor(TrioSettingsPart.Appearance, s).contains(ContentControl.SplitMode))
    }

    @Test fun independentSignalsHavePositionsWithoutDuplicateVisibilitySwitches() {
        val s = settings()
        val rows = contentControlsFor(TrioSettingsPart.Mobile, s)
        assertEquals(1, rows.count { it == ContentControl.SignalLayout })
        assertTrue(rows.containsAll(listOf(ContentControl.Sim1, ContentControl.Sim2)))
        assertFalse(rows.any { it in listOf(ContentControl.SimPair, ContentControl.PairSim1,
            ContentControl.PairSim2, ContentControl.PairMirror, ContentControl.Stacked) })
        assertFalse(slidersForEditor(TrioSettingsPart.Mobile, TrioAppearance.of(s))
            .any { it.scope == SliderScope.PairedSignal })
    }

    @Test fun pairedSignalsHaveOnePositionAndNoIndependentPositionRows() {
        for (style in listOf(Prefs.STYLE_RING, Prefs.STYLE_CAPSULE)) {
            val s = TrioZones.withSignalArrangement(settings(style), true)
            val rows = contentControlsFor(TrioSettingsPart.Mobile, s)
            assertEquals(1, rows.count { it == ContentControl.SimPair })
            assertTrue(rows.indexOf(ContentControl.SignalLayout) < rows.indexOf(ContentControl.SimPair))
            assertTrue(rows.indexOf(ContentControl.SimPair) < rows.indexOf(ContentControl.PairMirror))
            assertTrue(rows.containsAll(listOf(ContentControl.PairSim1, ContentControl.PairSim2,
                ContentControl.PairMirror)))
            assertFalse(rows.any { it == ContentControl.Sim1 || it == ContentControl.Sim2 || it == ContentControl.Stacked })
            assertEquals(5, slidersForEditor(TrioSettingsPart.Mobile, TrioAppearance.of(s))
                .count { it.scope == SliderScope.PairedSignal })
        }
    }

    @Test fun hidingBothCardsDoesNotHideTheControlsNeededToRestoreThem() {
        val s = TrioZones.withSignalArrangement(settings(), true)
        s.showSim1 = false
        s.showSim2 = false
        val rows = contentControlsFor(TrioSettingsPart.Mobile, s)
        assertTrue(rows.containsAll(listOf(ContentControl.SimPair, ContentControl.PairSim1, ContentControl.PairSim2)))
        assertFalse(TrioAppearance.of(s).drawsSimPair())
        val unchanged = TrioZones.withSignalArrangement(s, true)
        assertFalse(unchanged.showSim1 || unchanged.showSim2)
    }

    @Test fun outsideSignalUsesOnlyItsOwnControls() {
        val s = TrioZones.withSignalArrangement(settings(), true)
        s.signalMode = Prefs.SIGNAL_OUT_RING
        s.stackedSignal = true
        val rows = contentControlsFor(TrioSettingsPart.Mobile, s)
        assertTrue(rows.containsAll(listOf(ContentControl.Stacked, ContentControl.DataSim)))
        assertFalse(rows.any { it == ContentControl.SignalLayout || it == ContentControl.SimPair || it == ContentControl.Sim1 })
    }

    @Test fun switchingArrangementAtomicallyReplacesTheSignalPlacement() {
        var s = TrioZones.withPiece(settings(), 0, TrioZones.Part.SIM_1)
        s = TrioZones.withPiece(s, 2, TrioZones.Part.SIM_2)
        s = TrioZones.withSignalArrangement(s, true)
        assertEquals(listOf(Prefs.PART_SIM_PAIR), s.ringZones.flatMap { it.toList() }.filter { Prefs.isSignalRow(it) })
        s = TrioZones.withSignalArrangement(s, false)
        assertEquals(listOf(Prefs.PART_SIM_1, Prefs.PART_SIM_2),
            s.ringZones.flatMap { it.toList() }.filter { Prefs.isSignalRow(it) })
        assertTrue(s.showSim1 && s.showSim2)
    }

    @Test fun pairedPlacementReplacesTheLegacyAggregateAsWell() {
        val old = settings().apply { ringZones = Prefs.decodeLayout("2|||") }
        val next = TrioZones.withSignalArrangement(old, true)
        assertEquals(listOf(Prefs.PART_SIM_PAIR), next.ringZones.flatMap { it.toList() }.filter { Prefs.isSignalRow(it) })
    }

    @Test fun boltHasOneMenuWithHiddenNativeAndTheActualStylePositions() {
        val ring = settings()
        assertEquals(listOf(ContentControl.Bolt), contentControlsFor(TrioSettingsPart.Bolt, ring))
        assertEquals(listOf(R.string.bolt_hidden, R.string.bolt_native,
            R.string.drawing_hint_top, R.string.drawing_hint_centre, R.string.drawing_hint_bottom),
            boltPositionOptions(ring))
        val capsule = settings(Prefs.STYLE_CAPSULE)
        assertEquals(listOf(ContentControl.Bolt), contentControlsFor(TrioSettingsPart.Bolt, capsule))
        assertEquals(listOf(R.string.bolt_hidden, R.string.bolt_native,
            R.string.drawing_hint_top, R.string.drawing_hint_left,
            R.string.drawing_hint_right, R.string.drawing_hint_bottom), boltPositionOptions(capsule))
    }

    @Test fun boltMenuReadsLegacyHiddenStateAndRestoresPlacementInOneChoice() {
        val old = TrioZones.withPiece(settings(Prefs.STYLE_CAPSULE),
            TrioZones.Zone.LEFT.ordinal, TrioZones.Part.BOLT).apply { showBolt = false }
        assertEquals(TrioZones.BOLT_POSITION_HIDDEN, TrioZones.boltPosition(old))
        val placed = TrioZones.withBoltPosition(old, 3)
        assertEquals(R.string.drawing_hint_left, boltPositionOptions(placed)[TrioZones.boltPosition(placed)])
        assertTrue(TrioAppearance.of(placed).drawsBolt())
        assertFalse(old.showBolt)
        val native = TrioZones.withBoltPosition(placed, TrioZones.BOLT_POSITION_NATIVE)
        assertEquals(R.string.bolt_native, boltPositionOptions(native)[TrioZones.boltPosition(native)])
        assertFalse(TrioAppearance.of(native).hidesNativeBolt())
    }
}
