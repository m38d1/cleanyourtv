package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow or a bundled photograph. Every minute the clock drifts to a new spot and the glow shifts colour, so
 * nothing burns in.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};
    /** clock_photos: the photographs bundled in res/raw, from Unsplash (free licence). */
    private static final int[] PHOTOS = {
            R.raw.screensaver_01, R.raw.screensaver_02, R.raw.screensaver_03, R.raw.screensaver_04,
            R.raw.screensaver_05, R.raw.screensaver_06, R.raw.screensaver_07, R.raw.screensaver_08,
            R.raw.screensaver_09, R.raw.screensaver_10};
    private static final long PHOTO_FADE_MS = 1200L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final ImageView ambient;
    /** clock_photos: the layer the next picture fades in on, over the one showing. */
    private final ImageView ambientTop;
    /** shamsi_date: the Solar Hijri line above the Gregorian date; gone unless the theme shows it. */
    private final TextView shamsi;
    /** weather_line: the reading under the date; gone until there is one. */
    private final TextView weatherText;
    private final WeatherLine weatherLine = new WeatherLine();
    private String weatherUrl;
    private String weatherPlace;
    private final GradientDrawable glow;
    /** The soft darkening behind the clock and date, so they stay readable over a bright picture. */
    private final View scrimView;
    private final GradientDrawable scrim;
    /** clock_photos: the slow zoom running on the picture showing. */
    private ValueAnimator photoZoom;
    /** clock_photos: one bundled picture at a time, its darkening, its turn, and whether it is up. */
    private boolean photos;
    private int photoShade;
    private long photoMs;
    private long photoShownMs;
    private int[] photoOrder;
    private int photoAt;
    private boolean photoShown;
    private boolean photoOnTop;
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            advanceWallpaper();
            advancePhoto();
            refreshShamsi();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        ambient = root.findViewById(R.id.dreamAmbient);
        ambientTop = root.findViewById(R.id.dreamAmbientTop);
        shamsi = root.findViewById(R.id.dreamShamsi);
        weatherText = root.findViewById(R.id.dreamWeather);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        // clock_photos: the clock stays in the lower band and a full-width gradient rises from the bottom edge, as
        // Google's ambient screensaver does: the picture keeps its colour above, and the text sits on the dark.
        scrimView = root.findViewById(R.id.dreamScrim);
        scrim = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xC0000000, 0x90000000, 0x40000000, 0x00000000});
        scrimView.setBackground(scrim);
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        if (weatherUrl != null) {
            weatherLine.start(weatherUrl, weatherPlace, this::showWeather);
        }
        if (photos) {
            if (photoShown) {
                // A fade cut short by a stop(): finish on the newer picture, not half-way between two.
                ImageView current = photoOnTop ? ambientTop : ambient;
                ImageView previous = photoOnTop ? ambient : ambientTop;
                current.setAlpha(1f);
                if (previous.getDrawable() != null) {
                    previous.setAlpha(0f);
                    previous.setImageDrawable(null);
                }
                zoomPhoto(current);
            } else {
                loadPhoto();
            }
        } else if (wallpaperFile != null && !wallpaperShown) {
            loadWallpaper();
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        content.animate().cancel();
        ambient.animate().cancel();
        ambientTop.animate().cancel();
        weatherLine.stop();
        if (photoZoom != null) {
            photoZoom.cancel();
            photoZoom = null;
        }
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put the picture Home is showing behind the clock, darkened by clock_wallpaper_dim, in
     * place of the glow, and carry a collection's rotation on while the screensaver is up. Decoded off the main
     * thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read,
     * the glow stays.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (shamsi != null) {
            shamsi.setVisibility(cfg.shamsiDate ? View.VISIBLE : View.GONE);
            refreshShamsi();
        }
        Resources res = ctx.getResources();
        if (res.getBoolean(R.bool.weather_line)) {
            weatherUrl = WeatherLine.url(res);
            weatherPlace = res.getString(R.string.weather_place);
            // Home saves every reading, so the line is there the moment the screensaver comes up, even
            // before this scene's own fetch returns (or with no network at all).
            String saved = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("line", "");
            if (!saved.isEmpty()) showWeather(saved);
        }
        if (res.getBoolean(R.bool.clock_photos)) {
            photos = true;
            photoShade = (Math.max(0, Math.min(90, res.getInteger(R.integer.clock_photos_dim))) * 255 / 100) << 24;
            photoMs = Math.max(1, res.getInteger(R.integer.clock_photos_minutes)) * 60_000L;
            photoOrder = shuffledPhotos();
            wallpaperRes = res;
            wallpaperShown = true;   // stops the glow's colour cycle: the pictures cover it
            return;
        }
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
            }
            return;
        }
        // What Home plays, and Config puts the single wallpaper in it when a theme has no collection.
        // Reading cfg.wallpaper first held the screensaver on wallpaper.jpg while Home rotated.
        if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            // Start on the picture Home is showing. WallpaperPlayer records it under the same
            // preferences and the screensaver runs in the same process, so this is just a read.
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            // Home only rotates while it is visible, so the screensaver carries the rotation on
            // itself; without this it holds one picture for as long as it is up.
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** clock_photos: the next picture, a shuffled full pass through the set before any repeats. */
    private int[] shuffledPhotos() {
        int[] order = new int[PHOTOS.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        photoAt = random.nextInt(order.length);
        return order;
    }

    private void advancePhoto() {
        if (!photos) return;
        if (!photoShown) {
            loadPhoto();   // nothing could be decoded last time; the next minute tries the next picture
            return;
        }
        photoShownMs += MOVE_MS;
        if (photoShownMs < photoMs) return;
        photoShownMs = 0;
        loadPhoto();
    }

    private void loadPhoto() {
        if (photoOrder == null || photoOrder.length == 0) return;
        int id = PHOTOS[photoOrder[photoAt]];
        photoAt = (photoAt + 1) % photoOrder.length;
        int shade = photoShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(res, id, dm.widthPixels, dm.heightPixels);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration) {
                    bmp.recycle();   // stopped or replaced meanwhile: this picture was never attached
                    return;
                }
                showPhoto(res, bmp, shade);
            });
        }, "clock-photo").start();
    }

    /** Fade the new picture in over the old one; a picture is opaque, so the glow goes underneath once. */
    private void showPhoto(Resources res, Bitmap bmp, int shade) {
        ImageView into = photoOnTop ? ambient : ambientTop;
        ImageView from = photoOnTop ? ambientTop : ambient;
        into.setImageDrawable(new LayerDrawable(new Drawable[]{
                new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
        into.setAlpha(0f);
        into.animate().alpha(1f).setDuration(PHOTO_FADE_MS).start();
        from.animate().alpha(0f).setDuration(PHOTO_FADE_MS)
                .withEndAction(() -> {
                    from.setImageDrawable(null);
                    from.setScaleX(1f);
                    from.setScaleY(1f);
                }).start();
        ambient.setBackground(null);
        photoOnTop = !photoOnTop;
        photoShown = true;
        zoomPhoto(into);
    }

    /** A slow zoom over the picture's turn, so a still photograph does not sit frozen on a TV for minutes. */
    private void zoomPhoto(ImageView view) {
        if (photoZoom != null) photoZoom.cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        photoZoom = ValueAnimator.ofFloat(1f, 1.06f);
        photoZoom.setDuration(photoMs + PHOTO_FADE_MS);
        photoZoom.setInterpolator(new LinearInterpolator());
        photoZoom.addUpdateListener(a -> {
            float scale = (float) a.getAnimatedValue();
            view.setScaleX(scale);
            view.setScaleY(scale);
        });
        photoZoom.start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** clock_photos: the same decode for a picture bundled in res/raw. */
    private static Bitmap decode(Resources res, int id, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(res, id, options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeResource(res, id, options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;   // a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    /** The Shamsi date, refreshed by the minute tick so it is right after midnight too. */
    private void refreshShamsi() {
        if (shamsi != null && shamsi.getVisibility() == View.VISIBLE) {
            shamsi.setText(Shamsi.today(shamsi.getResources()));
        }
    }

    private void showWeather(String line) {
        weatherText.setText(line);
        if (weatherText.getVisibility() != View.VISIBLE) weatherText.setVisibility(View.VISIBLE);
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        // With photographs the clock stays in the lower band, on the bottom gradient that keeps it readable; it
        // still drifts across the whole width, so it never burns into one spot.
        float y = maxY * (photos ? 0.6f + 0.3f * random.nextFloat() : 0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        if (wallpaperShown) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
