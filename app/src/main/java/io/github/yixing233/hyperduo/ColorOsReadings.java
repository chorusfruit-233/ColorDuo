/*
 * Copyright (C) 2026 ColorDuo contributors
 * Based on HyperDuo by yixing233 and contributors.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package io.github.yixing233.hyperduo;

import android.content.Context;
import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Mounts upstream's out-of-ring readings in ColorOS's native system-icons row. */
final class ColorOsReadings extends ViewGroup {
    private final TrioState state;
    private final View glyphHost;
    private final Signal signal;
    private final TrioHooks.PerfLabel perf;
    private final TrioHooks.OutTypeLabel type;
    private final int[] levels = new int[2];
    private TrioAppearance appearance;
    private boolean radioNotice;
    private boolean radioNoticeVisible;

    boolean hasVisibleRadioNotice() { return radioNotice && radioNoticeVisible; }

    ColorOsReadings(Context context, TrioState state, View glyphHost) {
        super(context);
        this.state = state;
        this.glyphHost = glyphHost;
        signal = new Signal(context);
        perf = new TrioHooks.PerfLabel(context);
        type = new TrioHooks.OutTypeLabel(context);
        setupText(perf, false);
        setupText(type, true);
        addView(signal);
        addView(perf);
        addView(type);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private static void setupText(TextView view, boolean singleLine) {
        view.setGravity(Gravity.CENTER);
        if (singleLine) view.setSingleLine(true);
        else view.setMaxLines(2);
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(TrioGeometry.VALUE_LETTER_SPACING);
        view.setVisibility(GONE);
    }

    void update(TrioAppearance appearance) {
        this.appearance = appearance;
        // These are the same selection and drawing functions used by the upstream preview
        // and hooks, including a SIM in slot 2 alone and the data-SIM-only option.
        TrioRenderer.outSignalLevels(appearance.dataSimOnly, state.mobileLevel,
                state.slotLevels, state.dataSlot, levels);
        if (levels[0] < 0) levels[0] = 0;
        boolean registering = state.mobileService == DuoPolicy.Radio.REGISTERING;
        signal.setVisibility(appearance.stackedOut() && !registering ? VISIBLE : GONE);
        signal.invalidate();

        String performance = TrioHooks.perfText(appearance);
        if (!performance.contentEquals(perf.getText())) perf.setText(performance);
        perf.setVisibility(performance.isEmpty() ? GONE : VISIBLE);
        float size = TrioHooks.perfTextSize(performance, this, glyphHost);
        if (perf.getTextSize() != size) perf.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        int generation = TrioRenderer.fontGeneration();
        if (perf.weight != appearance.perfTypeWeight() || perf.fontGeneration != generation) {
            TrioRenderer.styleTextView(perf, appearance.perfTypeWeight());
            perf.weight = appearance.perfTypeWeight();
            perf.fontGeneration = generation;
        }
        perf.setLineSpacing(0f, TrioHooks.perfLineSpacing(perf, performance, this));

        radioNotice = appearance.foldsMobile() && DuoPolicy.hasNotice(state.mobileService);
        String network = registering ? "" : radioNotice ? ColorOsLabels.radioNotice(getContext(), state.mobileService)
                : appearance.visibleMobileType(state.mobileType, state.wifiPresent);
        if (!network.contentEquals(type.getText()) || type.suffixScale != appearance.typeSuffixScale) {
            TrioHooks.applyOutTypeText(type, network, appearance.typeSuffixScale);
        }
        type.setVisibility((radioNotice || appearance.typeOutOfRing) && !network.isEmpty() ? VISIBLE : GONE);
        size = appearance.outTypeSize * getResources().getDisplayMetrics().density;
        if (type.getTextSize() != size) type.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        if (type.appliedWeight != appearance.typeWeight || type.fontGeneration != generation) {
            TrioRenderer.styleTextView(type, appearance.typeWeight);
            type.appliedWeight = appearance.typeWeight;
            type.fontGeneration = generation;
        }
        perf.setTextColor(state.foreground());
        type.setTextColor(state.foreground());
        setVisibility(signal.getVisibility() == VISIBLE || perf.getVisibility() == VISIBLE
                || type.getVisibility() == VISIBLE ? VISIBLE : GONE);
        requestLayout();
    }

    private View[] orderedViews() {
        int[] order = appearance.outOrder;
        View[] views = new View[order.length];
        for (int i = 0; i < order.length; i++) {
            views[i] = switch (order[i]) {
                case Prefs.OUT_SLOT_SIGNAL -> signal;
                case Prefs.OUT_SLOT_READOUTS -> perf;
                case Prefs.OUT_SLOT_TYPE -> type;
                default -> null;
            };
        }
        return views;
    }

    private OutRingLayout.Plan plan(int width, int height) {
        View[] views = orderedViews();
        int[] widths = new int[views.length];
        int[] heights = new int[views.length];
        int[] gaps = new int[views.length];
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int[] margins = TrioHooks.outLabelMargins(this);
        int inward = rtl ? margins[0] : margins[1];
        for (int i = 0; i < views.length; i++) {
            View child = views[i];
            if (child != null && child.getVisibility() == VISIBLE) {
                widths[i] = child == perf
                        ? Math.max(perf.getMeasuredWidth(), TrioHooks.perfWidth(perf, perf.getText().toString()))
                        : child.getMeasuredWidth();
                heights[i] = child.getMeasuredHeight();
            }
            gaps[i] = TrioHooks.outGapFor(appearance.outOrder[i], this, inward);
        }
        int last = views.length - 1;
        int outward = last >= 0 && widths[last] > 0 && appearance.outOrder[last] == Prefs.OUT_SLOT_TYPE
                ? (rtl ? margins[1] : margins[0]) : 0;
        // ColorOS measures this as a sibling of the weighted native icon container. The
        // parent reserves its width; MIUI instead reserves that width with icon padding.
        int[] dropOrder = TrioHooks.outRingDropOrder(appearance);
        if (radioNotice) {
            // An explicit network warning takes priority over optional power and bars.
            int typeIndex = -1;
            for (int i = 0; i < views.length; i++) if (views[i] == type) typeIndex = i;
            int[] priority = new int[dropOrder.length];
            int at = 0;
            for (int index : dropOrder) if (index != typeIndex) priority[at++] = index;
            if (at < priority.length) priority[at] = typeIndex;
            dropOrder = priority;
        }
        return OutRingLayout.solve(width, 0, 0, 0, height, widths, heights, gaps,
                outward, dropOrder);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (appearance == null) {
            setMeasuredDimension(0, 0);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int signalHeight = TrioRenderer.outSignalHeight(appearance.outSignalSize, density);
        signal.measure(MeasureSpec.makeMeasureSpec(TrioRenderer.outSignalWidth(signalHeight), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(signalHeight, MeasureSpec.EXACTLY));
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        perf.measure(unspecified, unspecified);
        type.measure(unspecified, unspecified);
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Math.max(signalHeight, Math.max(perf.getMeasuredHeight(), type.getMeasuredHeight()))
                : MeasureSpec.getSize(heightMeasureSpec);
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE / 4 : MeasureSpec.getSize(widthMeasureSpec);
        OutRingLayout.Plan plan = plan(width, height);
        setMeasuredDimension(resolveSize(plan.reserve, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (appearance == null) return;
        View[] views = orderedViews();
        OutRingLayout.Plan plan = plan(getWidth(), getHeight());
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        boolean noticeVisible = false;
        for (int i = 0; i < views.length; i++) {
            View child = views[i];
            if (child == null || child.getVisibility() != VISIBLE) continue;
            if (plan.widths[i] <= 0) {
                child.setAlpha(0f);
                continue;
            }
            int w = plan.widths[i];
            int h = child.getMeasuredHeight();
            int x = Math.round(rtl ? getWidth() - plan.near[i] : plan.near[i] - w);
            int y = (getHeight() - h) / 2;
            child.setAlpha(plan.scale > 0f ? 1f : 0f);
            child.setPivotX(rtl ? 0f : w);
            child.setPivotY(h / 2f);
            child.setScaleX(plan.scale);
            child.setScaleY(plan.scale);
            child.layout(x, y, x + w, y + h);
            if (child == type && radioNotice && plan.scale > 0f) noticeVisible = true;
        }
        if (radioNoticeVisible != noticeVisible) {
            radioNoticeVisible = noticeVisible;
            ColorOsHooks.invalidateHosts();
        }
    }

    private final class Signal extends View {
        Signal(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            TrioRenderer.drawOutSignal(canvas, getWidth(), getHeight(), levels[0], levels[1],
                    state.foreground(), appearance);
        }
    }
}
