package com.treadcover;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen cover drawn over Just Run. It does not take focus, so the belt lock stays put. */
public final class CoverService extends Service implements TreadController.Listener {
    private static final String CHANNEL = "cover";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windows;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private View expandedBar;
    private View collapsedPill;
    private TextView pillMiles;
    private TextView speedView;
    private TextView inclineView;
    private TextView paceView;
    private TextView distanceView;
    private TextView timeView;
    private PauseGlyph pauseIcon;
    private View pauseButton;
    private View stopButton;
    private boolean workoutControlsOn;
    private boolean workoutControlsSet;
    private View modalScrim;
    private TreadController tread;
    private View pinPad;
    private TextView pinDots;
    private TextView pinError;
    private final StringBuilder pinEntry = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static CoverService active;
    private static boolean metricsHidden;

    /** Hide the metrics bar while the Watch screensaver is up. */
    public static void setMetricsHidden(boolean hidden) {
        metricsHidden = hidden;
        CoverService service = active;
        if (service != null) {
            service.applyMetricsHidden();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        active = this;
        startForeground(NOTIFICATION_ID, notification());
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        showCover();
        tread = new TreadController(this, this);
        tread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (active == this) {
            active = null;
        }
        if (tread != null) {
            tread.stop();
        }
        dismissPinPad();
        dismissModal();
        if (overlay != null) {
            windows.removeView(overlay);
            overlay = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTread(double speedMph, double inclinePercent, double miles, String elapsed) {
        handler.post(() -> render(speedMph, inclinePercent, miles, elapsed));
    }

    @Override
    public void onNeedPin() {
        handler.post(this::showPinPad);
    }

    private void render(double speed, double incline, double miles, String elapsed) {
        speedView.setText(String.format("%.1f", speed));
        inclineView.setText(String.format("%.1f%%", incline));
        if (speed > 0.05) {
            double minutes = 60.0 / speed;
            int whole = (int) minutes;
            int seconds = (int) Math.round((minutes - whole) * 60.0);
            if (seconds == 60) {
                whole += 1;
                seconds = 0;
            }
            paceView.setText(whole + ":" + (seconds < 10 ? "0" : "") + seconds);
        } else {
            paceView.setText("—");
        }
        distanceView.setText(String.format("%.2f", miles));
        timeView.setText(elapsed == null || elapsed.length() == 0 ? "0:00" : elapsed);
        pillMiles.setText(String.format("%.2f", miles));
        boolean clockRunning = elapsed != null && elapsed.length() > 0
                && (tread == null || !tread.isMetricsPaused());
        boolean paused = tread != null && tread.isMetricsPaused();
        setWorkoutControls(clockRunning || paused);
    }

    private static final int BAR_BG = Color.argb(236, 9, 11, 15);
    private static final int ACCENT = Color.rgb(96, 205, 170);

    private void showCover() {
        FrameLayout root = new FrameLayout(this);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(BAR_BG);

        View accent = new View(this);
        accent.setBackgroundColor(ACCENT);
        bar.addView(accent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(28), dp(12), dp(16), dp(12));

        inclineView = metric("0.0%", 30);
        row.addView(metricColumn("INCLINE", inclineView), weight(1f));

        row.addView(divider());
        paceView = metric("—", 30);
        row.addView(metricColumn("PACE", paceView), weight(1f));

        row.addView(divider());
        timeView = metric("0:00", 30);
        row.addView(metricColumn("TIME", timeView), weight(1f));

        row.addView(divider());
        distanceView = metric("0.00", 30);
        row.addView(metricColumn("MILES", distanceView), weight(1f));

        row.addView(divider());
        speedView = metric("0.0", 30);
        row.addView(metricColumn("MPH", speedView), weight(1f));

        pauseIcon = new PauseGlyph(this);
        pauseButton = symbolButton(pauseIcon, v -> onPauseClicked());
        stopButton = symbolButton(new StopGlyph(this), v -> showStopConfirm());
        row.addView(pauseButton);
        row.addView(stopButton);
        setWorkoutControls(false);
        row.addView(iconButton("\u25BC", true, v -> setCollapsed(true)));

        bar.addView(row);

        expandedBar = bar;
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundColor(BAR_BG);
        pill.setPadding(dp(18), dp(10), dp(18), dp(10));
        pillMiles = metric("0.00", 24);
        pill.addView(pillMiles);
        TextView pillUnit = label("  MILES   \u25B2");
        pill.addView(pillUnit);
        pill.setOnClickListener(v -> setCollapsed(false));
        pill.setVisibility(View.GONE);
        collapsedPill = pill;
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pillParams.gravity = Gravity.BOTTOM | Gravity.END;
        root.addView(pill, pillParams);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM;
        windows.addView(root, params);
        overlay = root;
        overlayParams = params;
        applyMetricsHidden();
    }

    private void applyMetricsHidden() {
        int vis = metricsHidden ? View.GONE : View.VISIBLE;
        if (overlay != null) {
            overlay.setVisibility(vis);
        }
        if (modalScrim != null) {
            modalScrim.setVisibility(vis);
        }
        if (pinPad != null) {
            pinPad.setVisibility(vis);
        }
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(Color.argb(38, 255, 255, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(40));
        lp.setMargins(dp(4), 0, dp(4), 0);
        line.setLayoutParams(lp);
        return line;
    }

    private LinearLayout metricColumn(String title, TextView value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView lbl = label(title);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        col.addView(lbl);
        col.addView(value);
        return col;
    }

    /** Subtle minimize chevron. Pause and stop are separate filled buttons. */
    private Button iconButton(String text, boolean chevron, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(170, 178, 188));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, chevron ? 16 : 13);
        button.setLetterSpacing(chevron ? 0f : 0.1f);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(dp(chevron ? 44 : 64));
        button.setMinHeight(dp(56));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void setCollapsed(boolean collapsed) {
        expandedBar.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        collapsedPill.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        overlayParams.width = collapsed
                ? WindowManager.LayoutParams.WRAP_CONTENT
                : WindowManager.LayoutParams.MATCH_PARENT;
        overlayParams.gravity = collapsed ? (Gravity.BOTTOM | Gravity.END) : Gravity.BOTTOM;
        windows.updateViewLayout(overlay, overlayParams);
    }

    /** Filled, rounded control so pause and stop read as buttons, not labels. */
    private FrameLayout symbolButton(View icon, View.OnClickListener listener) {
        FrameLayout button = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(48, 56, 68));
        bg.setCornerRadius(dp(14));
        button.setBackground(bg);
        button.setElevation(dp(3));
        button.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        button.setOnClickListener(listener);
        button.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) {
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.6f);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(58));
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMargins(dp(8), 0, 0, 0);
        button.setLayoutParams(lp);
        return button;
    }

    /** Pause and stop do nothing until the clock is running. A paused workout stays tappable so it can resume or end. */
    private void setWorkoutControls(boolean on) {
        if (pauseButton == null || stopButton == null) {
            return;
        }
        if (workoutControlsSet && workoutControlsOn == on) {
            return;
        }
        workoutControlsSet = true;
        workoutControlsOn = on;
        applyControl(pauseButton, on);
        applyControl(stopButton, on);
    }

    private void applyControl(View button, boolean on) {
        button.setEnabled(on);
        button.setAlpha(on ? 1f : 0.28f);
        button.setElevation(on ? dp(3) : 0);
    }

    private void onPauseClicked() {
        if (!workoutControlsOn || modalScrim != null) {
            return;
        }
        if (tread != null && tread.isMetricsPaused()) {
            tread.resumeMetrics();
            setPausedAppearance(false);
            return;
        }
        if (tread != null) {
            tread.pauseMetrics();
        }
        setPausedAppearance(true);
        pausePlayingVideo();
        showPauseModal();
    }

    /** Pause the show if one is playing. Does not resume it when the clock starts again. */
    private void pausePlayingVideo() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(
                now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        Log.i("CoverService", "media pause");
    }

    private void setPausedAppearance(boolean paused) {
        if (pauseIcon != null) {
            pauseIcon.setShowPlay(paused);
        }
    }

    private void showPauseModal() {
        LinearLayout scrim = modalScrim();
        LinearLayout card = modalCard();
        card.setMinimumWidth(dp(680));
        card.setPadding(dp(48), dp(40), dp(48), dp(32));

        PauseGlyph glyph = new PauseGlyph(this);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(84), dp(84));
        glyphLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(glyph, glyphLp);

        TextView message = new TextView(this);
        message.setText("Time and distance paused");
        message.setTextColor(Color.WHITE);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(22), 0, dp(8));
        card.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout note = new LinearLayout(this);
        note.setOrientation(LinearLayout.HORIZONTAL);
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.addView(pauseNote("Belt runs, click "));
        PauseGlyph play = new PauseGlyph(this);
        play.setShowPlay(true);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        playLp.setMargins(dp(2), 0, dp(2), 0);
        note.addView(play, playLp);
        note.addView(pauseNote(" to resume"));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLp.gravity = Gravity.CENTER_HORIZONTAL;
        noteLp.bottomMargin = dp(28);
        card.addView(note, noteLp);

        Button ok = pillButton("OK", Color.rgb(44, 50, 60), Color.WHITE);
        ok.setOnClickListener(v -> dismissModal());
        card.addView(ok);

        scrim.addView(card, cardParams());
        presentModal(scrim);
    }

    private TextView pauseNote(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(150, 158, 168));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void showStopConfirm() {
        if (!workoutControlsOn || modalScrim != null) {
            return;
        }
        LinearLayout scrim = modalScrim();
        LinearLayout card = modalCard();
        card.setMinimumWidth(dp(560));
        card.setPadding(dp(44), dp(32), dp(44), dp(28));

        TextView title = new TextView(this);
        title.setText("Stop this workout?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("Are you sure?");
        sub.setTextColor(Color.rgb(150, 158, 168));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(24));
        card.addView(sub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button cancel = pillButton("Cancel", Color.rgb(44, 50, 60), Color.WHITE);
        cancel.setOnClickListener(v -> dismissModal());
        buttons.addView(cancel);

        Button sure = pillButton("I'm sure", ACCENT, Color.rgb(8, 14, 12));
        sure.setOnClickListener(v -> {
            String incline = inclineView.getText().toString();
            String mph = speedView.getText().toString();
            String pace = paceView.getText().toString();
            String time = timeView.getText().toString();
            String miles = distanceView.getText().toString();
            if (tread != null) {
                tread.pauseMetrics();
                tread.stopBelt();
            }
            showSummary(incline, mph, pace, time, miles);
        });
        buttons.addView(sure);

        card.addView(buttons);
        scrim.addView(card, cardParams());
        presentModal(scrim);
    }

    private void showSummary(String incline, String mph, String pace, String time, String miles) {
        LinearLayout scrim = modalScrim();
        LinearLayout card = modalCard();
        card.setMinimumWidth(dp(680));

        View accent = new View(this);
        accent.setBackgroundColor(ACCENT);
        card.addView(accent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(40), dp(28), dp(40), dp(32));

        TextView heading = new TextView(this);
        heading.setText("WORKOUT");
        heading.setTextColor(Color.rgb(150, 158, 168));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        heading.setLetterSpacing(0.28f);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        heading.setPadding(0, 0, 0, dp(18));
        body.addView(heading);

        LinearLayout heroes = new LinearLayout(this);
        heroes.setOrientation(LinearLayout.HORIZONTAL);
        heroes.setGravity(Gravity.CENTER);
        heroes.addView(summaryFigure(miles, "MILES", 56), weight(1f));
        heroes.addView(summaryFigure(time, "TIME", 56), weight(1f));
        body.addView(heroes, new LinearLayout.LayoutParams(dp(600), LinearLayout.LayoutParams.WRAP_CONTENT));

        View rule = new View(this);
        rule.setBackgroundColor(Color.argb(40, 255, 255, 255));
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        ruleLp.setMargins(dp(8), dp(22), dp(8), dp(18));
        body.addView(rule, ruleLp);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.setGravity(Gravity.CENTER);
        details.addView(summaryFigure(incline, "INCLINE", 28), weight(1f));
        details.addView(summaryFigure(mph, "MPH", 28), weight(1f));
        details.addView(summaryFigure(pace, "PACE", 28), weight(1f));
        body.addView(details, new LinearLayout.LayoutParams(dp(600), LinearLayout.LayoutParams.WRAP_CONTENT));

        Button close = pillButton("Close", ACCENT, Color.rgb(8, 14, 12));
        close.setMinWidth(dp(220));
        close.setOnClickListener(v -> finishWorkout());
        LinearLayout.LayoutParams closeLp = (LinearLayout.LayoutParams) close.getLayoutParams();
        closeLp.topMargin = dp(28);
        closeLp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(close);

        card.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scrim.addView(card, cardParams());
        presentModal(scrim);
    }

    private LinearLayout summaryFigure(String value, String title, int sp) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(8), 0, dp(8), 0);
        col.addView(metric(value, sp));
        TextView lbl = label(title);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lbl.setPadding(0, dp(6), 0, 0);
        col.addView(lbl);
        return col;
    }

    private void finishWorkout() {
        if (tread != null) {
            tread.resetSession();
        }
        distanceView.setText("0.00");
        timeView.setText("0:00");
        pillMiles.setText("0.00");
        setPausedAppearance(false);
        setWorkoutControls(false);
        dismissModal();
    }

    private LinearLayout modalScrim() {
        LinearLayout scrim = new LinearLayout(this);
        scrim.setOrientation(LinearLayout.VERTICAL);
        scrim.setGravity(Gravity.CENTER);
        scrim.setBackgroundColor(Color.argb(205, 0, 0, 0));
        return scrim;
    }

    private LinearLayout modalCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(16, 18, 24));
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setClipToOutline(true);
        return card;
    }

    private void presentModal(View scrim) {
        dismissModal();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        windows.addView(scrim, params);
        modalScrim = scrim;
    }

    private void dismissModal() {
        if (modalScrim == null) {
            return;
        }
        try {
            windows.removeView(modalScrim);
        } catch (IllegalArgumentException ignored) {
        }
        modalScrim = null;
    }

    /** Centered, content-sized card (vertical LinearLayout would otherwise stretch it full width). */
    private LinearLayout.LayoutParams cardParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private Button pillButton(String text, int bg, int fg) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(fg);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setBackgroundColor(bg);
        button.setMinWidth(dp(150));
        button.setMinHeight(dp(60));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), 0, dp(12), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private Button control(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.rgb(44, 50, 60));
        button.setMinWidth(dp(56));
        button.setMinHeight(dp(56));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void showPinPad() {
        if (pinPad != null) {
            return;
        }
        pinEntry.setLength(0);

        LinearLayout scrim = new LinearLayout(this);
        scrim.setOrientation(LinearLayout.VERTICAL);
        scrim.setGravity(Gravity.CENTER);
        scrim.setBackgroundColor(Color.argb(205, 0, 0, 0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundColor(Color.rgb(18, 20, 26));
        card.setPadding(dp(36), dp(28), dp(36), dp(28));

        TextView title = new TextView(this);
        title.setText("Enter Tread Lock PIN");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setPadding(0, 0, 0, dp(6));
        card.addView(title);

        pinDots = new TextView(this);
        pinDots.setTextColor(Color.rgb(120, 200, 255));
        pinDots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        pinDots.setMinHeight(dp(42));
        card.addView(pinDots);

        pinError = new TextView(this);
        pinError.setTextColor(Color.rgb(230, 90, 90));
        pinError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pinError.setMinHeight(dp(20));
        card.addView(pinError);

        int[][] rows = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
        for (int[] row : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int d : row) {
                r.addView(pinKey(String.valueOf(d), d));
            }
            card.addView(r);
        }
        LinearLayout lastRow = new LinearLayout(this);
        lastRow.setOrientation(LinearLayout.HORIZONTAL);
        lastRow.addView(pinAction("Clear", () -> {
            pinEntry.setLength(0);
            updateDots();
        }));
        lastRow.addView(pinKey("0", 0));
        lastRow.addView(pinAction("Cancel", this::dismissPinPad));
        card.addView(lastRow);

        scrim.addView(card, cardParams());

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        windows.addView(scrim, params);
        pinPad = scrim;
        updateDots();
    }

    private Button pinKey(String label, int digit) {
        Button button = control(label, v -> {
            if (pinEntry.length() < 8) {
                pinEntry.append(digit);
                updateDots();
            }
            if (pinEntry.length() == 4) {
                submitPin();
            }
        });
        button.setMinWidth(dp(76));
        button.setMinHeight(dp(64));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        return button;
    }

    private Button pinAction(String label, Runnable action) {
        Button button = control(label, v -> action.run());
        button.setMinWidth(dp(76));
        button.setMinHeight(dp(64));
        return button;
    }

    private void updateDots() {
        if (pinDots == null) {
            return;
        }
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < pinEntry.length(); i++) {
            dots.append("\u25CF ");
        }
        pinDots.setText(dots.toString().trim());
        if (pinError != null) {
            pinError.setText("");
        }
    }

    private void submitPin() {
        if (tread == null) {
            return;
        }
        int result = tread.tryUnlock(pinEntry.toString());
        if (result == 0) {
            dismissPinPad();
        } else {
            pinEntry.setLength(0);
            updateDots();
            pinError.setText("Incorrect PIN");
        }
    }

    private void dismissPinPad() {
        if (pinPad != null) {
            windows.removeView(pinPad);
            pinPad = null;
        }
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(150, 158, 168));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setLetterSpacing(0.18f);
        return view;
    }

    private TextView metric(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Tread cover", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Tread cover")
                .setContentText("Covering the Peloton screen")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    /** Two bars, or a play triangle once time and distance are paused. */
    private static final class PauseGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean showPlay;

        PauseGlyph(Context context) {
            super(context);
            paint.setColor(Color.WHITE);
        }

        void setShowPlay(boolean showPlay) {
            this.showPlay = showPlay;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (showPlay) {
                Path path = new Path();
                float inset = w * 0.16f;
                path.moveTo(inset, h * 0.1f);
                path.lineTo(w - inset * 0.35f, h / 2f);
                path.lineTo(inset, h * 0.9f);
                path.close();
                canvas.drawPath(path, paint);
                return;
            }
            float barW = w * 0.28f;
            float gap = w * 0.16f;
            float left = (w - barW * 2f - gap) / 2f;
            float top = h * 0.06f;
            float bottom = h * 0.94f;
            float radius = barW / 4f;
            canvas.drawRoundRect(left, top, left + barW, bottom, radius, radius, paint);
            float second = left + barW + gap;
            canvas.drawRoundRect(second, top, second + barW, bottom, radius, radius, paint);
        }
    }

    /** Red octagon. The belt is not stopped; this ends the counted workout. */
    private static final class StopGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StopGlyph(Context context) {
            super(context);
            paint.setColor(Color.rgb(214, 59, 59));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float rad = Math.min(getWidth(), getHeight()) / 2f;
            Path path = new Path();
            for (int i = 0; i < 8; i++) {
                double angle = Math.toRadians(-90 + 22.5 + i * 45.0);
                float x = cx + (float) (Math.cos(angle) * rad);
                float y = cy + (float) (Math.sin(angle) * rad);
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            path.close();
            canvas.drawPath(path, paint);
        }
    }
}
