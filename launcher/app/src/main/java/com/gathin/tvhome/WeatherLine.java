package com.gathin.tvhome;

import android.content.res.Resources;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Live weather for the line under the clock: one Open-Meteo reading (no key, no account, no location
 * permission) while Home is resumed, refreshed every 30 minutes. A failed read is retried after half a
 * minute, so the line appears soon after a reboot once the network is up; a bad payload never touches
 * the screen.
 */
final class WeatherLine {

    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 30_000L;
    private static final long TIMEOUT_MS = 8000;
    private static final int MAX_BODY = 64 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private volatile Handler bg;
    private String lastLine;

    /** The reading Home and the screensaver share: weather_place/lat/lon from a theme's config. */
    static String url(Resources res) {
        return String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                        + "&current=temperature_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&hourly=precipitation_probability&forecast_hours=6&timezone=auto",
                res.getString(R.string.weather_lat), res.getString(R.string.weather_lon));
    }

    /** Shows the cached line immediately, then reads. Safe to call on every resume. */
    void start(String url, String place, Consumer<String> sink) {
        if (lastLine != null) sink.accept(lastLine);
        if (thread != null) return;
        thread = new HandlerThread("weather");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                String line = read(url, place);
                if (line != null) {
                    lastLine = line;
                    main.post(() -> {
                        if (bg == h) sink.accept(line);
                    });
                }
                if (bg == h) h.postDelayed(this, line != null ? REFRESH_MS : RETRY_MS);
            }
        });
    }

    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    private static String read(String url, String place) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout((int) TIMEOUT_MS);
            c.setReadTimeout((int) TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "CleanYourTV");
            if (c.getResponseCode() != 200) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0 && body.length() < MAX_BODY) body.append(buf, 0, n);
            }
            JSONObject json = new JSONObject(body.toString());
            JSONObject current = json.optJSONObject("current");
            if (current == null || !current.has("temperature_2m")) return null;
            int temp = Math.round((float) current.getDouble("temperature_2m"));
            StringBuilder line = new StringBuilder(place)
                    .append(" \u00B7 ").append(temp).append("\u00B0 ")
                    .append(condition(current.optInt("weather_code", -1)));
            JSONObject daily = json.optJSONObject("daily");
            if (daily != null) {
                JSONArray low = daily.optJSONArray("temperature_2m_min");
                JSONArray high = daily.optJSONArray("temperature_2m_max");
                if (low != null && high != null && low.length() > 0 && high.length() > 0) {
                    line.append(" \u00B7 ")
                            .append(Math.round((float) low.optDouble(0)))
                            .append("\u2013")
                            .append(Math.round((float) high.optDouble(0))).append("\u00B0");
                }
            }
            JSONObject hourly = json.optJSONObject("hourly");
            if (hourly != null) {
                JSONArray rain = hourly.optJSONArray("precipitation_probability");
                if (rain != null) {
                    for (int i = 0; i < rain.length(); i++) {
                        if (rain.optDouble(i, 0) >= 50) {
                            line.append(" \u00B7 rain soon");
                            break;
                        }
                    }
                }
            }
            return line.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather code to a couple of words, as Open-Meteo returns it. */
    private static String condition(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: return "Snow";
            case 77: return "Snow grains";
            case 80: case 81: case 82: return "Rain showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }
}
