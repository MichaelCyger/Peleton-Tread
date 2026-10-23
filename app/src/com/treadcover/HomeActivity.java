package com.treadcover;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Replacement home screen. Shows the streaming / media apps installed on the Tread as
 * tiles the user can launch. Replaces Peloton's "Welcome / Activate your Tread" screen.
 * Also makes sure the metrics overlay service is running whenever we land here.
 */
public final class HomeActivity extends Activity {
    private static final long SAVER_IDLE_MS = 60_000;
    private static final long SLEEP_HOLD_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScreensaverView screensaver;
    private SleepButton sleepControl;
    private boolean resumed;
    private boolean sleepHeld;
    private static HomeActivity foreground;

    private final Runnable startSaver = new Runnable() {
        @Override
        public void run() {
            beginSaver(false);
        }
    };

    private final Runnable sleepHold = new Runnable() {
        @Override
        public void run() {
            sleepHeld = true;
            beginSaver(true);
        }
    };

    // Peloton chrome + Qualcomm/system test apps we never want to surface as a tile.
    private static final String[] HIDE_PREFIXES = {
        "com.treadcover",
        "com.peloton",
        "com.onepeloton",
        "com.qualcomm",
        "org.codeaurora",
        "com.android.webview",
        "com.android.soundrecorder",
        "com.android.settings.intelligence",
        "com.android.gallery3d",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Never turn a sleeping screen on. The button on the back of the
        // screen decides; KEEP_SCREEN_ON only stops the idle timeout while Watch is up.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ensureOverlayRunning();
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        foreground = this;
        ensureOverlayRunning();
        // Rebuild so newly installed apps appear without a restart.
        setContentView(buildUi());
        armSaver();
        powerWatchOn();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (foreground == this) {
            foreground = null;
        }
        handler.removeCallbacks(startSaver);
        handler.removeCallbacks(sleepHold);
        if (screensaver != null) {
            screensaver.stop();
        }
        setWatchChrome(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (screensaver != null && screensaver.isRunning()) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                screensaver.stop();
                setWatchChrome(false);
                powerWatchOn();
                armSaver();
            }
            return true;
        }
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            armSaver();
        }
        return super.dispatchTouchEvent(ev);
    }

    /** The belt started moving. Leave Watch up instead of covering it. */
    static void beltStarted() {
        HomeActivity activity = foreground;
        if (activity != null) {
            activity.stopSaverBecauseBeltIsMoving();
        }
    }

    private void stopSaverBecauseBeltIsMoving() {
        if (screensaver != null && screensaver.isRunning()) {
            screensaver.stop();
            setWatchChrome(false);
            Log.i("HomeActivity", "screensaver off, belt moving");
        }
        armSaver();
    }

    private void beginSaver(boolean party) {
        if (!resumed || screensaver == null) {
            return;
        }
        if (CoverService.isBeltMoving()) {
            Log.i("HomeActivity", "screensaver skipped, belt moving");
            armSaver();
            return;
        }
        handler.removeCallbacks(startSaver);
        setWatchChrome(true);
        screensaver.start();
        if (party) {
            screensaver.previewCorner();
        }
    }

    private static final int PELOTON_WINDOW = 0x80000000;

    /** The tablet hides its bottom row only for a window marked as a Peloton window. */
    private void setPelotonWindow(boolean on) {
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        try {
            Field field = WindowManager.LayoutParams.class.getField("privateFlags");
            int flags = field.getInt(attrs);
            if (on) {
                flags |= PELOTON_WINDOW;
            } else {
                flags &= ~PELOTON_WINDOW;
            }
            field.setInt(attrs, flags);
            getWindow().setAttributes(attrs);
        } catch (Exception e) {
            Log.w("HomeActivity", "peloton window flag", e);
        }
    }

    private void setWatchChrome(boolean saver) {
        setPelotonWindow(saver);
        View decor = getWindow().getDecorView();
        if (saver) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        if (sleepControl != null) {
            sleepControl.setAlpha(0.72f);
        }
        CoverService.setMetricsHidden(saver);
    }

    /** Screen on, and the controller enabled, once Watch is on screen again. */
    private void powerWatchOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        // Screen off (button pressed): leave it off. Watch can come back to the front
        // while the display sleeps, and that must not wake it.
        if (power != null && power.isInteractive()) {
            PowerManager.WakeLock wake = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "treadcover:watch");
            wake.setReferenceCounted(false);
            wake.acquire(5_000L);
        }
        CoverService.powerController();
    }

    private void armSaver() {
        handler.removeCallbacks(startSaver);
        handler.postDelayed(startSaver, SAVER_IDLE_MS);
    }

    private void ensureOverlayRunning() {
        Intent service = new Intent(this, CoverService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.addView(new PelotonMark(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(36), dp(40), dp(36));

        TextView heading = new TextView(this);
        heading.setText("Watch");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        heading.setPadding(0, 0, 0, dp(24));
        root.addView(heading);

        GridLayout grid = new GridLayout(this);
        int columns = 5;
        grid.setColumnCount(columns);

        List<ResolveInfo> apps = launchableApps();
        for (ResolveInfo info : apps) {
            grid.addView(tile(info));
        }
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No apps found. Install Netflix or another app and it will appear here.");
            empty.setTextColor(Color.rgb(150, 158, 168));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            root.addView(empty);
        } else {
            root.addView(grid);
        }

        scroller.addView(root);
        frame.addView(scroller, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        frame.addView(sleepButton());
        screensaver = new ScreensaverView(this);
        frame.addView(screensaver, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private View sleepButton() {
        sleepControl = new SleepButton(this);
        int size = dp(52);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(18);
        lp.rightMargin = dp(18);
        lp.setMarginEnd(dp(18));
        sleepControl.setLayoutParams(lp);
        sleepControl.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sleepHeld = false;
                        v.setAlpha(1f);
                        handler.postDelayed(sleepHold, SLEEP_HOLD_MS);
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(sleepHold);
                        v.setAlpha(0.72f);
                        if (!sleepHeld) {
                            beginSaver(false);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(sleepHold);
                        v.setAlpha(0.72f);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return sleepControl;
    }

    private List<ResolveInfo> launchableApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(launcher, 0);
        List<ResolveInfo> keep = new ArrayList<>();
        for (ResolveInfo info : all) {
            String pkg = info.activityInfo.packageName;
            if (isHidden(pkg)) {
                continue;
            }
            keep.add(info);
        }
        Collections.sort(keep, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        return keep;
    }

    private boolean isHidden(String pkg) {
        for (String prefix : HIDE_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private View tile(final ResolveInfo info) {
        final PackageManager pm = getPackageManager();
        final String pkg = info.activityInfo.packageName;

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(16), dp(18), dp(16), dp(18));
        tile.setClickable(true);
        tile.setBackgroundColor(Color.rgb(22, 25, 32));

        ImageView icon = new ImageView(this);
        try {
            Drawable d = info.loadIcon(pm);
            icon.setImageDrawable(d);
        } catch (Exception ignored) {
        }
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(96), dp(96));
        icon.setLayoutParams(iconParams);
        tile.addView(icon);

        TextView label = new TextView(this);
        label.setText(info.loadLabel(pm));
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setPadding(0, dp(10), 0, 0);
        tile.addView(label);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                }
            }
        });

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.setMargins(dp(10), dp(10), dp(10), dp(10));
        lp.width = dp(150);
        tile.setLayoutParams(lp);
        return tile;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    /** Sleep mark from the owner's icon: crescent plus three Zs. Tap sleeps. Hold plays the corner burst. */
    private static final class SleepButton extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path icon = sleepIcon();

        SleepButton(Context context) {
            super(context);
            setAlpha(0.72f);
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float pad = Math.min(w, h) * 0.25f;
            canvas.save();
            canvas.translate(pad, pad);
            canvas.scale((w - pad * 2f) / 100f, (h - pad * 2f) / 100f);
            canvas.drawPath(icon, ink);
            canvas.restore();
        }

        private static Path sleepIcon() {
            Path icon = new Path();
            icon.setFillType(Path.FillType.EVEN_ODD);
            icon.moveTo(79.15f, 0.00f);
            icon.lineTo(95.73f, 0.00f);
            icon.lineTo(97.63f, 1.26f);
            icon.lineTo(98.10f, 2.09f);
            icon.lineTo(97.87f, 4.60f);
            icon.lineTo(88.39f, 15.06f);
            icon.lineTo(88.63f, 15.69f);
            icon.lineTo(96.45f, 15.90f);
            icon.lineTo(98.10f, 17.99f);
            icon.lineTo(98.10f, 19.46f);
            icon.lineTo(97.16f, 21.13f);
            icon.lineTo(95.02f, 21.97f);
            icon.lineTo(79.86f, 21.97f);
            icon.lineTo(77.49f, 20.92f);
            icon.lineTo(76.78f, 19.67f);
            icon.lineTo(77.01f, 17.15f);
            icon.lineTo(86.49f, 6.90f);
            icon.lineTo(86.26f, 6.28f);
            icon.lineTo(79.15f, 6.28f);
            icon.lineTo(78.20f, 5.86f);
            icon.lineTo(77.01f, 4.60f);
            icon.lineTo(76.78f, 2.51f);
            icon.lineTo(77.25f, 1.26f);
            icon.lineTo(78.91f, 0.21f);
            icon.close();
            icon.moveTo(50.71f, 6.69f);
            icon.lineTo(62.56f, 6.69f);
            icon.lineTo(64.93f, 8.16f);
            icon.lineTo(64.93f, 11.51f);
            icon.lineTo(59.72f, 17.15f);
            icon.lineTo(59.95f, 17.78f);
            icon.lineTo(63.27f, 17.99f);
            icon.lineTo(64.93f, 19.25f);
            icon.lineTo(65.17f, 21.97f);
            icon.lineTo(63.98f, 23.64f);
            icon.lineTo(63.03f, 24.06f);
            icon.lineTo(50.47f, 24.06f);
            icon.lineTo(48.82f, 23.22f);
            icon.lineTo(48.10f, 22.18f);
            icon.lineTo(48.10f, 19.67f);
            icon.lineTo(53.79f, 13.39f);
            icon.lineTo(53.55f, 12.97f);
            icon.lineTo(49.76f, 12.76f);
            icon.lineTo(48.34f, 11.51f);
            icon.lineTo(48.34f, 8.16f);
            icon.lineTo(49.76f, 6.90f);
            icon.lineTo(50.47f, 6.90f);
            icon.close();
            icon.moveTo(33.41f, 11.72f);
            icon.lineTo(35.78f, 11.72f);
            icon.lineTo(37.91f, 13.60f);
            icon.lineTo(37.91f, 16.11f);
            icon.lineTo(36.02f, 20.92f);
            icon.lineTo(34.83f, 28.24f);
            icon.lineTo(35.31f, 35.77f);
            icon.lineTo(36.73f, 41.21f);
            icon.lineTo(41.47f, 50.42f);
            icon.lineTo(45.26f, 55.02f);
            icon.lineTo(50.71f, 59.83f);
            icon.lineTo(57.35f, 64.02f);
            icon.lineTo(62.09f, 66.11f);
            icon.lineTo(71.56f, 68.62f);
            icon.lineTo(77.96f, 69.25f);
            icon.lineTo(83.89f, 69.04f);
            icon.lineTo(89.57f, 68.20f);
            icon.lineTo(96.21f, 66.32f);
            icon.lineTo(98.34f, 66.74f);
            icon.lineTo(100.00f, 68.41f);
            icon.lineTo(100.00f, 70.50f);
            icon.lineTo(96.68f, 76.99f);
            icon.lineTo(93.60f, 81.17f);
            icon.lineTo(88.63f, 86.40f);
            icon.lineTo(81.75f, 91.63f);
            icon.lineTo(77.01f, 94.35f);
            icon.lineTo(68.48f, 97.70f);
            icon.lineTo(59.48f, 99.58f);
            icon.lineTo(48.34f, 100.00f);
            icon.lineTo(36.97f, 98.33f);
            icon.lineTo(29.86f, 96.03f);
            icon.lineTo(21.09f, 91.42f);
            icon.lineTo(12.56f, 84.52f);
            icon.lineTo(6.16f, 76.57f);
            icon.lineTo(3.08f, 70.50f);
            icon.lineTo(0.47f, 61.51f);
            icon.lineTo(0.00f, 51.26f);
            icon.lineTo(1.18f, 44.14f);
            icon.lineTo(4.03f, 36.19f);
            icon.lineTo(9.95f, 27.20f);
            icon.lineTo(16.11f, 21.13f);
            icon.lineTo(25.59f, 14.85f);
            icon.lineTo(33.18f, 11.92f);
            icon.close();
            icon.moveTo(28.67f, 21.76f);
            icon.lineTo(27.73f, 21.34f);
            icon.lineTo(23.22f, 24.27f);
            icon.lineTo(16.35f, 30.54f);
            icon.lineTo(12.32f, 35.98f);
            icon.lineTo(8.53f, 44.35f);
            icon.lineTo(7.11f, 51.67f);
            icon.lineTo(7.35f, 59.21f);
            icon.lineTo(9.95f, 68.41f);
            icon.lineTo(13.03f, 74.06f);
            icon.lineTo(18.01f, 80.13f);
            icon.lineTo(23.93f, 85.15f);
            icon.lineTo(29.86f, 88.70f);
            icon.lineTo(39.34f, 92.26f);
            icon.lineTo(48.82f, 93.72f);
            icon.lineTo(56.87f, 93.51f);
            icon.lineTo(67.30f, 91.21f);
            icon.lineTo(74.88f, 87.87f);
            icon.lineTo(80.09f, 84.52f);
            icon.lineTo(85.55f, 79.71f);
            icon.lineTo(89.34f, 75.10f);
            icon.lineTo(88.39f, 74.69f);
            icon.lineTo(81.04f, 75.52f);
            icon.lineTo(73.93f, 75.31f);
            icon.lineTo(64.22f, 73.64f);
            icon.lineTo(54.50f, 70.08f);
            icon.lineTo(49.05f, 66.95f);
            icon.lineTo(43.60f, 62.97f);
            icon.lineTo(39.81f, 59.41f);
            icon.lineTo(34.83f, 53.35f);
            icon.lineTo(30.81f, 45.82f);
            icon.lineTo(29.38f, 41.84f);
            icon.lineTo(27.96f, 34.73f);
            icon.lineTo(27.73f, 28.03f);
            icon.lineTo(28.67f, 21.97f);
            icon.close();
            icon.moveTo(76.30f, 31.17f);
            icon.lineTo(87.44f, 31.17f);
            icon.lineTo(90.05f, 32.43f);
            icon.lineTo(90.52f, 33.68f);
            icon.lineTo(90.28f, 35.77f);
            icon.lineTo(84.83f, 41.84f);
            icon.lineTo(85.07f, 42.47f);
            icon.lineTo(88.39f, 42.47f);
            icon.lineTo(90.28f, 43.93f);
            icon.lineTo(90.52f, 46.23f);
            icon.lineTo(89.57f, 47.91f);
            icon.lineTo(87.44f, 48.74f);
            icon.lineTo(76.30f, 48.74f);
            icon.lineTo(75.12f, 48.54f);
            icon.lineTo(73.46f, 47.07f);
            icon.lineTo(73.22f, 44.98f);
            icon.lineTo(73.70f, 43.72f);
            icon.lineTo(78.91f, 38.08f);
            icon.lineTo(78.67f, 37.45f);
            icon.lineTo(75.36f, 37.45f);
            icon.lineTo(73.46f, 35.77f);
            icon.lineTo(73.46f, 32.85f);
            icon.lineTo(75.12f, 31.38f);
            icon.lineTo(76.07f, 31.38f);
            icon.close();
            return icon;
        }
    }
}
