package org.thermal.wp09;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;

final class InstrumentUi {
    static final int BG = 0xff151515, PANEL = 0xff222222, TILE = 0xff343434;
    static final int TEXT = 0xffeeeeee, MUTED = 0xffbbbbbb, ACCENT = 0xffdedede;

    static void landscape(AppCompatActivity activity, View root) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(activity.getWindow(), root);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.systemBars());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
    }

    static int dp(Context c, float value) { return Math.round(c.getResources().getDisplayMetrics().density * value); }

    static boolean largeText(Context c) { return c.getResources().getConfiguration().fontScale >= 1.4f; }

    static TextView text(Context c, String label, int size, int color) {
        TextView v = new TextView(c);
        v.setText(label);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setBaselineAligned(false);
        return group;
    }

    static MaterialButton action(LinearLayout row, String label, int icon, Runnable action) {
        Context c = row.getContext();
        MaterialButton b = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setLetterSpacing(0);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(dp(c, 2), 0, dp(c, 2), 0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setCornerRadius(0);
        b.setStrokeWidth(0);
        if (icon != 0 && (label.isEmpty() || !largeText(c))) b.setIconResource(icon);
        b.setIconSize(dp(c, 16));
        b.setIconPadding(dp(c, 4));
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        int[][] states = {{-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}};
        ColorStateList tint = new ColorStateList(states, new int[]{0xff898989, BG, TEXT});
        b.setTextColor(tint);
        b.setIconTint(tint);
        b.setBackgroundTintList(new ColorStateList(states, new int[]{0xff2b2b2b, ACCENT, TILE}));
        if (Build.VERSION.SDK_INT >= 26) b.setTooltipText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        row.addView(b, params);
        return b;
    }

    static void label(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
}
