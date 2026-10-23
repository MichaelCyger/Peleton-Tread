package com.treadcover;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * Client for the Peloton tread controller (affernet ITreadInterface).
 * Reads metrics for the HUD, keeps Tread Lock cleared, and relays physical knob turns
 * into speed and incline changes. stopBelt() is the only other speed write, and it only lowers speed.
 * Person-present auto-stop and the hardware safety key stay in the controller firmware.
 */
public final class TreadController {
    public interface Listener {
        void onTread(double speedMph, double inclinePercent, double miles, String elapsed);

        /** No PIN saved yet: ask the user to enter it once so the lock auto-clears from now on. */
        void onNeedPin();
    }

    private static final String TAG = "TreadController";
    private static final String DESCRIPTOR = "com.onepeloton.affernetservice.ITreadInterface";
    private static final String PACKAGE = "com.onepeloton.affernetservice";

    private static final int GET_MAX_SPEED = 14;
    private static final int GET_MAX_INCLINE = 15;
    private static final int GET_CURRENT_SPEED = 17;
    private static final int GET_CURRENT_INCLINE = 18;
    private static final int GET_TARGET_SPEED = 19;
    private static final int GET_TARGET_INCLINE = 20;
    private static final int SET_SPEED = 45;
    private static final int SET_INCLINE = 46;
    private static final int REGISTER_CALLBACK = 48;
    private static final int ENABLE_TREAD = 54;
    private static final int ENABLE_PERSON_PRESENT = 59;
    private static final int UNLOCK_TREAD = 73;

    // Peloton TreadControlEvent.value (see com.onepeloton.sensor.tread.TreadControlEvent).
    private static final int EVT_LEFT_KNOB_FORWARD = 3;
    private static final int EVT_LEFT_KNOB_BACKWARD = 4;
    private static final int EVT_RIGHT_KNOB_FORWARD = 7;
    private static final int EVT_RIGHT_KNOB_BACKWARD = 8;

    /** Same step as Peloton Just Run on-screen +/- (0.1 mph, 0.5% incline). */
    private static final int SPEED_STEP_TENTHS = 1;
    private static final int INCLINE_STEP_TENTHS = 5;

    // Confirmed stop. A single SET_SPEED 0 makes the motor board brake hard, so step the
    // target down the same way the knob does: 0.5 mph every half second, about 1 mph/s.
    private static final int STOP_STEP_TENTHS = 5;
    private static final long STOP_STEP_MS = 500L;

    // Registering our callback with an approved package string as the cookie makes affernet
    // treat an approved "pin lock owner" as present, which cancels its 5s rogue re-lock timer.
    // That is what keeps Tread Lock from re-engaging mid-run after a one-time unlock.
    private static final String LOCK_OWNER_COOKIE = "com.onepeloton.lockservice";
    // adb-settable: `settings put global treadcover_pin 1234`. Never stored in source.
    private static final String PIN_SETTING = "treadcover_pin";
    // App-private store so a once-entered PIN auto-unlocks on every boot (no pad again).
    private static final String PREFS = "treadcover";
    private static final String PREF_PIN = "pin";

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private IBinder tread;
    private boolean polling;
    private long firstMoveMs;
    private long lastSampleMs;
    private double miles;
    private boolean metricsPaused;
    private long pausedElapsedMs;
    private int sessionEpoch;

    private TreadCallback callback;
    private boolean ownerRegistered;
    private boolean beltAwake;
    private int maxSpeedTenths = 125;
    private int maxInclineTenths = 125;
    private int targetSpeedTenths;
    private int targetInclineTenths;
    private boolean easingStop;
    private final Runnable easeStop = this::easeOneStep;
    private int lastUnlockResult = Integer.MIN_VALUE;
    private String sessionPin;

    // Screen off puts the controller board to sleep and it locks itself. After screen on the
    // board needs a moment before it accepts an unlock; one sent right away is dropped and
    // affernet gives up on it 20 s later without retrying. So wait before asking, and ask
    // again if affernet reports an unlock timeout (lock reason 5) or the board still says
    // locked when the watchdog fires. All of this is ENABLE_TREAD only; no speed writes.
    private static final long WAKE_DELAY_MS = 5_000L;
    private static final long WAKE_TIMEOUT_MS = 22_000L;
    private static final int WAKE_ATTEMPTS_MAX = 4;
    private static final int LOCK_REASON_UNLOCK_TIMEOUT = 5;
    private int wakeAttempts;
    /** Lock byte from the board's sensor packets: TRUE locked, FALSE unlocked, null unknown. */
    private volatile Boolean hwLocked;
    private final Runnable wakeAttempt = this::sendEnableTread;
    private final Runnable wakeWatchdog = this::onWakeWatchdog;

    public TreadController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tread = service;
            registerLockOwner();
            if (loadStoredPin() != null) {
                // Known PIN: clear the lock automatically — no pad, ever.
                unlockWithStoredPin();
            } else {
                // First run: ask once so we can save it and auto-unlock from now on.
                handler.post(listener::onNeedPin);
            }
            handler.post(poll);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            tread = null;
            ownerRegistered = false;
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling || tread == null) {
                return;
            }
            sample();
            handler.postDelayed(this, 500);
        }
    };

    public void start() {
        polling = true;
        Intent bind = new Intent(DESCRIPTOR);
        bind.setPackage(PACKAGE);
        if (!context.bindService(bind, connection, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "tread controller did not bind");
        }
    }

    public void stop() {
        polling = false;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(wakeAttempt);
        handler.removeCallbacks(wakeWatchdog);
        handler.removeCallbacks(easeStop);
        easingStop = false;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        tread = null;
    }

    private void sample() {
        int speedTenths = readInt(GET_CURRENT_SPEED);
        int inclineTenths = readInt(GET_CURRENT_INCLINE);
        if (speedTenths == Integer.MIN_VALUE || inclineTenths == Integer.MIN_VALUE) {
            return; // transient binder hiccup; keep the last frame on screen
        }

        double speedMph = speedTenths / 10.0;
        double inclinePercent = inclineTenths / 10.0;

        long now = SystemClock.elapsedRealtime();
        if (!metricsPaused) {
            if (speedMph > 0.05) {
                if (firstMoveMs == 0) {
                    firstMoveMs = now;
                    lastSampleMs = now;
                }
                double hours = (now - lastSampleMs) / 3600000.0;
                miles += speedMph * hours;
            }
            lastSampleMs = now;
        }

        String elapsed;
        if (metricsPaused) {
            elapsed = pausedElapsedMs == 0 && firstMoveMs == 0 ? "" : formatElapsed(pausedElapsedMs);
        } else {
            elapsed = firstMoveMs == 0 ? "" : formatElapsed(now - firstMoveMs);
        }

        final double fSpeed = speedMph;
        final double fIncline = inclinePercent;
        final double fMiles = miles;
        final String fElapsed = elapsed;
        final int epoch = sessionEpoch;
        handler.post(() -> {
            if (epoch != sessionEpoch) {
                return;
            }
            listener.onTread(fSpeed, fIncline, fMiles, fElapsed);
        });
    }

    /** Freeze distance and elapsed time. Does not change belt speed. */
    public void pauseMetrics() {
        if (metricsPaused) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        pausedElapsedMs = firstMoveMs == 0 ? 0 : now - firstMoveMs;
        metricsPaused = true;
    }

    /** Continue distance and elapsed time from the frozen values. Does not change belt speed. */
    public void resumeMetrics() {
        if (!metricsPaused) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (firstMoveMs != 0 || pausedElapsedMs > 0) {
            firstMoveMs = now - pausedElapsedMs;
        }
        lastSampleMs = now;
        metricsPaused = false;
    }

    public boolean isMetricsPaused() {
        return metricsPaused;
    }

    /** Zero the HUD's distance and time without touching the belt. */
    public void resetSession() {
        miles = 0;
        firstMoveMs = 0;
        lastSampleMs = 0;
        metricsPaused = false;
        pausedElapsedMs = 0;
        sessionEpoch++;
    }

    public boolean isUnlocked() {
        return sessionPin != null || lastUnlockResult == 0;
    }

    /** Try the user-entered PIN. Returns 0 on success; remembers the PIN so it never needs re-entry. */
    public int tryUnlock(String pin) {
        int r = unlockTread(pin);
        lastUnlockResult = r;
        if (r == 0) {
            sessionPin = pin;
            savePin(pin);
            ensureBeltReady();
        }
        Log.i(TAG, "tryUnlock result=" + r);
        return r;
    }

    private String loadStoredPin() {
        if (sessionPin != null && sessionPin.length() > 0) {
            return sessionPin;
        }
        String pin = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_PIN, null);
        if (pin == null || pin.length() == 0) {
            try {
                pin = android.provider.Settings.Global.getString(
                        context.getContentResolver(), PIN_SETTING);
            } catch (Exception ignored) {
            }
        }
        return (pin == null || pin.length() == 0) ? null : pin;
    }

    private void savePin(String pin) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_PIN, pin).apply();
    }

    /**
     * Register our callback as an approved pin-lock owner. This cancels affernet's rogue
     * re-lock timer so the belt stays unlocked after a one-time unlock. Read-only w.r.t.
     * motion; safe to call on every (re)connect.
     */
    private void registerLockOwner() {
        if (tread == null || ownerRegistered) {
            return;
        }
        if (callback == null) {
            callback = new TreadCallback(new TreadCallback.Listener() {
                @Override
                public void onLockEvent(boolean locked, int reason) {
                    TreadController.this.onLockEvent(locked, reason);
                }

                @Override
                public void onControlEvent(int eventCode) {
                    TreadController.this.onKnobEvent(eventCode);
                }

                @Override
                public void onSensorData(int hardwareLock, int softwareLock) {
                    TreadController.this.onSensorData(hardwareLock, softwareLock);
                }
            });
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(callback);
            data.writeString(LOCK_OWNER_COOKIE);
            data.writeString(LOCK_OWNER_COOKIE);
            tread.transact(REGISTER_CALLBACK, data, reply, 0);
            reply.readException();
            ownerRegistered = true;
            Log.i(TAG, "registered lock owner cookie");
        } catch (Exception e) {
            Log.e(TAG, "registerLockOwner failed: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Clear Tread Lock using a remembered PIN (session, app store, or adb global). */
    private int unlockWithStoredPin() {
        String pin = loadStoredPin();
        if (pin == null) {
            Log.w(TAG, "no tread pin available; cannot unlock");
            return Integer.MIN_VALUE;
        }
        lastUnlockResult = unlockTread(pin);
        if (lastUnlockResult == 0) {
            sessionPin = pin;
            savePin(pin);
            ensureBeltReady();
        }
        Log.i(TAG, "unlockTread result=" + lastUnlockResult);
        return lastUnlockResult;
    }

    /** Wake the tread and sync targets so physical knobs can change speed/incline. */
    private void ensureBeltReady() {
        if (tread == null) {
            return;
        }
        int maxSp = readInt(GET_MAX_SPEED);
        int maxInc = readInt(GET_MAX_INCLINE);
        if (maxSp > 0) {
            maxSpeedTenths = maxSp;
        }
        if (maxInc > 0) {
            maxInclineTenths = maxInc;
        }
        int tgtSp = easingStop ? targetSpeedTenths : readInt(GET_TARGET_SPEED);
        int tgtInc = readInt(GET_TARGET_INCLINE);
        if (!easingStop && tgtSp != Integer.MIN_VALUE && tgtSp >= 0) {
            targetSpeedTenths = tgtSp;
        } else if (!easingStop) {
            int cur = readInt(GET_CURRENT_SPEED);
            targetSpeedTenths = cur == Integer.MIN_VALUE ? 0 : Math.max(0, cur);
        }
        if (tgtInc != Integer.MIN_VALUE && tgtInc >= 0) {
            targetInclineTenths = tgtInc;
        } else {
            int cur = readInt(GET_CURRENT_INCLINE);
            targetInclineTenths = cur == Integer.MIN_VALUE ? 0 : Math.max(0, cur);
        }
        // Safety: an unlock must never start or speed up the belt. Cap the target at
        // what the belt is doing right now (0 when stopped); unknown speed counts as stopped.
        int nowSp = readInt(GET_CURRENT_SPEED);
        targetSpeedTenths = Math.min(targetSpeedTenths, nowSp == Integer.MIN_VALUE ? 0 : Math.max(0, nowSp));
        call(ENABLE_PERSON_PRESENT, 1);
        callVoid(ENABLE_TREAD);
        if (!easingStop) {
            call(SET_SPEED, targetSpeedTenths);
        }
        call(SET_INCLINE, targetInclineTenths);
        beltAwake = true;
        Log.i(TAG, "belt ready targets speed=" + targetSpeedTenths + " incline=" + targetInclineTenths);
    }

    /**
     * Called when the Watch screen comes back (screen on, resume, screensaver dismissed).
     * Re-asserts person-present now and schedules ENABLE_TREAD for once the board has had
     * time to wake. Does not write speed or incline.
     */
    public void powerOn() {
        if (tread == null) {
            return;
        }
        call(ENABLE_PERSON_PRESENT, 1);
        beltAwake = true;
        handler.removeCallbacks(wakeAttempt);
        handler.removeCallbacks(wakeWatchdog);
        wakeAttempts = 0;
        handler.postDelayed(wakeAttempt, WAKE_DELAY_MS);
        Log.i(TAG, "power on: hwLocked=" + hwLocked + ", enable tread in " + WAKE_DELAY_MS + " ms");
    }

    /**
     * The display button turned the screen off. Drop pending ENABLE_TREAD retries; the
     * board answers each one by waking the tablet, which turned the screen back on.
     */
    public void screenOff() {
        handler.removeCallbacks(wakeAttempt);
        handler.removeCallbacks(wakeWatchdog);
        wakeAttempts = 0;
        Log.i(TAG, "screen off: enable retries cancelled");
    }

    private boolean screenInteractive() {
        android.os.PowerManager power =
                (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isInteractive();
    }

    /** One ENABLE_TREAD, then a watchdog in case affernet's unlock request times out. */
    private void sendEnableTread() {
        if (tread == null) {
            return;
        }
        if (!screenInteractive()) {
            Log.i(TAG, "screen off: skip enable tread (it would wake the tablet)");
            return;
        }
        wakeAttempts++;
        callVoid(ENABLE_TREAD);
        Log.i(TAG, "enable tread attempt " + wakeAttempts + " hwLocked=" + hwLocked);
        handler.removeCallbacks(wakeWatchdog);
        handler.postDelayed(wakeWatchdog, WAKE_TIMEOUT_MS);
    }

    private void onWakeWatchdog() {
        if (Boolean.FALSE.equals(hwLocked)) {
            Log.i(TAG, "board unlocked after attempt " + wakeAttempts);
            return;
        }
        if (wakeAttempts >= WAKE_ATTEMPTS_MAX) {
            Log.w(TAG, "board still locked after " + wakeAttempts
                    + " attempts; waiting for the next screen wake");
            return;
        }
        sendEnableTread();
    }

    /** Binder thread. Lock byte from each sensor packet; act only on changes. */
    private void onSensorData(int hardwareLock, int softwareLock) {
        boolean locked = hardwareLock != 0;
        Boolean previous = hwLocked;
        if (previous != null && previous == locked) {
            return;
        }
        hwLocked = locked;
        handler.post(() -> {
            Log.i(TAG, "board lock byte=" + hardwareLock + " software lock=" + softwareLock
                    + (previous == null ? " (first packet)" : locked ? " (locked)" : " (unlocked)"));
            if (locked) {
                return;
            }
            if (handler.hasCallbacks(wakeWatchdog)) {
                handler.removeCallbacks(wakeWatchdog);
                Log.i(TAG, "board unlocked after attempt " + wakeAttempts);
            }
            wakeAttempts = 0;
            // Knob turns made while the board was locked never reached it; start from what
            // the belt and deck are actually doing (0 mph when stopped). Read-only.
            if (previous != null) {
                resyncTargetsFromCurrent();
            }
        });
    }

    private void resyncTargetsFromCurrent() {
        int curSp = readInt(GET_CURRENT_SPEED);
        int curInc = readInt(GET_CURRENT_INCLINE);
        if (curSp != Integer.MIN_VALUE) {
            int cur = Math.max(0, curSp);
            if (!easingStop || cur < targetSpeedTenths) {
                targetSpeedTenths = cur;
            }
        }
        if (curInc != Integer.MIN_VALUE) {
            targetInclineTenths = Math.max(0, curInc);
        }
        Log.i(TAG, "targets resynced speed=" + targetSpeedTenths + " incline=" + targetInclineTenths);
    }

    private void onKnobEvent(int eventCode) {
        if (!beltAwake || tread == null) {
            return;
        }
        if (Boolean.TRUE.equals(hwLocked)) {
            Log.i(TAG, "knob event " + eventCode + " ignored: board locked");
            return;
        }
        if (easingStop && eventCode == EVT_LEFT_KNOB_FORWARD) {
            Log.i(TAG, "knob event " + eventCode + " ignored: slowing to stop");
            return;
        }
        switch (eventCode) {
            case EVT_LEFT_KNOB_FORWARD:
                nudgeSpeed(SPEED_STEP_TENTHS);
                break;
            case EVT_LEFT_KNOB_BACKWARD:
                nudgeSpeed(-SPEED_STEP_TENTHS);
                break;
            case EVT_RIGHT_KNOB_FORWARD:
                nudgeIncline(INCLINE_STEP_TENTHS);
                break;
            case EVT_RIGHT_KNOB_BACKWARD:
                nudgeIncline(-INCLINE_STEP_TENTHS);
                break;
            default:
                break;
        }
    }

    private void nudgeSpeed(int deltaTenths) {
        targetSpeedTenths = clamp(targetSpeedTenths + deltaTenths, 0, maxSpeedTenths);
        call(SET_SPEED, targetSpeedTenths);
    }

    /**
     * The tread itself commanded a stop (physical stop button). Remember 0 so a later
     * sync cannot speed the belt back up. Returns false when this app is already
     * easing the belt down from the on-screen stop.
     */
    public boolean takeExternalStop() {
        if (easingStop || tread == null) {
            return false;
        }
        handler.removeCallbacks(easeStop);
        targetSpeedTenths = 0;
        call(SET_SPEED, 0);
        Log.i(TAG, "external stop, target 0");
        return true;
    }

    /**
     * Ease the belt to 0 mph after "I'm sure". Steps the target down; never raises it.
     * Does not change incline, person-present, or the safety key.
     */
    public void stopBelt() {
        handler.removeCallbacks(easeStop);
        int current = readInt(GET_CURRENT_SPEED);
        if (current != Integer.MIN_VALUE && current >= 0 && current < targetSpeedTenths) {
            targetSpeedTenths = current;
        }
        if (targetSpeedTenths < 0) {
            targetSpeedTenths = 0;
        }
        easingStop = targetSpeedTenths > 0;
        easeOneStep();
    }

    /** One downward SET_SPEED. Schedules the next until the target is 0. */
    private void easeOneStep() {
        if (tread == null) {
            easingStop = false;
            return;
        }
        int next = targetSpeedTenths <= STOP_STEP_TENTHS ? 0 : targetSpeedTenths - STOP_STEP_TENTHS;
        targetSpeedTenths = next;
        call(SET_SPEED, next);
        Log.i(TAG, "slowing to " + next);
        if (next > 0) {
            easingStop = true;
            handler.postDelayed(easeStop, STOP_STEP_MS);
        } else {
            easingStop = false;
        }
    }

    private void nudgeIncline(int deltaTenths) {
        targetInclineTenths = clamp(targetInclineTenths + deltaTenths, 0, maxInclineTenths);
        call(SET_INCLINE, targetInclineTenths);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int unlockTread(String pin) {
        if (tread == null) {
            return Integer.MIN_VALUE;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(pin);
            tread.transact(UNLOCK_TREAD, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RemoteException e) {
            return Integer.MIN_VALUE;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Re-clear Tread Lock and re-wake the belt if the controller locks again. */
    /** Binder thread. Affernet's lock state changes; reason 5 = its unlock request timed out. */
    private void onLockEvent(boolean locked, int reason) {
        handler.post(() -> {
            Log.i(TAG, "lock event locked=" + locked + " reason=" + reason + " hwLocked=" + hwLocked);
            if (!locked) {
                if (reason == LOCK_REASON_UNLOCK_TIMEOUT && handler.hasCallbacks(wakeWatchdog)) {
                    // The board dropped our unlock; do not wait out the rest of the watchdog.
                    handler.removeCallbacks(wakeWatchdog);
                    onWakeWatchdog();
                }
                return;
            }
            if (loadStoredPin() == null) {
                return;
            }
            unlockWithStoredPin();
        });
    }

    private int readInt(int code) {
        if (tread == null) {
            return Integer.MIN_VALUE;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            tread.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RemoteException e) {
            return Integer.MIN_VALUE;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void call(int code, Integer arg) {
        if (tread == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (arg != null) {
                data.writeInt(arg);
            }
            tread.transact(code, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            Log.e(TAG, "call " + code + " failed: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void callVoid(int code) {
        call(code, null);
    }

    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}
