package com.treadcover;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Minimal ITreadCallback for affernet: lock events, physical knob events, the lock-owner
 * cookie registration, and the hardware lock byte out of each sensor packet. Everything
 * here is read-only; nothing in this class sends a command to the controller.
 */
final class TreadCallback extends Binder {
    static final String DESCRIPTOR = "com.onepeloton.affernetservice.ITreadCallback";

    private static final int ON_SENSOR_DATA_CHANGE = 1;
    private static final int ON_TREAD_LOCKED = 8;
    private static final int ON_TREAD_CONTROL_EVENT = 9;

    // Give up on the sensor packet layout after this many parcels that do not line up.
    private static final int LAYOUT_FAILURES_MAX = 5;

    interface Listener {
        void onLockEvent(boolean locked, int reason);

        /** Peloton TreadControlEvent ordinal (3/4 = speed knob, 7/8 = incline knob). */
        void onControlEvent(int eventCode);

        /**
         * From every sensor packet (about 20 per second while the board is awake).
         * hardwareLock is the lock byte the board reports: 0 = unlocked, anything else =
         * locked. softwareLock is affernet's own lock state (1 = locked).
         */
        void onSensorData(int hardwareLock, int softwareLock);
    }

    private final Listener listener;
    private int layoutFailures;

    TreadCallback(Listener listener) {
        this.listener = listener;
        attachInterface(null, DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        if (listener == null) {
            return true;
        }
        try {
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case ON_TREAD_CONTROL_EVENT: {
                    int event = data.readByte() & 0xff;
                    data.readLong(); // timestamp; unused
                    listener.onControlEvent(event);
                    break;
                }
                case ON_TREAD_LOCKED: {
                    int lockedRaw = data.readInt();
                    int reason = data.readInt();
                    listener.onLockEvent(lockedRaw != 0, reason);
                    break;
                }
                case ON_SENSOR_DATA_CHANGE: {
                    if (layoutFailures >= LAYOUT_FAILURES_MAX || data.readInt() == 0) {
                        break; // layout unknown, or a null TreadData
                    }
                    int[] locks = readLockBytes(data);
                    if (locks == null) {
                        layoutFailures++;
                        break;
                    }
                    listener.onSensorData(locks[0], locks[1]);
                    break;
                }
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    /**
     * Walk affernet 3.2.1's TreadData.writeToParcel order and return {hardwareLock,
     * softwareLock}, or null if the parcel does not end exactly where the layout says it
     * should (different affernet build). Only the two lock fields are kept.
     */
    private static int[] readLockBytes(Parcel p) {
        try {
            p.readLong();                                   // a
            skipStrings(p, 4);                              // b c d e
            p.readInt();                                    // f
            p.createIntArray();                             // g
            p.readString();                                 // h
            p.readInt();                                    // i
            p.readString();                                 // j
            p.readLong();                                   // k
            skipInts(p, 11);                                // l m n o r s t u v y z
            p.readLong();                                   // C
            p.readLong();                                   // D
            skipStrings(p, 3);                              // E F G
            int softwareLock = p.readInt();                 // H
            p.createIntArray();                             // I
            p.readLong();                                   // J
            skipInts(p, 10);                                // K L N O P Q R S T U
            p.createByteArray();                            // i0
            skipInts(p, 3);                                 // A M V
            skipStrings(p, 2);                              // Y Z
            skipInts(p, 6);                                 // a0 p q w x B
            skipStrings(p, 2);                              // W X
            skipInts(p, 10);                                // b0 c0 d0 e0 f0 g0 h0 j0 k0 l0
            p.readInt();                                    // m0 (boolean as int)
            skipInts(p, 7);                                 // n0 o0 p0 q0 r0 s0 t0
            p.readString();                                 // w0
            int hardwareLock = p.readInt();                 // x0: board lock byte, 0 = unlocked
            p.readInt();                                    // u0
            p.readInt();                                    // v0
            if (p.dataAvail() != 0) {
                return null;
            }
            return new int[] {hardwareLock, softwareLock};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void skipInts(Parcel p, int count) {
        for (int i = 0; i < count; i++) {
            p.readInt();
        }
    }

    private static void skipStrings(Parcel p, int count) {
        for (int i = 0; i < count; i++) {
            p.readString();
        }
    }
}
