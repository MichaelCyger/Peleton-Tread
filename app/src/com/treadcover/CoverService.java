package com.treadcover;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
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
    private TextView pillSpeed;
    private TextView speedView;
    private TextView inclineView;
    private TextView paceView;
    private TextView distanceView;
    private TextView timeView;
    private TreadController tread;
    private View pinPad;
    private TextView pinDots;
    private TextView pinError;
    private final StringBuilder pinEntry = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
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
        if (tread != null) {
            tread.stop();
        }
        dismissPinPad();
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
        pillSpeed.setText(String.format("%.1f", speed));
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

        speedView = metric("0.0", 30);
        row.addView(metricColumn("MPH", speedView, true), weight(1f));

        row.addView(divider());
        inclineView = metric("0.0%", 30);
        row.addView(metricColumn("INCLINE", inclineView, false), weight(1f));

        row.addView(divider());
        paceView = metric("—", 30);
        row.addView(metricColumn("PACE", paceView, false), weight(1f));

        row.addView(divider());
        distanceView = metric("0.00", 30);
        row.addView(metricColumn("MILES", distanceView, false), weight(1f));

        row.addView(divider());
        timeView = metric("0:00", 30);
        row.addView(metricColumn("TIME", timeView, false), weight(1f));

        row.addView(iconButton("RESET", false, v -> showResetConfirm()));
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
        pillSpeed = metric("0.0", 24);
        pill.addView(pillSpeed);
        TextView pillUnit = label("  MPH   \u25B2");
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

    private LinearLayout metricColumn(String title, TextView value, boolean hero) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView lbl = label(title);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        if (hero) {
            lbl.setTextColor(ACCENT);
        }
        col.addView(lbl);
        col.addView(value);
        return col;
    }

    /** Subtle right-side button for RESET and the minimize chevron. */
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

    private void showResetConfirm() {
        final LinearLayout scrim = new LinearLayout(this);
        scrim.setOrientation(LinearLayout.VERTICAL);
        scrim.setGravity(Gravity.CENTER);
        scrim.setBackgroundColor(Color.argb(205, 0, 0, 0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundColor(Color.rgb(18, 20, 26));
        card.setPadding(dp(44), dp(32), dp(44), dp(28));

        TextView title = new TextView(this);
        title.setText("Reset distance & time?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Are you sure?");
        sub.setTextColor(Color.rgb(150, 158, 168));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(8), 0, dp(24));
        card.addView(sub);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button cancel = pillButton("Cancel", Color.rgb(44, 50, 60), Color.WHITE);
        cancel.setOnClickListener(v -> windows.removeView(scrim));
        buttons.addView(cancel);

        Button reset = pillButton("Reset", ACCENT, Color.rgb(8, 14, 12));
        reset.setOnClickListener(v -> {
            if (tread != null) {
                tread.resetSession();
            }
            distanceView.setText("0.00");
            timeView.setText("0:00");
            windows.removeView(scrim);
        });
        buttons.addView(reset);

        card.addView(buttons);
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
}
