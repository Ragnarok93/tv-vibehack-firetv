package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Runs as the adb shell user (app_process, started by tools/fast-home.sh) and opens this
 * home screen on request. Android holds activity starts for 5 s after Home unless the caller has
 * STOP_APP_SWITCHES; the shell user has it, apps don't.
 *
 * Listens on 127.0.0.1 only. Any local app can connect, so the commands are limited to ones that are
 * harmless from anyone: bring this home screen forward, or report the version.
 */
public final class ShellHome {

    static final int PORT = 38_275;
    static final int VERSION = 1;

    private static Object activityManager;
    private static Method startActivity;

    public static void main(String[] args) {
        ServerSocket server;
        try {
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        } catch (Exception alreadyRunning) {
            return;
        }
        try {
            bindActivityManager();
        } catch (Exception e) {
            System.err.println("ShellHome: activity manager unavailable: " + e);
            return;
        }
        while (true) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(2_000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = s.getOutputStream();
                String cmd = in.readLine();
                if ("home".equals(cmd)) {
                    out.write((openHome() ? "ok\n" : "fail\n").getBytes(StandardCharsets.UTF_8));
                } else if ("ping".equals(cmd)) {
                    out.write(("pong " + VERSION + "\n").getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            } catch (Exception ignored) {
            }
        }
    }

    private static void bindActivityManager() throws Exception {
        try {
            activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        } catch (NoSuchMethodException preOreo) {
            activityManager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        }
        // startActivity(caller, callingPackage, intent, resolvedType, resultTo, resultWho, requestCode,
        //               flags, profilerInfo, options) on Android 7-9.
        for (Method m : activityManager.getClass().getMethods()) {
            if (m.getName().equals("startActivity") && m.getParameterTypes().length == 10) {
                startActivity = m;
                return;
            }
        }
        throw new NoSuchMethodException("IActivityManager.startActivity");
    }

    private static boolean openHome() {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName("com.gathin.tvhome", "com.gathin.tvhome.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            Object r = startActivity.invoke(activityManager, null, "com.android.shell", i, null, null, null, 0, 0, null, null);
            return r instanceof Integer && (Integer) r >= 0;
        } catch (Exception e) {
            System.err.println("ShellHome: start failed: " + e);
            return false;
        }
    }
}
