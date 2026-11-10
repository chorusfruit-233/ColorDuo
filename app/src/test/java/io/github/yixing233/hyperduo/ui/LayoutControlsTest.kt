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

    @Test fun overallScaleControlAllowsThreeHundredPercent() {
        val s = settings()
        val a = TrioAppearance.of(s)
        val row = geometrySliders().single { it.titleRes(a) == R.string.glyph_scale_title }
        assertEquals(60, row.min)
        assertEquals(300, row.maxFor(a))
        assertEquals(100, s.glyphScale)
    }

    @Test fun overallScaleUpdatePreservesLayoutAndTypography() {
        val original = settings().apply {
            ringZones = Prefs.decodeLayout("4,3|1,5|7|")
            valueSize = 40
            showBolt = false
        }
        val update = original.copy().apply { glyphScale = 250 }
        assertTrue(original.applyKeyFrom(update, Prefs.KEY_GLYPH_SCALE))
        assertEquals(250, original.glyphScale)
        assertTrue(original.ringZones.contentDeepEquals(update.ringZones))
        assertEquals(40, original.valueSize)
        assertFalse(original.showBolt)
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
        val linked = slidersForEditor(TrioSettingsPart.Structure, TrioAppearance.of(s))
        assertEquals(6, linked.count { it.scope == SliderScope.SplitLeft })
        assertFalse(linked.any { it.scope == SliderScope.SplitRight })
        s.splitLink = 0
        val independent = slidersForEditor(TrioSettingsPart.Structure, TrioAppearance.of(s))
        assertEquals(6, independent.count { it.scope == SliderScope.SplitRight })
        s.trioStyle = Prefs.STYLE_CAPSULE
        assertTrue(slidersForEditor(TrioSettingsPart.Structure, TrioAppearance.of(s))
            .all { it.scope == SliderScope.CapsuleStructure })
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

    @Test fun signalOpacityHasOneIndependentControlForEverySignalArrangement() {
        for (style in listOf(Prefs.STYLE_RING, Prefs.STYLE_CAPSULE)) {
            for (paired in listOf(false, true)) {
                val placed = TrioZones.withPiece(settings(style), 0, TrioZones.Part.SIM_1)
                val s = TrioZones.withSignalArrangement(placed, paired).apply {
                    trackAlpha = 53
                    signalTrackAlpha = 180
                }
                for (outside in listOf(false, true)) {
                    s.signalMode = if (outside) Prefs.SIGNAL_OUT_RING else Prefs.SIGNAL_IN_RING
                    s.stackedSignal = outside
                    val a = TrioAppearance.of(s)
                    val row = slidersForEditor(TrioSettingsPart.Mobile, a)
                        .single { it.titleRes(a) == R.string.signal_track_alpha_title }
                    assertTrue("style=$style paired=$paired outside=$outside", row.enabled(a))
                    assertEquals(180, row.read(s))
                    assertEquals(0, row.min)
                    assertEquals(255, row.maxFor(a))
                    assertFalse(slidersForEditor(TrioSettingsPart.Battery, a).contains(row))
                    assertEquals(53, slidersForEditor(TrioSettingsPart.Battery, a)
                        .single { it.titleRes(a) == R.string.track_alpha_title }.read(s))
                    if (outside) {
                        s.stackedSignal = false
                        assertFalse(row.enabled(TrioAppearance.of(s)))
                    }
                }
            }
        }
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

    @Test fun batteryAndBoltEditorsHaveIndependentColorRows() {
        val battery = roleColorsForEditor(TrioSettingsPart.Battery)
        val bolt = roleColorsForEditor(TrioSettingsPart.Bolt)
        assertEquals(6, battery.size)
        assertEquals(4, bolt.size)
        assertTrue(battery.toSet().intersect(bolt.toSet()).isEmpty())
        assertTrue(roleColorsForEditor(TrioSettingsPart.Appearance).isEmpty())
        assertEquals(listOf(RoleColor.BoltCharging, RoleColor.BoltQuickCharging,
            RoleColor.BoltFlashCharging, RoleColor.BoltSuperCharging), bolt)
    }

    @Test fun colorPickersReadTheSelectedComponentAndTheme() {
        val s = settings().apply {
            flashChargingOnDark = 0xffa12233.toInt()
            flashChargingOnLight = 0xffb23344.toInt()
            boltFlashChargingOnDark = 0xff1122aa.toInt()
            boltFlashChargingOnLight = 0xff2233bb.toInt()
        }
        assertEquals(s.flashChargingOnDark, colorFor(s, RoleColor.FlashCharging, true))
        assertEquals(s.flashChargingOnLight, colorFor(s, RoleColor.FlashCharging, false))
        assertEquals(s.boltFlashChargingOnDark, colorFor(s, RoleColor.BoltFlashCharging, true))
        assertEquals(s.boltFlashChargingOnLight, colorFor(s, RoleColor.BoltFlashCharging, false))
    }

    @Test fun hdVisibilityHasOneIndependentSwitchEvenWhenNetworkTypeIsOff() {
        for (style in listOf(Prefs.STYLE_RING, Prefs.STYLE_CAPSULE)) {
            val s = settings(style).apply { mobileTypeMode = Prefs.MOBILE_TYPE_OFF }
            assertEquals(1, contentControlsFor(TrioSettingsPart.Type, s)
                .count { it == ContentControl.HideHd })
            s.hideHd = true
            assertEquals(1, contentControlsFor(TrioSettingsPart.Type, s)
                .count { it == ContentControl.HideHd })
        }
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
    @Test fun canonicalOwnersAndCachedSliders() {
        assertSame(geometrySliders(), geometrySliders())
        assertTrue(contentControlsFor(TrioSettingsPart.Appearance, settings()).isEmpty())
        assertTrue(contentControlsFor(TrioSettingsPart.Battery, settings()).contains(ContentControl.PausedGray))
        assertEquals(TrioSettingsPart.Wifi, TrioSettingsPart.of(TrioZones.Part.TYPE))
        assertFalse(partsForPage(settings()).contains(TrioSettingsPart.Type))
    }
    @Test fun advancedSectionsOwnAllTheirControlsWithoutLeakingIntoBasicRows() {
        val s = settings().apply { splitMode = 1; splitLink = 0 }
        var a = TrioAppearance.of(s)
        val structure = slidersForEditor(TrioSettingsPart.Structure, a)
        assertEquals(listOf(AdvancedGroup.LeftArc, AdvancedGroup.RightArc),
            advancedGroupsFor(structure, contentControlsFor(TrioSettingsPart.Structure,s), a))
        s.splitLink = 1; a = TrioAppearance.of(s)
        assertEquals(listOf(AdvancedGroup.LeftArc), advancedGroupsFor(
            slidersForEditor(TrioSettingsPart.Structure,a), emptyList(), a))
        val paired = TrioZones.withPiece(settings(),TrioZones.Zone.CENTRE.ordinal,TrioZones.Part.SIM_PAIR)
        a = TrioAppearance.of(paired)
        val controls = contentControlsFor(TrioSettingsPart.Mobile,paired)
        val rows = slidersForEditor(TrioSettingsPart.Mobile,a)
        assertEquals(listOf(AdvancedGroup.Position), advancedGroupsFor(rows,controls,a))
        for (control in listOf(ContentControl.PairMirror,ContentControl.PairReverse,ContentControl.PairSingle)) {
            assertTrue(controls.contains(control))
            assertEquals(AdvancedGroup.Position, advancedGroup(control))
        }
        assertTrue(rows.filter { isAdvancedSlider(it,a) }.all { advancedGroup(it,a) in listOf(AdvancedGroup.Position) })
        val capsule = settings(Prefs.STYLE_CAPSULE); a = TrioAppearance.of(capsule)
        assertEquals(listOf(AdvancedGroup.Capsule), advancedGroupsFor(
            slidersForEditor(TrioSettingsPart.Structure,a), emptyList(),a))
        val network = TrioZones.withPiece(settings(),TrioZones.Zone.CENTRE.ordinal,TrioZones.Part.TYPE)
        a = TrioAppearance.of(network)
        assertEquals(listOf(AdvancedGroup.Position), advancedGroupsFor(
            slidersForEditor(TrioSettingsPart.Type,a), contentControlsFor(TrioSettingsPart.Type,network),a))
        val independent = settings(); a = TrioAppearance.of(independent)
        assertEquals(listOf(AdvancedGroup.Position), advancedGroupsFor(slidersForEditor(TrioSettingsPart.Mobile,a),
            contentControlsFor(TrioSettingsPart.Mobile,independent),a))
    }
    @Test fun componentOffsetsAndExistingProtocolOffsetsAreAdvancedOnly() {
        val a = TrioAppearance.of(settings())
        val titles = listOf(R.string.wifi_dx_title,R.string.wifi_dy_title,R.string.mobile_dx_title,R.string.mobile_dy_title,R.string.sim1_dx_title,R.string.sim1_dy_title,
            R.string.sim2_dx_title,R.string.sim2_dy_title,R.string.value_dx_title,R.string.value_dy_title,R.string.bolt_dx_title,R.string.bolt_dy_title,R.string.type_dx_title,R.string.type_dy_title,
            R.string.clock_dx_title,R.string.clock_dy_title,R.string.battery_dx_title,R.string.battery_dy_title,R.string.perf_dx_title,R.string.perf_dy_title,
            R.string.wifi_standard_dx_title,R.string.wifi_standard_dy_title)
        for (title in titles) {
            val row=geometrySliders().single { it.titleRes(a)==title }
            assertTrue(isAdvancedSlider(row,a))
            assertEquals(AdvancedGroup.Position,advancedGroup(row,a))
            assertEquals(-40,row.min);assertEquals(40,row.max)
        }
        for (part in listOf(TrioSettingsPart.Wifi,TrioSettingsPart.Mobile,TrioSettingsPart.Value,
            TrioSettingsPart.Bolt,TrioSettingsPart.Type,TrioSettingsPart.Clock,
            TrioSettingsPart.Battery,TrioSettingsPart.Readouts)) {
            assertTrue(slidersForEditor(part,a).any { advancedGroup(it,a)==AdvancedGroup.Position })
        }
    }

    @Test fun wifiAdvancedParametersAreGroupedAndOrderedByObject() {
        val a=TrioAppearance.of(settings())
        val rows=slidersForEditor(TrioSettingsPart.Wifi,a)+slidersForEditor(TrioSettingsPart.Type,a)
        assertTrue(rows.all { isAdvancedSlider(it,a)&&advancedGroup(it,a)==AdvancedGroup.Position })
        val sections=rows.sortedBy { parameterOrder(it,a) }.map { parameterHeading(it,a) }.distinct()
        assertEquals(listOf(R.string.parameters_wifi,R.string.parameters_wifi_standard,R.string.parameters_network_type),sections)
        val wifi=rows.filter { parameterHeading(it,a)==R.string.parameters_wifi }.sortedBy { parameterOrder(it,a) }
        assertEquals(listOf(R.string.wifi_scale_title,R.string.arc_stroke_title,R.string.wifi_dx_title,R.string.wifi_dy_title),wifi.map { it.titleRes(a) })
    }

    @Test fun signalPositionControlsFollowTheSelectedArrangement() {
        val paired=TrioZones.withPiece(settings(),TrioZones.Zone.CENTRE.ordinal,TrioZones.Part.SIM_PAIR)
        val a=TrioAppearance.of(paired)
        val rows=slidersForEditor(TrioSettingsPart.Mobile,a)
        assertFalse(rows.any { it.titleRes(a) in listOf(R.string.mobile_dx_title,R.string.mobile_dy_title,R.string.sim1_dx_title,R.string.sim1_dy_title,R.string.sim2_dx_title,R.string.sim2_dy_title) })
        assertTrue(rows.any { it.titleRes(a)==R.string.pair_y_title })
        val flat=TrioAppearance.of(paired.copy().apply { pairShape=1 })
        assertFalse(slidersForEditor(TrioSettingsPart.Mobile,flat).any { it.titleRes(flat)==R.string.pair_bend_title })
    }

    @Test fun wifiStandardSizeAndWeightAreIndependentFromTheWholeIcon() {
        val s = settings().apply { wifiScale = 150; wifiStandardScale = 225; wifiStandardWeight = 900 }
        val a = TrioAppearance.of(s)
        val rows = slidersForEditor(TrioSettingsPart.Wifi, a)
        val size = rows.single { it.titleRes(a) == R.string.wifi_standard_scale_title }
        val weight = rows.single { it.titleRes(a) == R.string.wifi_standard_weight_title }
        assertEquals(50, size.min); assertEquals(250, size.max)
        assertEquals(100, weight.min); assertEquals(1000, weight.max)
        assertEquals(225, size.read(s)); assertEquals(900, weight.read(s))
        assertEquals(150, s.wifiScale)
        assertTrue(size.enabled(a) && weight.enabled(a))
    }
}
