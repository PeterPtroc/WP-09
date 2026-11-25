package org.thermal.wp09;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

import static org.thermal.wp09.InstrumentUi.*;

/** Shared live and album measurement controls, all selections are in sensor coordinates. */
final class InspectionPanel extends LinearLayout {
    private final ThermalView thermal;
    private final TextView name, value, stats;
    private final MaterialButton[] tools = new MaterialButton[3];
    private final TemperaturePlot plot;
    private final MaterialButton chart;
    private final MaterialButton hotMarker, coldMarker;
    private final SharedPreferences settings;
    private static final String SHOW_HOT = "showHotMarker", SHOW_COLD = "showColdMarker";
    private final SharedPreferences.OnSharedPreferenceChangeListener markerChange = (prefs, key) -> {
        if (SHOW_HOT.equals(key) || SHOW_COLD.equals(key) || key == null) loadMarkers();
    };
    private long time;
    private boolean hasTime;
    private boolean chartOpen;

    InspectionPanel(Context c, ThermalView thermal) {
        super(c);
        this.thermal = thermal;
        settings = c.getSharedPreferences("wp09", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        LinearLayout reading = row(c);
        reading.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        name = text(c, "选点", 12, MUTED);
        value = text(c, "—", 23, TEXT);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        reading.addView(name);
        reading.addView(value, new LayoutParams(0, -2, 1));
        addView(reading);
        LinearLayout markers = row(c);
        addView(markers);
        hotMarker = marker(markers, "最高温");
        coldMarker = marker(markers, "最低温");
        stats = text(c, "", 11, MUTED);
        stats.setPadding(dp(c, 6), 0, 0, dp(c, 4));
        addView(stats);
        LinearLayout modes = row(c);
        addView(modes);
        String[] labels = {"点", "线", "面"};
        int[] icons = {R.drawable.ic_point, R.drawable.ic_line, R.drawable.ic_area};
        int i = 0;
        for (Measurement.Tool next : Measurement.Tool.values()) {
            tools[i] = action(modes, labels[i], icons[i], () -> thermal.select(next));
            i++;
        }
        plot = new TemperaturePlot(c, thermal.measurement);
        chart = action(modes, "", R.drawable.ic_chart, () -> {
            chartOpen = !chartOpen;
            updateChart();
        });
        chart.setContentDescription("温度曲线");
        if (android.os.Build.VERSION.SDK_INT >= 26) chart.setTooltipText("温度曲线");
        addView(plot, new LayoutParams(-1, dp(c, 76)));
        thermal.onSpotChanged(() -> {
            reset();
            refresh(time, true);
        });
        reset();
        loadMarkers();
        updateChart();
    }

    private MaterialButton marker(LinearLayout row, String title) {
        MaterialButton button = action(row, title, 0, () -> settings.edit()
                .putBoolean(SHOW_HOT, hotMarker.isChecked())
                .putBoolean(SHOW_COLD, coldMarker.isChecked()).apply());
        button.setCheckable(true);
        button.setSingleLine();
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(title + "标记");
        return button;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        settings.registerOnSharedPreferenceChangeListener(markerChange);
        loadMarkers();
    }

    @Override protected void onDetachedFromWindow() {
        settings.unregisterOnSharedPreferenceChangeListener(markerChange);
        super.onDetachedFromWindow();
    }

    private void loadMarkers() {
        boolean hot = settings.getBoolean(SHOW_HOT, true), cold = settings.getBoolean(SHOW_COLD, true);
        hotMarker.setChecked(hot);
        coldMarker.setChecked(cold);
        hotMarker.setSelected(hot);
        coldMarker.setSelected(cold);
        thermal.setExtremaVisible(hot, cold);
        refresh(time, false);
    }

    private void labelMarker(MaterialButton button, String title, ThermalCapture capture, boolean hot) {
        String caption = title;
        if (button.isChecked() && capture != null) {
            ThermalFrame f = capture.frame;
            float temp = capture.calibration.apply(hot ? f.reported.maximum : f.reported.minimum);
            if (Float.isFinite(temp)) caption += (f.isEstimatedAt(hot ? f.reported.hot : f.reported.cold) ? " ≈" : " ")
                    + ThermalView.number(temp) + "°";
        }
        label(button, caption);
    }

    void reset() {
        plot.reset(thermal.measurement.tool == Measurement.Tool.LINE);
        hasTime = false;
    }

    void refresh(long sampleTimeMs, boolean append) {
        time = sampleTimeMs;
        ThermalCapture c = thermal.capture();
        Measurement m = thermal.measurement;
        m.evaluate(c == null ? null : c.frame, c == null ? Calibration.DEFAULT : c.calibration);
        boolean point = m.tool == Measurement.Tool.POINT;
        boolean estimated = c != null && !c.frame.thermalOutput && c.frame.hasTemperature();
        boolean centerPair = point && c != null && !c.frame.hasPixelTemperatures() && m.start == ThermalFrame.CENTER
                && m.start != c.frame.hot && m.start != c.frame.cold;
        label(name, point ? (centerPair ? "中心温度" : "选点") : m.tool == Measurement.Tool.LINE ? "线均温" : "区域均温");
        label(value, point ? thermal.spotDisplay() : m.count == 0 ? "—"
                : (estimated ? "≈" : "") + ThermalView.number(m.mean) + " °C");
        labelMarker(hotMarker, "最高温", c, true);
        labelMarker(coldMarker, "最低温", c, false);
        String summary = "";
        if (!point) {
            summary = "选区   ↓ " + ThermalView.number(m.minimum) + "°   ↑ " + ThermalView.number(m.maximum) + "°";
        } else if (c != null && c.frame.firmware != null && m.count == 0 && c.frame.measurable(m.start)
                && Float.isFinite(c.frame.readings[m.start])) {
            summary = "估算 " + ThermalView.number(c.calibration.apply(c.frame.readings[m.start])) + "°，超出画面温度范围";
        } else if (c != null && c.frame.firmware != null) {
            summary = "中心温度 " + ThermalView.number(c.calibration.apply(c.frame.reported.center)) + "°";
        } else if (c != null && c.frame.thermalOutput) {
            summary = "画面均温 " + ThermalView.number(c.calibration.apply(c.frame.average)) + "°";
        }
        label(stats, summary);
        stats.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < tools.length; i++) tools[i].setSelected(m.tool.ordinal() == i);
        if (append && c != null && c.frame.hasTemperature()) {
            plot.add(time, m.mean);
            hasTime = true;
        } else if (hasTime && (c == null || !c.frame.hasTemperature())) reset();
        if (chartOpen) plot.invalidate();
    }

    private void updateChart() {
        plot.setVisibility(chartOpen ? View.VISIBLE : View.GONE);
        chart.setSelected(chartOpen);
    }
}
