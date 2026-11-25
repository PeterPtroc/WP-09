package org.thermal.wp09;

import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.math.BigDecimal;

import static org.thermal.wp09.InstrumentUi.*;

final class TemperatureControls {
    interface Apply { void range(float low, float high); }

    static void show(Context c, float low, float high, Apply apply) {
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        LinearLayout inputs = row(c);
        panel.addView(inputs);
        EditText a = input(inputs, "低温提醒", low);
        EditText b = input(inputs, "高温提醒", high);
        AlertDialog dialog = new MaterialAlertDialogBuilder(c).setTitle("高低温提醒 · °C")
                .setView(panel).setNegativeButton("取消", null)
                .setNeutralButton("全部关闭", (d, w) -> apply.range(Float.NaN, Float.NaN))
                .setPositiveButton("应用", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                float left = read(a), right = read(b);
                if (left >= right) { b.setError("上限须大于下限"); return; }
                apply.range(left, right);
                dialog.dismiss();
            } catch (NumberFormatException e) { b.setError("请输入有效温度"); }
        }));
        dialog.show();
    }

    private static EditText input(LinearLayout row, String title, float value) {
        Context c = row.getContext();
        LinearLayout group = new LinearLayout(c);
        group.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        group.setOrientation(LinearLayout.VERTICAL);
        row.addView(group, new LinearLayout.LayoutParams(0, -2, 1));
        group.addView(text(c, title, 12, MUTED));
        EditText e = new EditText(c);
        e.setSingleLine();
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setHint("关闭");
        e.setContentDescription(title);
        if (Float.isFinite(value)) e.setText(editValue(value));
        e.setSelectAllOnFocus(true);
        group.addView(e);
        return e;
    }

    private static String editValue(float f) {
        return BigDecimal.valueOf(f).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static float read(EditText e) {
        String s = e.getText().toString().trim();
        if (s.isEmpty()) return Float.NaN;
        float f = Float.parseFloat(s);
        if (!Float.isFinite(f)) throw new NumberFormatException();
        return f;
    }
}
