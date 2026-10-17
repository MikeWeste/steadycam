package com.example.steadycam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements CameraEngine.Listener, SurfaceHolder.Callback, SensorEventListener {
    private static final int CAMERA_REQUEST = 17, AUDIO_REQUEST = 18;
    private static final int YELLOW = Color.rgb(255, 214, 10), RED = Color.rgb(255, 59, 48);
    private static final int GRAY = Color.rgb(153, 153, 160), WHITE = Color.rgb(246, 246, 248);
    private static final int DIM = Color.argb(170, 255, 255, 255);
    private static final int G_BOLT = 0, G_DOTS = 1, G_FLIP = 2, G_MIC = 3, G_PAUSE = 4;
    private static final float[] ZOOM_PRESETS = {.6f, 1f, 2f, 5f};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private CameraEngine engine;
    private CameraEngine.Info info;
    private CameraEngine.State state = CameraEngine.State.CLOSED;
    private SharedPreferences prefs;
    private FrameLayout root;
    private FocusFrame viewport;
    private SurfaceView surface;
    private CameraOverlay overlay;
    private View blink;
    private LinearLayout top, controls, zoomRow, modeRow, chipsRow;
    private Button stabilization, resolution, fps, photo, video;
    private Glyph flash, microphone, settings, flip, pause;
    private TextView status, telemetry, clock;
    private final ArrayList<Button> zoomButtons = new ArrayList<>();
    private final ArrayList<Float> zoomValues = new ArrayList<>();
    private ImageView gallery;
    private Shutter shutter;
    private ScaleGestureDetector scale;
    private GestureDetector gestures;
    private SensorManager sensors;
    private Sensor accelerometer;
    private PowerManager power;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private Uri lastUri;
    private boolean resumed = false, destroyed = false, wantsAudio = true, pendingAudioStart = false, counting = false;
    private boolean scaling = false, permissionRequested = false, evDragStarted = false;
    private int countdownSeconds = 0, countdownLeft = 0, insetLeft = 0, insetTop = 0, insetRight = 0, insetBottom = 0, lastEvSent = 0;
    private float currentZoom = 1, evFloat = 0;
    private String lastAnalysis = "";
    private long lastLowFpsWarning = 0;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        prefs = getSharedPreferences("ui_v2", MODE_PRIVATE);
        wantsAudio = prefs.getBoolean("audio", true);
        countdownSeconds = prefs.getInt("timer", 0);
        String last = prefs.getString("lastUri", null);
        if (last != null) lastUri = Uri.parse(last);
        engine = new CameraEngine(this, this);
        buildUi();
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        power = (PowerManager) getSystemService(POWER_SERVICE);
        thermalListener = value -> {
            if (value >= PowerManager.THERMAL_STATUS_CRITICAL) engine.thermalStop();
            else if (value >= PowerManager.THERMAL_STATUS_SEVERE) showMessage("Телефон нагрелся: частота кадров может снижаться");
        };
        power.addThermalStatusListener(getMainExecutor(), thermalListener);
        if (lastUri != null) loadThumbnail(lastUri);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (accelerometer != null) sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        engine.orientation(displayDegrees());
        engine.setAudio(wantsAudio && has(Manifest.permission.RECORD_AUDIO));
        if (has(Manifest.permission.CAMERA)) engine.resume();
        else if (!permissionRequested) {
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
        ui.post(ticker);
    }

    @Override protected void onPause() {
        resumed = false;
        cancelCountdown();
        ui.removeCallbacks(ticker);
        sensors.unregisterListener(this);
        engine.suspend();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        power.removeThermalStatusListener(thermalListener);
        thumbnails.shutdownNow();
        engine.destroy();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        cancelCountdown();
        engine.orientation(displayDegrees());
        root.post(this::layoutCamera);
        overlay.focusX = -1;
    }

    private boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == CAMERA_REQUEST) {
            if (has(Manifest.permission.CAMERA)) { if (resumed) engine.resume(); }
            else { showMessage("Для съёмки нужен доступ к камере. Нажми кнопку съёмки, чтобы открыть настройки."); updateUi(); }
        }
        if (code == AUDIO_REQUEST) {
            engine.setAudio(wantsAudio && has(Manifest.permission.RECORD_AUDIO));
            if (!has(Manifest.permission.RECORD_AUDIO)) showMessage("Микрофон недоступен. Можно записывать без звука.");
            boolean start = pendingAudioStart;
            pendingAudioStart = false;
            updateUi();
            if (start && resumed && state == CameraEngine.State.READY) {
                if (has(Manifest.permission.RECORD_AUDIO)) engine.startRecording(displayDegrees());
                else new AlertDialog.Builder(this).setTitle("Записать без звука?").setMessage("Доступ к микрофону не выдан.")
                        .setPositiveButton("Без звука", (d, w) -> engine.startRecording(displayDegrees()))
                        .setNegativeButton("Отмена", null).show();
            }
        }
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        viewport = new FocusFrame();
        viewport.setBackgroundColor(Color.BLACK);
        root.addView(viewport);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        viewport.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        overlay = new CameraOverlay(this);
        overlay.grid = prefs.getBoolean("grid", false);
        overlay.level = prefs.getBoolean("level", true);
        viewport.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        blink = new View(this);
        blink.setBackgroundColor(Color.BLACK);
        blink.setAlpha(0f);
        viewport.addView(blink, new FrameLayout.LayoutParams(-1, -1));

        scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; return info != null && !counting; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (info != null) {
                    currentZoom = CameraPolicy.clamp(currentZoom * d.getScaleFactor(), info.minZoom, info.maxZoom);
                    applyZoom();
                }
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { scaling = false; }
        });
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { evDragStarted = false; return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!scaling && !counting && info != null) startFocus(e.getX(), e.getY(), false);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (!scaling && !counting && info != null) {
                    startFocus(e.getX(), e.getY(), true);
                    viewport.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling || counting || info == null || !overlay.focusVisible() || info.evMax <= info.evMin) return false;
                if (!evDragStarted && Math.abs(dy) < Math.abs(dx)) return false;
                evDragStarted = true;
                float range = info.evMax - info.evMin;
                evFloat = CameraPolicy.clamp(evFloat + dy * range / dp(240), info.evMin, info.evMax);
                int v = Math.round(evFloat);
                if (v != lastEvSent) {
                    lastEvSent = v;
                    engine.exposure(v);
                    viewport.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                overlay.setEv((evFloat - info.evMin) / range, String.format(Locale.US, "%+.1f", v * info.evStep));
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || scaling || counting || info == null || evDragStarted || recordingBusy()) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(70) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                boolean toVideo = dx > 0;
                if (toVideo != info.video) {
                    cancelCountdown();
                    viewport.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    engine.mode(toVideo);
                }
                return true;
            }
        });
        viewport.setOnTouchListener((v, e) -> {
            scale.onTouchEvent(e);
            if (!scale.isInProgress()) gestures.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) { viewport.fromTouch = true; v.performClick(); viewport.fromTouch = false; }
            return true;
        });
        viewport.setContentDescription("Предпросмотр. Касание — фокус, удержание — фиксация AF/AE, свайп вверх-вниз — яркость, два пальца — масштаб, свайп вбок — режим");

        zoomRow = row();
        zoomRow.setGravity(Gravity.CENTER);
        root.addView(zoomRow);

        // Top bar: flash — timer / frame-rate — more
        top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), 0, dp(14), 0);
        root.addView(top);
        flash = glyph(G_BOLT, "Вспышка или фонарь");
        settings = glyph(G_DOTS, "Настройки");
        top.addView(flash, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout center = vertical();
        center.setGravity(Gravity.CENTER);
        clock = label("", 13, WHITE);
        clock.setGravity(Gravity.CENTER);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        clock.setPadding(dp(11), dp(3), dp(11), dp(3));
        clock.setVisibility(View.GONE);
        center.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        telemetry = label("", 10, GRAY);
        telemetry.setGravity(Gravity.CENTER);
        center.addView(telemetry, new LinearLayout.LayoutParams(-2, -2));
        top.addView(center, new LinearLayout.LayoutParams(0, -1, 1));
        top.addView(settings, new LinearLayout.LayoutParams(dp(42), dp(42)));

        // Bottom controls
        controls = vertical();
        controls.setPadding(dp(14), 0, dp(14), dp(8));
        root.addView(controls);
        chipsRow = row();
        chipsRow.setGravity(Gravity.CENTER);
        resolution = chip("FHD");
        fps = chip("30");
        stabilization = chip("СТАБ");
        microphone = glyph(G_MIC, "Микрофон");
        chipsRow.addView(resolution, chipParams());
        chipsRow.addView(fps, chipParams());
        chipsRow.addView(stabilization, chipParams());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(34), dp(34));
        mp.leftMargin = dp(4);
        chipsRow.addView(microphone, mp);
        controls.addView(chipsRow, new LinearLayout.LayoutParams(-1, dp(40)));

        modeRow = row();
        modeRow.setGravity(Gravity.CENTER);
        video = modeButton("ВИДЕО");
        photo = modeButton("ФОТО");
        modeRow.addView(video, new LinearLayout.LayoutParams(dp(98), dp(36)));
        modeRow.addView(photo, new LinearLayout.LayoutParams(dp(98), dp(36)));
        controls.addView(modeRow, new LinearLayout.LayoutParams(-1, dp(38)));

        FrameLayout shutterRow = new FrameLayout(this);
        controls.addView(shutterRow, new LinearLayout.LayoutParams(-1, dp(92)));
        gallery = new ImageView(this);
        gallery.setScaleType(ImageView.ScaleType.CENTER_CROP);
        gallery.setBackground(round(Color.rgb(32, 32, 35), 12));
        gallery.setClipToOutline(true);
        gallery.setContentDescription("Открыть последний снимок или видео");
        gallery.setImageResource(android.R.drawable.ic_menu_gallery);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START | Gravity.CENTER_VERTICAL);
        gl.leftMargin = dp(22);
        shutterRow.addView(gallery, gl);
        shutter = new Shutter();
        shutterRow.addView(shutter, new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER));
        flip = glyph(G_FLIP, "Переключить основную и фронтальную камеры");
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        fl.rightMargin = dp(24);
        shutterRow.addView(flip, fl);
        pause = glyph(G_PAUSE, "Пауза или продолжение записи");
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        pl.rightMargin = dp(24);
        shutterRow.addView(pause, pl);
        pause.setVisibility(View.GONE);

        status = label("", 11, GRAY);
        status.setMaxLines(1);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(status, new LinearLayout.LayoutParams(-1, dp(22)));

        flash.setOnClickListener(v -> {
            if (info != null) engine.flash(info.video ? (info.flashMode == 2 ? 0 : 2) : (info.flashMode + 1) % 3);
        });
        stabilization.setOnClickListener(v -> chooseStabilization());
        microphone.setOnClickListener(v -> {
            wantsAudio = !wantsAudio;
            prefs.edit().putBoolean("audio", wantsAudio).apply();
            if (wantsAudio && !has(Manifest.permission.RECORD_AUDIO)) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_REQUEST);
            engine.setAudio(wantsAudio && has(Manifest.permission.RECORD_AUDIO));
            updateUi();
        });
        settings.setOnClickListener(v -> showSettings());
        resolution.setOnClickListener(v -> chooseResolution());
        fps.setOnClickListener(v -> chooseFps());
        photo.setOnClickListener(v -> { cancelCountdown(); tick(); engine.mode(false); });
        video.setOnClickListener(v -> { cancelCountdown(); tick(); engine.mode(true); });
        flip.setOnClickListener(v -> { cancelCountdown(); engine.flip(); });
        shutter.setOnClickListener(v -> shutter());
        pause.setOnClickListener(v -> engine.pause());
        gallery.setOnClickListener(v -> openGallery());

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getSystemWindowInsetBottom();
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                insetLeft = Math.max(insetLeft, cutout.getSafeInsetLeft());
                insetTop = Math.max(insetTop, cutout.getSafeInsetTop());
                insetRight = Math.max(insetRight, cutout.getSafeInsetRight());
                insetBottom = Math.max(insetBottom, cutout.getSafeInsetBottom());
            }
            root.post(this::layoutCamera);
            return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (r - l != orr - ol || b - t != ob - ot) layoutCamera();
        });
        showMessage("Открываю камеру…");
    }

    private void startFocus(float x, float y, boolean lock) {
        overlay.focus(x, y, lock);
        if (info != null && info.evMax > info.evMin) {
            if (Math.round(evFloat) != 0 && info.evMin <= 0 && info.evMax >= 0) {
                evFloat = 0;
                lastEvSent = 0;
                engine.exposure(0);
            }
            overlay.setEv((evFloat - info.evMin) / (info.evMax - info.evMin), "");
        }
        engine.focus(x / viewport.getWidth(), y / viewport.getHeight(), lock);
    }

    private boolean recordingBusy() {
        return state == CameraEngine.State.PREPARING || state == CameraEngine.State.RECORDING
                || state == CameraEngine.State.PAUSED || state == CameraEngine.State.STOPPING;
    }

    private void layoutCamera() {
        if (destroyed || root.getWidth() == 0) return;
        int w = root.getWidth() - insetLeft - insetRight, h = root.getHeight() - insetTop - insetBottom;
        boolean landscape = w > h;
        int topHeight = dp(52), controlHeight = dp(224), controlWidth = dp(250);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(landscape ? w - controlWidth : w, topHeight);
        tl.leftMargin = insetLeft;
        tl.topMargin = insetTop;
        top.setLayoutParams(tl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(landscape ? controlWidth : w, landscape ? h : Math.min(controlHeight, h - dp(160)));
        cl.leftMargin = insetLeft + (landscape ? w - controlWidth : 0);
        cl.topMargin = insetTop + (landscape ? 0 : h - cl.height);
        controls.setLayoutParams(cl);
        if (landscape) controls.setBackgroundColor(Color.BLACK);
        else controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000, 0xFF000000}));
        boolean immersive = !landscape && info != null && info.video;
        int availW = landscape ? w - controlWidth : w;
        int availH = landscape ? h - topHeight : (immersive ? h - topHeight : h - topHeight - cl.height);
        int pw = info == null ? 1920 : info.previewWidth, ph = info == null ? 1080 : info.previewHeight;
        int rotation = info == null ? 90 : CameraPolicy.previewRotation(info.sensor, displayDegrees(), info.front);
        double aspect = rotation % 180 == 0 ? (double) pw / ph : (double) ph / pw;
        int vw = availW, vh = (int) (vw / aspect);
        if (vh > availH) { vh = Math.max(dp(80), availH); vw = (int) (vh * aspect); }
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(vw, vh);
        vp.leftMargin = insetLeft + (availW - vw) / 2;
        vp.topMargin = insetTop + topHeight + (immersive ? 0 : (availH - vh) / 2);
        viewport.setLayoutParams(vp);
        int zoomBottom = vp.topMargin + vh;
        if (!landscape) zoomBottom = Math.min(zoomBottom, cl.topMargin);
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(vw, dp(46));
        zl.leftMargin = vp.leftMargin;
        zl.topMargin = zoomBottom - dp(54);
        zoomRow.setLayoutParams(zl);
        // SurfaceView applies Camera2's front-camera mirror transform itself.
        surface.setScaleX(1);
    }

    private void updateUi() {
        if (destroyed) return;
        boolean recording = state == CameraEngine.State.RECORDING || state == CameraEngine.State.PAUSED;
        boolean busy = recordingBusy();
        boolean ready = state == CameraEngine.State.READY;
        shutter.enabled = ready || recording || counting || state == CameraEngine.State.CLOSED || !has(Manifest.permission.CAMERA);
        shutter.recording = recording;
        shutter.video = info == null || info.video;
        shutter.preparing = state == CameraEngine.State.PREPARING || state == CameraEngine.State.STOPPING;
        shutter.count = counting ? countdownLeft : 0;
        shutter.invalidate();
        shutter.setContentDescription(counting ? "Отменить таймер" : recording ? "Остановить запись" : info != null && !info.video ? "Сделать фото" : "Начать запись");
        boolean free = !busy && !counting && state != CameraEngine.State.PHOTO;
        for (View v : new View[]{photo, video, flip, flash, stabilization, microphone, resolution, fps, settings}) {
            v.setEnabled(free);
            v.setAlpha(free ? 1f : .4f);
        }
        chipsRow.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        modeRow.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        pause.setVisibility(recording ? View.VISIBLE : View.GONE);
        pause.slash = state == CameraEngine.State.PAUSED;
        pause.invalidate();
        flip.setVisibility(recording ? View.GONE : View.VISIBLE);
        microphone.on = wantsAudio && has(Manifest.permission.RECORD_AUDIO);
        microphone.invalidate();
        if (info == null) return;
        photo.setTextColor(info.video ? DIM : YELLOW);
        photo.setTypeface(info.video ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT_BOLD);
        video.setTextColor(info.video ? YELLOW : DIM);
        video.setTypeface(info.video ? Typeface.DEFAULT_BOLD : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        boolean flashOk = free && info.flash;
        flash.setEnabled(flashOk);
        flash.setAlpha(flashOk ? 1f : .4f);
        flash.tint = info.flashMode > 0 ? YELLOW : WHITE;
        flash.slash = info.flashMode == 0;
        flash.badge = !info.video && info.flashMode == 1 ? "A" : "";
        flash.invalidate();
        stabilization.setVisibility(info.video ? View.VISIBLE : View.GONE);
        microphone.setVisibility(info.video ? View.VISIBLE : View.GONE);
        stabilization.setText(info.stabilization.equals("AUTO") ? "СТАБ АВТО" : info.stabilization.equals("OFF") ? "СТАБ ВЫКЛ" : "СТАБ " + info.stabilization);
        stabilization.setTextColor(info.stabilization.equals("OFF") ? GRAY : WHITE);
        resolution.setText(info.video ? (info.mode == null ? "—" : info.mode.resolution()) : (info.photoMax ? "MAX" : "БЫСТРО"));
        boolean sixty = info.video && info.mode != null && info.mode.fps == 60;
        fps.setText(info.video ? (info.mode == null ? "—" : (info.mode.range.getLower() < info.mode.fps ? "≈" : "") + info.mode.fps) : (countdownSeconds == 0 ? "Таймер" : "Таймер " + countdownSeconds + " c"));
        fps.setTextColor(sixty || (!info.video && countdownSeconds > 0) ? YELLOW : WHITE);
        telemetry.setVisibility(info.video ? View.VISIBLE : View.GONE);
        rebuildZoom();
        layoutCamera();
    }

    private void rebuildZoom() {
        zoomRow.removeAllViews();
        zoomButtons.clear();
        zoomValues.clear();
        zoomRow.setVisibility(info == null ? View.GONE : View.VISIBLE);
        if (info == null) return;
        for (float z : ZOOM_PRESETS) {
            if (z < info.minZoom - .01f || z > info.maxZoom + .01f) continue;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setStateListAnimator(null);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setContentDescription("Масштаб " + zoomText(z));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.leftMargin = dp(4); lp.rightMargin = dp(4);
            zoomRow.addView(b, lp);
            zoomButtons.add(b);
            zoomValues.add(z);
            b.setOnClickListener(v -> {
                currentZoom = z;
                viewport.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                applyZoom();
            });
        }
        refreshZoomLabels();
    }

    private static String zoomText(float z) {
        String s = Math.abs(z - Math.round(z)) < .05f ? Integer.toString(Math.round(z)) : String.format(Locale.US, "%.1f", z);
        return s.startsWith("0.") ? s.substring(1) : s;
    }

    private void refreshZoomLabels() {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < zoomValues.size(); i++) {
            double distance = Math.abs(Math.log(currentZoom / zoomValues.get(i)));
            if (distance < bestDistance) { bestDistance = distance; best = i; }
        }
        // Selected bubble shows the exact zoom (like iOS: 1,4×); others show the preset.
        for (int i = 0; i < zoomButtons.size(); i++) {
            Button b = zoomButtons.get(i);
            boolean selected = i == best;
            b.setText(selected ? zoomText(currentZoom) + "×" : zoomText(zoomValues.get(i)));
            b.setTextSize(selected ? 13 : 12);
            b.setTextColor(selected ? YELLOW : WHITE);
            b.setBackground(round(selected ? Color.argb(150, 20, 20, 22) : Color.argb(100, 20, 20, 22), 20));
        }
    }

    private void applyZoom() {
        engine.zoom(currentZoom);
        refreshZoomLabels();
    }

    private void chooseResolution() {
        if (info == null) return;
        if (!info.video) {
            engine.highPhoto(!info.photoMax);
            showMessage(info.photoMax ? "Быстрое фото: до 13 МП" : "Максимальный JPEG, который отдаёт Camera2");
            return;
        }
        ArrayList<CameraEngine.Mode> choices = new ArrayList<>();
        for (int height : new int[]{720, 1080, 2160}) {
            CameraEngine.Mode best = null;
            for (CameraEngine.Mode m : info.modes)
                if (m.size.getHeight() == height && (best == null || m.fps == (info.mode == null ? 30 : info.mode.fps))) best = m;
            if (best != null) choices.add(best);
        }
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = choices.get(i).label();
        if (names.length == 0) { diagnostics(); return; }
        new AlertDialog.Builder(this).setTitle("Разрешение видео").setItems(names, (d, w) -> engine.selectMode(choices.get(w).key())).show();
    }

    private void chooseFps() {
        if (info == null) return;
        if (!info.video) {
            String[] labels = {"Без таймера", "3 секунды", "10 секунд"};
            new AlertDialog.Builder(this).setTitle("Таймер фото").setItems(labels, (d, w) -> {
                countdownSeconds = new int[]{0, 3, 10}[w];
                prefs.edit().putInt("timer", countdownSeconds).apply();
                updateUi();
            }).show();
            return;
        }
        boolean sixty = false;
        for (CameraEngine.Mode m : info.modes) if (m.fps == 60 && (info.mode == null || m.size.equals(info.mode.size))) sixty = true;
        new AlertDialog.Builder(this).setTitle("Кадров в секунду").setItems(new String[]{"30 — универсальный", sixty ? "60 — плавное движение" : "60 — недоступно в этом разрешении"}, (d, w) -> {
            int wanted = w == 0 ? 30 : 60;
            for (CameraEngine.Mode m : info.modes)
                if (m.fps == wanted && (info.mode == null || m.size.equals(info.mode.size))) { engine.selectMode(m.key()); return; }
            new AlertDialog.Builder(this).setTitle("Почему нет " + wanted + " FPS?")
                    .setMessage("Для этого разрешения Camera2 или кодировщик не предоставили подходящий режим. Попробуй FHD, основную камеру и H.264. В диагностике указана точная причина для каждого режима.")
                    .setPositiveButton("Диагностика", (dd, ww) -> diagnostics()).setNegativeButton("Закрыть", null).show();
        }).show();
    }

    private void chooseStabilization() {
        if (info == null) return;
        ArrayList<String> values = new ArrayList<>(Arrays.asList("AUTO", "OFF"));
        ArrayList<String> labels = new ArrayList<>(Arrays.asList("Авто: EIS до 30 / OIS при 60", "Выключена"));
        if (info.ois) { values.add("OIS"); labels.add("Оптическая OIS"); }
        if (info.eis) { values.add("EIS"); labels.add("Электронная EIS (возможен предел FPS)"); }
        new AlertDialog.Builder(this).setTitle("Стабилизация видео").setItems(labels.toArray(new String[0]), (d, w) -> engine.stabilization(values.get(w))).show();
    }

    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("SteadyCam 2.1").setItems(new String[]{
                "Сетка: " + (overlay.grid ? "вкл" : "выкл"),
                "Уровень: " + (overlay.level ? "вкл" : "выкл"),
                "Кодек: " + (info != null && info.mime.equals("video/hevc") ? "HEVC" : "H.264"),
                "Выбрать доступную камеру",
                "Диагностика камеры и 60 FPS",
                "О приложении"}, (d, w) -> {
            if (w == 0) { overlay.grid = !overlay.grid; prefs.edit().putBoolean("grid", overlay.grid).apply(); overlay.invalidate(); }
            if (w == 1) { overlay.level = !overlay.level; prefs.edit().putBoolean("level", overlay.level).apply(); overlay.invalidate(); }
            if (w == 2) new AlertDialog.Builder(this).setTitle("Кодек видео").setItems(new String[]{"H.264 — совместимость", "HEVC — меньше размер"}, (dd, i) -> engine.codec(i == 0 ? "video/avc" : "video/hevc")).show();
            if (w == 3 && info != null) new AlertDialog.Builder(this).setTitle("Камеры, доступные Android").setItems(info.names.toArray(new String[0]), (dd, i) -> engine.camera(info.ids.get(i))).show();
            if (w == 4) diagnostics();
            if (w == 5) new AlertDialog.Builder(this).setTitle("SteadyCam 13T · 2.1")
                    .setMessage("Нативная Camera2-камера. Фото JPEG, видео MP4, H.264/HEVC.\n\nСъёмка сохраняется в DCIM/SteadyCam.\n\nКасание — фокус, удержание — AF/AE LOCK, после касания свайп вверх/вниз меняет яркость. Свайп вбок переключает ВИДЕО/ФОТО. Кнопки громкости — затвор.\n\nДоступ к фирменным режимам Xiaomi зависит от прошивки. HDR, ночная многокадровая обработка и кинорежим не имитируются. FPS MP4 измеряется по временным меткам, без поиска повторяющихся изображений.\n\n" + lastAnalysis)
                    .setPositiveButton("Понятно", null).show();
        }).show();
    }

    private void diagnostics() {
        engine.report(text -> {
            if (isFinishing() || destroyed) return;
            TextView t = label(text, 12, WHITE);
            t.setTextIsSelectable(true);
            t.setPadding(dp(16), dp(12), dp(16), dp(12));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(t);
            new AlertDialog.Builder(this).setTitle("Диагностика Camera2").setView(scroll).setPositiveButton("Поделиться", (d, w) -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Отправить диагностику"));
            }).setNegativeButton("Закрыть", null).show();
        });
    }

    // ------------------------------------------------------------------ shooting

    private void tick() { viewport.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }

    private void blinkShutter() {
        blink.animate().cancel();
        blink.setAlpha(0f);
        blink.animate().alpha(.9f).setDuration(40).withEndAction(() -> blink.animate().alpha(0f).setDuration(140).start()).start();
    }

    private void takePhoto() {
        blinkShutter();
        engine.photo(displayDegrees());
    }

    private void shutter() {
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (counting) { cancelCountdown(); return; }
        if (!has(Manifest.permission.CAMERA)) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (state == CameraEngine.State.CLOSED) { showMessage("Повторное открытие камеры…"); engine.resume(); return; }
        if (state == CameraEngine.State.RECORDING || state == CameraEngine.State.PAUSED) { engine.stopRecording(); return; }
        if (state != CameraEngine.State.READY || info == null) return;
        if (info.video) {
            if (info.mode == null) { diagnostics(); return; }
            if (wantsAudio && !has(Manifest.permission.RECORD_AUDIO)) {
                pendingAudioStart = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_REQUEST);
                return;
            }
            engine.setAudio(wantsAudio && has(Manifest.permission.RECORD_AUDIO));
            engine.startRecording(displayDegrees());
        } else if (countdownSeconds > 0) {
            counting = true;
            countdownLeft = countdownSeconds;
            updateUi();
            ui.post(countdown);
        } else takePhoto();
    }

    private final Runnable countdown = new Runnable() {
        public void run() {
            if (!counting || !resumed) return;
            if (countdownLeft <= 0) { counting = false; clock.setVisibility(View.GONE); updateUi(); takePhoto(); return; }
            styleClock(2);
            clock.setText(getString(R.string.countdown, countdownLeft));
            clock.setVisibility(View.VISIBLE);
            shutter.count = countdownLeft;
            shutter.invalidate();
            countdownLeft--;
            ui.postDelayed(this, 1000);
        }
    };

    private void cancelCountdown() {
        counting = false;
        ui.removeCallbacks(countdown);
        if (shutter != null) { shutter.count = 0; shutter.invalidate(); }
        if (clock != null) clock.setVisibility(View.GONE);
        if (info != null) updateUi();
    }

    /** 0 = recording (red), 1 = paused (yellow), 2 = neutral (self-timer). */
    private void styleClock(int kind) {
        clock.setBackground(round(kind == 0 ? RED : kind == 1 ? YELLOW : Color.rgb(52, 52, 56), 7));
        clock.setTextColor(kind == 1 ? Color.BLACK : WHITE);
    }

    private final Runnable ticker = new Runnable() {
        public void run() {
            if (!resumed) return;
            engine.tick();
            boolean rec = state == CameraEngine.State.RECORDING, paused = state == CameraEngine.State.PAUSED;
            if (rec || paused) {
                long seconds = engine.elapsed() / 1000;
                styleClock(paused ? 1 : 0);
                clock.setText(String.format(Locale.US, "%s%02d:%02d", paused ? "ПАУЗА  " : "● ", seconds / 60, seconds % 60));
                clock.setVisibility(View.VISIBLE);
            } else if (!counting) clock.setVisibility(View.GONE);
            ui.postDelayed(this, 250);
        }
    };

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) shutter();
            return true;
        }
        return super.onKeyDown(key, event);
    }

    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(key, event);
    }

    @Override public void onBackPressed() {
        if (counting) { cancelCountdown(); updateUi(); return; }
        if (state == CameraEngine.State.RECORDING || state == CameraEngine.State.PAUSED) { engine.stopRecording(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ engine callbacks

    @Override public void onInfo(CameraEngine.Info value) {
        if (destroyed) return;
        boolean modeChanged = info != null && info.video != value.video;
        info = value;
        if (!scaling) { currentZoom = value.zoom; }
        if (!evDragStarted) { evFloat = value.ev; lastEvSent = value.ev; }
        if (modeChanged) { overlay.focusX = -1; blinkMode(); }
        surface.getHolder().setFixedSize(value.previewWidth, value.previewHeight);
        updateUi();
    }

    private void blinkMode() {
        blink.animate().cancel();
        blink.setAlpha(.55f);
        blink.animate().alpha(0f).setDuration(260).start();
    }

    @Override public void onState(CameraEngine.State value) {
        if (destroyed) return;
        state = value;
        if (value == CameraEngine.State.PREPARING) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        else if (value == CameraEngine.State.READY || value == CameraEngine.State.CLOSED) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        updateUi();
    }

    @Override public void onMessage(String message) { if (!destroyed) showMessage(message); }

    @Override public void onFrame(double value, String stab) {
        if (destroyed || info == null) return;
        telemetry.setText(String.format(Locale.US, "КАМЕРА %.1f  ·  %s", value, stab));
        if (info.video && info.mode != null && info.mode.fps == 60 && value < 50 && state == CameraEngine.State.RECORDING
                && engine.elapsed() > 4000 && SystemClock.elapsedRealtime() - lastLowFpsWarning > 12000) {
            lastLowFpsWarning = SystemClock.elapsedRealtime();
            showMessage("Камера выдаёт меньше 60 к/с. Проверь свет и стабилизацию; MP4 будет измерен после записи.");
        }
    }

    @Override public void onSaved(Uri uri, boolean video, String summary) {
        if (destroyed) return;
        lastUri = uri;
        prefs.edit().putString("lastUri", uri.toString()).apply();
        lastAnalysis = summary;
        showMessage(summary);
        loadThumbnail(uri);
    }

    @Override public void onFocus(boolean success, boolean locked) { if (!destroyed) overlay.focused(success, locked); }

    private final Runnable fadeStatus = () -> status.animate().alpha(0f).setDuration(400).start();

    private void showMessage(String text) {
        if (status == null) return;
        status.animate().cancel();
        status.setText(text);
        status.setAlpha(1f);
        ui.removeCallbacks(fadeStatus);
        ui.postDelayed(fadeStatus, 5000);
    }

    private void loadThumbnail(Uri uri) {
        thumbnails.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(uri, new Size(160, 160), null);
                ui.post(() -> { if (!destroyed) gallery.setImageBitmap(b); });
            } catch (Exception ignored) { }
        });
    }

    private void openGallery() {
        if (lastUri == null) { showMessage("Здесь появится последний снимок или видео"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, getContentResolver().getType(lastUri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { showMessage("Не удалось открыть файл. Найди его в DCIM/SteadyCam."); }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        engine.attach(holder.getSurface(), holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
        if (resumed && has(Manifest.permission.CAMERA)) engine.resume();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { engine.attach(holder.getSurface(), width, height); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { engine.detach(); }

    @Override public void onSensorChanged(SensorEvent event) {
        float x = event.values[0], y = event.values[1];
        if (Math.hypot(x, y) < 2) return;
        float angle = (float) Math.toDegrees(Math.atan2(-x, y)) + displayDegrees();
        while (angle > 90) angle -= 180;
        while (angle < -90) angle += 180;
        overlay.roll = overlay.roll * .75f + angle * .25f;
        if (overlay.level) overlay.invalidate();
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ------------------------------------------------------------------ helpers

    private int displayDegrees() { return getWindowManager().getDefaultDisplay().getRotation() * 90; }
    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private TextView label(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setFontFeatureSettings("tnum");
        return t;
    }
    private Button chip(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(WHITE);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(round(Color.rgb(40, 40, 43), 15));
        return b;
    }
    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30));
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }
    private Button modeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(DIM);
        b.setAllCaps(false);
        b.setLetterSpacing(.06f);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }
    private Glyph glyph(int type, String description) {
        Glyph g = new Glyph(type);
        g.setBackground(round(Color.argb(150, 38, 38, 42), 24));
        g.setContentDescription(description);
        return g;
    }

    private final class FocusFrame extends FrameLayout {
        boolean fromTouch = false;
        FocusFrame() { super(MainActivity.this); setClickable(true); }
        @Override public boolean performClick() {
            super.performClick();
            if (!fromTouch && info != null && !counting) startFocus(getWidth() / 2f, getHeight() / 2f, false);
            return true;
        }
    }

    /** Vector-like icons drawn in code, so the app has no image assets. */
    private final class Glyph extends View {
        final int type;
        boolean on = true, slash = false;
        int tint = WHITE;
        String badge = "";
        private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path gpath = new Path();
        private final RectF rect = new RectF();
        Glyph(int type) { super(MainActivity.this); this.type = type; setClickable(true); setFocusable(true); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, u = Math.min(getWidth(), getHeight()) / 30f;
            int color = (type == G_MIC && !on) ? GRAY : tint;
            gp.setColor(color);
            gp.setStrokeCap(Paint.Cap.ROUND);
            gp.setStrokeJoin(Paint.Join.ROUND);
            gp.setStyle(Paint.Style.FILL);
            switch (type) {
                case G_BOLT:
                    gpath.reset();
                    gpath.moveTo(cx + 3.5f * u, cy - 11 * u);
                    gpath.lineTo(cx - 5.5f * u, cy + 2 * u);
                    gpath.lineTo(cx - .5f * u, cy + 2 * u);
                    gpath.lineTo(cx - 3.5f * u, cy + 11 * u);
                    gpath.lineTo(cx + 5.5f * u, cy - 2 * u);
                    gpath.lineTo(cx + .5f * u, cy - 2 * u);
                    gpath.close();
                    c.drawPath(gpath, gp);
                    if (slash) { gp.setStyle(Paint.Style.STROKE); gp.setStrokeWidth(2 * u); c.drawLine(cx - 9 * u, cy - 10 * u, cx + 9 * u, cy + 10 * u, gp); }
                    if (badge.length() > 0) { gp.setStyle(Paint.Style.FILL); gp.setTextSize(9 * u); gp.setTypeface(Typeface.DEFAULT_BOLD); c.drawText(badge, cx + 6 * u, cy + 11 * u, gp); }
                    break;
                case G_DOTS:
                    for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * 7 * u, cy, 2.2f * u, gp);
                    break;
                case G_FLIP:
                    gp.setStyle(Paint.Style.STROKE);
                    gp.setStrokeWidth(1.8f * u);
                    rect.set(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u);
                    c.drawArc(rect, 200, 125, false, gp);
                    c.drawArc(rect, 20, 125, false, gp);
                    gp.setStyle(Paint.Style.FILL);
                    for (int k = 0; k < 2; k++) {
                        double a = Math.toRadians(325 + 180 * k);
                        float px = cx + 7 * u * (float) Math.cos(a), py = cy + 7 * u * (float) Math.sin(a);
                        float tx = -(float) Math.sin(a), ty = (float) Math.cos(a), nx = (float) Math.cos(a), ny = (float) Math.sin(a);
                        gpath.reset();
                        gpath.moveTo(px + tx * 4.5f * u, py + ty * 4.5f * u);
                        gpath.lineTo(px + nx * 3.2f * u, py + ny * 3.2f * u);
                        gpath.lineTo(px - nx * 3.2f * u, py - ny * 3.2f * u);
                        gpath.close();
                        c.drawPath(gpath, gp);
                    }
                    break;
                case G_MIC:
                    rect.set(cx - 3 * u, cy - 10 * u, cx + 3 * u, cy + 2 * u);
                    c.drawRoundRect(rect, 3 * u, 3 * u, gp);
                    gp.setStyle(Paint.Style.STROKE);
                    gp.setStrokeWidth(1.7f * u);
                    rect.set(cx - 6.5f * u, cy - 6 * u, cx + 6.5f * u, cy + 7 * u);
                    c.drawArc(rect, 0, 180, false, gp);
                    c.drawLine(cx, cy + 7 * u, cx, cy + 11 * u, gp);
                    if (!on) { gp.setStrokeWidth(2 * u); c.drawLine(cx - 8 * u, cy - 9 * u, cx + 8 * u, cy + 10 * u, gp); }
                    break;
                case G_PAUSE:
                    if (slash) { // "slash" is reused as the "paused" flag: show a play triangle
                        gpath.reset();
                        gpath.moveTo(cx - 5 * u, cy - 8 * u);
                        gpath.lineTo(cx - 5 * u, cy + 8 * u);
                        gpath.lineTo(cx + 8 * u, cy);
                        gpath.close();
                        c.drawPath(gpath, gp);
                    } else {
                        rect.set(cx - 6.5f * u, cy - 8 * u, cx - 2 * u, cy + 8 * u);
                        c.drawRoundRect(rect, u, u, gp);
                        rect.set(cx + 2 * u, cy - 8 * u, cx + 6.5f * u, cy + 8 * u);
                        c.drawRoundRect(rect, u, u, gp);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private final class Shutter extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean recording = false, video = true, enabled = false, preparing = false;
        int count = 0;
        Shutter() { super(MainActivity.this); setFocusable(true); setClickable(true); }
        @Override public boolean performClick() { if (enabled) return super.performClick(); return true; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) animate().scaleX(.9f).scaleY(.9f).setDuration(70).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return super.onTouchEvent(e);
        }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(3);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            p.setColor(enabled ? Color.WHITE : GRAY);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(video ? RED : Color.WHITE);
            p.setAlpha(preparing ? 110 : enabled ? 255 : 80);
            if (recording) c.drawRoundRect(cx - dp(16), cy - dp(16), cx + dp(16), cy + dp(16), dp(7), dp(7), p);
            else c.drawCircle(cx, cy, r - dp(8), p);
            p.setAlpha(255);
            if (count > 0) {
                p.setColor(Color.BLACK);
                p.setTextSize(dp(32));
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(Integer.toString(count), cx, cy + dp(11), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }
    }
}
