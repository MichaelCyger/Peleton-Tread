package com.treadcover;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
        ensureOverlayRunning();
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureOverlayRunning();
        // Rebuild so newly installed apps appear without a restart.
        setContentView(buildUi());
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
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Color.rgb(10, 12, 16));
        scroller.setFillViewport(true);

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
        return scroller;
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
}
