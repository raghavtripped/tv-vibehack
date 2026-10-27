package io.cleanyourtv.phoneremote;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One full-screen page that sets a phone up as this TV's remote: the steps, the name the TV goes by on the
 * network, and QR codes for the Google TV app on Android and iPhone. The QR codes are bundled images, so the
 * page needs no permissions and no network. The pairing code itself is shown by the TV's own remote service
 * when a phone asks to pair.
 */
public class PhoneRemoteActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(56), dp(36), dp(56), dp(36));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1F3D, 0xFF07080D}));

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.addView(text("Use your phone as the remote", 30, 0xFFFFFFFF, true));
        steps.addView(text("Anyone on the home Wi-Fi. No Google account needed.", 16, 0xFF9FB0D6, false));
        View gap = new View(this);
        steps.addView(gap, new LinearLayout.LayoutParams(1, dp(16)));
        steps.addView(step("1", "Join the home Wi-Fi (not the guest network or mobile data)."));
        steps.addView(step("2", "Scan a code on the right to install the Google TV app."));
        steps.addView(step("3", "In the app, tap Connect TV (Android) or TVs nearby (iPhone), then pick "
                + "“" + tvName() + "”."));
        steps.addView(step("4", "A 6-digit code appears on this TV. Type it on your phone and tap Pair."));
        TextView tip = text("iPhone: if the TV doesn't show up, allow it in Settings → Privacy & Security "
                + "→ Local Network → Google TV.", 13, 0xFF7F8DB0, false);
        tip.setPadding(0, dp(14), dp(24), 0);
        steps.addView(tip);
        root.addView(steps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(qrCard(R.drawable.qr_android, "Android"));
        root.addView(qrCard(R.drawable.qr_iphone, "iPhone"));
        setContentView(root);
    }

    /** The name phones see in their list of TVs: the TV's device name, which its remote service advertises. */
    private String tvName() {
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
    }

    private View step(String number, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), dp(24), dp(6));
        TextView n = text(number, 15, 0xFF0B0F17, true);
        n.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF9DB4FF);
        n.setBackground(dot);
        row.addView(n, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = text(body, 17, 0xFFE8ECF6, false);
        t.setPadding(dp(14), dp(2), 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View qrCard(int qr, String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        ImageView image = new ImageView(this);
        image.setImageResource(qr);
        image.setContentDescription(label + " QR code for the Google TV app");
        card.addView(image, new LinearLayout.LayoutParams(dp(156), dp(156)));
        TextView name = text(label, 17, 0xFF0B0F17, true);
        name.setPadding(0, dp(8), 0, 0);
        card.addView(name);
        card.addView(text("Google TV app", 12, 0xFF5A6380, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(16));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif-light", Typeface.NORMAL));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
