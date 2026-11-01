/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo;

import androidx.constraintlayout.core.widgets.ConstraintWidget;
import androidx.constraintlayout.core.widgets.ConstraintWidgetContainer;
import org.junit.Test;
import static androidx.constraintlayout.core.widgets.ConstraintAnchor.Type.*;
import static androidx.constraintlayout.core.widgets.ConstraintWidget.DimensionBehaviour.*;
import static org.junit.Assert.assertEquals;

/** Exercise the AndroidX solver used by a wrap-content row with a 0dp battery child. */
public final class ConstraintHeaderMeasurementTest {
    @Test public void solverMinimumControlsTheRowAndCanShrinkBackToNative() {
        ConstraintWidgetContainer row=new ConstraintWidgetContainer();
        row.setHorizontalDimensionBehaviour(FIXED);
        row.setWidth(1160);
        row.setVerticalDimensionBehaviour(WRAP_CONTENT);
        ConstraintWidget meter=new ConstraintWidget(144,120);
        meter.setVerticalDimensionBehaviour(MATCH_CONSTRAINT);
        meter.connect(TOP,row,TOP);
        meter.connect(BOTTOM,row,BOTTOM);
        meter.connect(LEFT,row,LEFT);
        row.add(meter);

        // A desired 120px child is still laid out at the native solver minimum.
        // The header's direct AT_MOST measure never consults LayoutParams.height.
        measure(row,meter,80);
        measure(row,meter,120);
        measure(row,meter,240);
        measure(row,meter,120);
        measure(row,meter,80);
    }

    private static void measure(ConstraintWidgetContainer row,ConstraintWidget meter,int minimum) {
        row.setMinHeight(minimum);
        row.setHeight(0);
        row.layout();
        assertEquals(minimum,row.getHeight());
        assertEquals(minimum,meter.getHeight());
    }
}
