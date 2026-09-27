package com.taowen.arglass;

import android.content.Context;
import android.util.Log;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Device ownership for phone ATW. This is not the graphics/runtime owner. */
public final class PhoneAtwDeviceConnection implements AutoCloseable {
    public interface Consumer {
        // Consumer decides which tracking/calibration schemas it can handle.
        // Called before any device mode writes or transport startup.
        void validate(GlassesModel model);
        void initialize(PhoneAtwConfiguration configuration);
        /** Calibration accompanying this sample may change while streaming. */
        void accept(ImuSample sample, ImuCalibrationData calibration);
    }
    public interface Listener {
        void ready(GlassesDisplayProfile profile);
        void failed(String reason);
    }
    private final Context context;
    private final Executor callbacks;
    private final Consumer consumer;
    private final Listener listener;
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final ArGlassesManager discovery;
    private volatile boolean closed;
    private boolean opening;
    // Only the control executor accesses the live session.
    private PhoneAtwSession session;

    public PhoneAtwDeviceConnection(Context context, Executor callbacks,
            Consumer consumer, Listener listener) {
        this.context = context.getApplicationContext();
        this.callbacks = callbacks;
        this.consumer = consumer;
        this.listener = listener;
        discovery = new ArGlassesManager(this.context, callbacks, new ArGlassesListener() {
            @Override public void onPermissionResult(ConnectedGlasses glasses, boolean granted) {
                if (closed) return;
                if (granted) open(glasses); else fail("ar-glass-lib USB 权限被拒绝");
            }
        });
    }

    public synchronized void start() {
        if (closed || opening) return;
        List<ConnectedGlasses> devices = discovery.scan();
        if (devices.size() != 1) {
            fail("ar-glass-lib 需要恰好连接一副已识别眼镜，当前 " + devices.size());
            return;
        }
        ConnectedGlasses device = devices.get(0);
        if (discovery.hasPermission(device)) open(device); else discovery.requestPermission(device);
    }

    private synchronized void open(ConnectedGlasses device) {
        if (opening || closed) return;
        try {
            // Reject missing tracker adaptations before touching the device.
            consumer.validate(device.getModel());
        } catch (IllegalArgumentException error) {
            fail(error.getMessage());
            return;
        }
        opening = true;
        control.execute(() -> {
            try {
                if (closed) return;
                session = PhoneAtwSession.open(context, device, new PhoneAtwSink() {
                    @Override public void onStatus(String message) { Log.i("Library3DoF", message); }
                    @Override public void onSample(ImuSample sample, ImuCalibrationData calibration) {
                        if (!closed) consumer.accept(sample, calibration);
                    }
                });
                PhoneAtwConfiguration configuration = session.getConfiguration();
                synchronized (PhoneAtwDeviceConnection.this) {
                    if (closed) return;
                    consumer.initialize(configuration);
                }
                if (closed) return;
                session.startSamples();
                callbacks.execute(() -> { if (!closed) listener.ready(configuration.getDisplay()); });
            } catch (Exception error) {
                Log.e("Library3DoF", "Library session failed", error);
                if (session != null) { session.close(); session = null; }
                fail(error.toString());
            } finally {
                if (closed && session != null) { session.close(); session = null; }
            }
        });
    }

    private void fail(String reason) {
        callbacks.execute(() -> { if (!closed) listener.failed(reason); });
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        control.execute(() -> {
            try {
                if (session != null) { session.close(); session = null; }
            } finally {
                discovery.close();
            }
        });
        control.shutdown();
    }
}
