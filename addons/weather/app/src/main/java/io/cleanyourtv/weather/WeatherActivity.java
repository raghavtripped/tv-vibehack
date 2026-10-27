package io.cleanyourtv.weather;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One full-screen page: current conditions and the next seven days for one city, from Open-Meteo
 * (free, no account or key). It fetches only while the page is on screen - on open, then hourly - and
 * keeps the last answer so it can show something offline. No background service, nothing else stored.
 */
public class WeatherActivity extends Activity {

    private static final long REFRESH_MS = 60 * 60 * 1000L;
    private static final String PREFS = "weather";
    private static final String KEY_JSON = "json";
    private static final String KEY_AT = "at";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::fetch;

    private LinearLayout root;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(64), dp(48), dp(64), dp(40));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B1A33, 0xFF050608}));
        setContentView(root);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cached = prefs.getString(KEY_JSON, null);
        if (cached != null) {
            render(cached, prefs.getLong(KEY_AT, 0));
        } else {
            showMessage("Loading the forecast for " + BuildConfig.CITY + "…");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        long at = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_AT, 0);
        long age = System.currentTimeMillis() - at;
        if (age >= REFRESH_MS / 2) fetch();
        else handler.postDelayed(refresh, REFRESH_MS - age);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void fetch() {
        handler.removeCallbacks(refresh);
        new Thread(() -> {
            String body = null;
            try {
                body = download(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                                + "weather_code,wind_speed_10m"
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                                + "precipitation_probability_max&timezone=auto&forecast_days=7",
                        BuildConfig.LATITUDE, BuildConfig.LONGITUDE));
                new JSONObject(body); // only keep answers that parse
            } catch (Exception e) {
                body = null;
            }
            final String result = body;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (result != null) {
                    long now = System.currentTimeMillis();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_JSON, result).putLong(KEY_AT, now).apply();
                    render(result, now);
                } else if (status != null) {
                    status.setText("Couldn't update - showing the last forecast");
                } else {
                    showMessage("Couldn't reach the weather service. Check the TV's internet connection.");
                }
                handler.postDelayed(refresh, REFRESH_MS);
            });
        }).start();
    }

    private static String download(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void showMessage(String text) {
        root.removeAllViews();
        status = null;
        root.setGravity(Gravity.CENTER);
        root.addView(text(text, 28, 0xFFB8C7DC, false));
    }

    private void render(String json, long fetchedAt) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            root.removeAllViews();
            root.setGravity(Gravity.NO_GRAVITY);

            root.addView(text(BuildConfig.CITY.toUpperCase(Locale.ROOT), 20, 0xFF8FA6C4, false));

            LinearLayout now = new LinearLayout(this);
            now.setOrientation(LinearLayout.HORIZONTAL);
            now.setGravity(Gravity.CENTER_VERTICAL);
            now.addView(text(Math.round(cur.getDouble("temperature_2m")) + "°", 120, Color.WHITE, true));
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setPadding(dp(32), 0, 0, 0);
            int code = cur.getInt("weather_code");
            side.addView(text(icon(code) + "  " + describe(code), 36, Color.WHITE, false));
            side.addView(text(String.format(Locale.US, "Feels like %d°  ·  Humidity %d%%  ·  Wind %d km/h",
                    Math.round(cur.getDouble("apparent_temperature")),
                    cur.getInt("relative_humidity_2m"),
                    Math.round(cur.getDouble("wind_speed_10m"))), 22, 0xFFB8C7DC, false));
            now.addView(side);
            root.addView(now);

            View gap = new View(this);
            root.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));

            LinearLayout week = new LinearLayout(this);
            week.setOrientation(LinearLayout.HORIZONTAL);
            JSONArray days = daily.getJSONArray("time");
            JSONArray codes = daily.getJSONArray("weather_code");
            JSONArray hi = daily.getJSONArray("temperature_2m_max");
            JSONArray lo = daily.getJSONArray("temperature_2m_min");
            JSONArray rain = daily.optJSONArray("precipitation_probability_max");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 0; i < days.length(); i++) {
                LinearLayout day = new LinearLayout(this);
                day.setOrientation(LinearLayout.VERTICAL);
                day.setGravity(Gravity.CENTER_HORIZONTAL);
                day.setPadding(dp(8), dp(18), dp(8), dp(18));
                GradientDrawable card = new GradientDrawable();
                card.setColor(0x1AFFFFFF);
                card.setCornerRadius(dp(20));
                day.setBackground(card);
                Date date = in.parse(days.getString(i));
                day.addView(text(i == 0 ? "Today" : out.format(date), 22, 0xFFB8C7DC, false));
                day.addView(text(icon(codes.getInt(i)), 40, Color.WHITE, false));
                day.addView(text(Math.round(hi.getDouble(i)) + "°  " + Math.round(lo.getDouble(i)) + "°",
                        24, Color.WHITE, false));
                if (rain != null && !rain.isNull(i)) {
                    day.addView(text("☂ " + rain.getInt(i) + "%", 18, 0xFF7FB2F0, false));
                }
                for (int c = 0; c < day.getChildCount(); c++) {
                    ((TextView) day.getChildAt(c)).setGravity(Gravity.CENTER_HORIZONTAL);
                    day.getChildAt(c).setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(8), 0, dp(8), 0);
                week.addView(day, lp);
            }
            root.addView(week);

            status = text("Updated " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(fetchedAt))
                    + "  ·  Open-Meteo", 16, 0xFF5F7390, false);
            status.setPadding(dp(8), dp(20), 0, 0);
            root.addView(status);
        } catch (Exception e) {
            showMessage("The forecast could not be read.");
        }
    }

    private TextView text(String s, int sp, int color, boolean thin) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(thin ? "sans-serif-thin" : "sans-serif-light", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code <= 48) return "Fog";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Rain showers";
        if (code <= 86) return "Snow showers";
        return "Thunderstorm";
    }

    private static String icon(int code) {
        if (code == 0) return "☀";
        if (code <= 2) return "⛅";
        if (code == 3) return "☁";
        if (code <= 48) return "🌫";
        if (code <= 67 || (code >= 80 && code <= 82)) return "🌧";
        if (code <= 86) return "❄";
        return "⛈";
    }
}
