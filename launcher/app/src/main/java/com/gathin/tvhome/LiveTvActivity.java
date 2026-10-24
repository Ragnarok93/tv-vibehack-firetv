package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Receives the remote's TV button. Fire OS turns that key into a livetv:// link meant for Amazon's
 * Live TV app; with that app disabled this is the only handler, and it opens the configured app.
 */
public class LiveTvActivity extends Activity {

    static Intent launchIntent(Context context, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        Intent i = context.getPackageManager().getLeanbackLaunchIntentForPackage(pkg.trim());
        if (i == null) i = context.getPackageManager().getLaunchIntentForPackage(pkg.trim());
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Use the same saved selection as the Inputs menu. Falling back to MainActivity here
        // made the physical TV button appear to refresh the home screen when the default app
        // was absent or the user had selected a different app.
        String packageName = getSharedPreferences("launcher_settings", MODE_PRIVATE)
                .getString("live_tv_package", getString(R.string.live_tv_package)).trim();
        Intent target = launchIntent(this, packageName);
        if (target != null) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(target);
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // No-op rather than bouncing back to the launcher for a stale app selection.
            }
        }
        finish();
    }
}
