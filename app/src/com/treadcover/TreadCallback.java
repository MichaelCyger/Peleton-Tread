package com.treadcover;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Minimal ITreadCallback for affernet: lock events, physical knob events, and the
 * lock-owner cookie registration. Sensor/OTA callbacks are one-way; we just ack them.
 */
final class TreadCallback extends Binder {
    static final String DESCRIPTOR = "com.onepeloton.affernetservice.ITreadCallback";

    private static final int ON_SENSOR_DATA_CHANGE = 1;
    private static final int ON_TREAD_LOCKED = 8;
    private static final int ON_TREAD_CONTROL_EVENT = 9;

    interface Listener {
        void onLockEvent(boolean locked, int reason);

        /** Peloton TreadControlEvent ordinal (3/4 = speed knob, 7/8 = incline knob). */
        void onControlEvent(int eventCode);
    }

    private final Listener listener;

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
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
        return true;
    }
}
