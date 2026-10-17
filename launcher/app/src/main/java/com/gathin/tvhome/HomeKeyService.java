package com.gathin.tvhome;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Brings this home screen forward when Fire OS shows Amazon's home.
 *
 * Fire OS sends the Home button straight to Amazon's launcher, which is protected, and it blocks every
 * public hook: third-party accessibility services are dropped, the "homekey" broadcast needs an Amazon
 * permission, and the default-home setting is ignored. What remains is the system log, readable once
 * READ_LOGS is granted over adb. This follows two tags as a stream (no polling):
 *   events am_focused_activity  Amazon's home activity got focus (boot, Back out of an Amazon screen)
 *   system ContextImpl          FireTVKeyPolicyManager.handleShortPressOnHome (every Home press)
 */
public class HomeKeyService extends Service {

    private static final String TAG = "TvHomeKey";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher/.ui.HomeActivity_vNext";
    /** Settings on Fire OS is a tab of Amazon's home activity, reached through these packages. */
    private static final String[] SETTINGS_PREFIXES = {"com.amazon.tv.settings", "com.amazon.tv.launcher/.ui.Settings"};

    /**
     * Android holds activity starts for 5 s after Home unless the caller has STOP_APP_SWITCHES. ShellHome
     * (running as the shell user, which has it) opens this screen without the hold; if it isn't running (it
     * has to be started over adb again after a reboot) the start here is held. Either way a snapshot of this
     * home screen covers Amazon's until the real one opens.
     */
    private static final long OVERLAY_MAX_MS = 7_000;
    private static Bitmap snapshot;
    private static HomeKeyService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable removeOverlay = this::removeOverlay;
    private Thread reader;
    private Process logcat;
    private volatile boolean running;
    private String lastFocused = "";
    private long lastShown;
    private View overlay;

    /** Called by MainActivity when it leaves the screen; the bitmap is what the overlay shows. */
    static void setSnapshot(Bitmap b) {
        if (snapshot != null && snapshot != b) snapshot.recycle();
        snapshot = b;
    }

    /** Called by MainActivity once it is on screen again. */
    static void homeVisible() {
        if (instance != null) instance.removeOverlay();
    }

    /** Called by MainActivity as soon as it resumes, before the overlay is removed. */
    static void homeResumed() {
        if (instance != null) instance.releaseOverlayInput();
    }

    static void start(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, HomeKeyService.class));
        } catch (Exception e) {
            Log.w(TAG, "could not start", e);
        }
    }

    static void showHome(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "could not open home", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (reader == null || !reader.isAlive()) {
            running = true;
            reader = new Thread(this::follow, "home-log");
            reader.start();
        }
        return START_STICKY;
    }

    private void follow() {
        while (running) {
            try {
                logcat = new ProcessBuilder("logcat", "-T", "1", "-b", "events", "-b", "system", "-v", "brief",
                        "am_focused_activity:I", "ContextImpl:W", "*:S").redirectErrorStream(true).start();
                BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
                String line;
                while (running && (line = in.readLine()) != null) onLine(line);
            } catch (Exception e) {
                Log.w(TAG, "log reader stopped", e);
            }
            if (logcat != null) logcat.destroy();
            if (running) SystemClock.sleep(5_000);
        }
    }

    private void onLine(String line) {
        if (line.contains("handleShortPressOnHome")) {
            lastFocused = "";
            show();
            return;
        }
        int at = line.indexOf("am_focused_activity");
        if (at < 0) return;
        int open = line.indexOf('[', at), comma = line.indexOf(',', open), end = line.indexOf(',', comma + 1);
        if (open < 0 || comma < 0 || end < 0) return;
        String activity = line.substring(comma + 1, end);
        String previous = lastFocused;
        lastFocused = activity;
        if (!AMAZON_HOME.equals(activity)) return;
        for (String p : SETTINGS_PREFIXES) if (previous.startsWith(p)) return;
        show();
    }

    private void show() {
        long now = SystemClock.uptimeMillis();
        if (now - lastShown < 1_000) return;
        lastShown = now;
        main.post(() -> {
            addOverlay();
            io.execute(() -> {
                if (!askHelper("home").equals("ok")) main.post(() -> showHome(this));
            });
        });
    }

    private static String askHelper(String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", ShellHome.PORT), 300);
            s.setSoTimeout(1_000);
            s.getOutputStream().write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            String r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return r == null ? "" : r;
        } catch (Exception e) {
            return "";
        }
    }

    private void addOverlay() {
        if (overlay != null || snapshot == null || snapshot.isRecycled()) return;
        ImageView v = new ImageView(this);
        v.setImageBitmap(snapshot);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        // Swallow the remote while Amazon's home is underneath, so presses can't open things there.
        v.setOnKeyListener((view, keyCode, event) -> true);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(v, lp);
            v.requestFocus();
            overlay = v;
            main.postDelayed(removeOverlay, OVERLAY_MAX_MS);
        } catch (Exception e) {
            Log.w(TAG, "overlay not allowed; grant SYSTEM_ALERT_WINDOW over adb", e);
        }
    }

    private void releaseOverlayInput() {
        if (overlay == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlay.getLayoutParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).updateViewLayout(overlay, lp);
        } catch (Exception ignored) {
        }
    }

    private void removeOverlay() {
        main.removeCallbacks(removeOverlay);
        if (overlay == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (logcat != null) logcat.destroy();
        io.shutdown();
        removeOverlay();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
