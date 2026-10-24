package com.gathin.tvhome;

import android.app.Activity;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Full-screen player for one TV input (HDMI, AV, antenna) through the standard TvView. Amazon's own
 * player needs an Amazon-only permission, so the launcher plays inputs itself. Back returns home.
 */
public class InputActivity extends Activity {

    static final String EXTRA_INPUT_ID = "inputId";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_PASSTHROUGH = "passthrough";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TvView tv;
    private TextView label;
    private String title;
    private final Runnable hideLabel = () -> {
        if (label != null) label.animate().alpha(0f).setDuration(400).start();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        tv = new TvView(this);
        root.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        label = new TextView(this);
        label.setTag("w300");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(34);
        label.setGravity(Gravity.CENTER);
        root.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Fonts.apply(label);
        setContentView(root);

        String id = getIntent().getStringExtra(EXTRA_INPUT_ID);
        title = getIntent().getStringExtra(EXTRA_LABEL);
        if (id == null) {
            finish();
            return;
        }
        label.setText(title);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String inputId) {
                label.setText(title);
                handler.postDelayed(hideLabel, 1500);
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                handler.removeCallbacks(hideLabel);
                label.animate().cancel();
                label.setAlpha(1f);
                label.setText(reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                        ? title : title + "\n" + getString(R.string.no_signal));
            }

            @Override
            public void onConnectionFailed(String inputId) {
                label.setText(title + "\n" + getString(R.string.input_failed));
            }

            @Override
            public void onDisconnected(String inputId) {
                finish();
            }
        });
        boolean passthrough = getIntent().getBooleanExtra(EXTRA_PASSTHROUGH, true);
        // TvView.tune() requires a URI for one channel. buildChannelsUriForInput() is a
        // collection URI, not a tuneable channel URI; passing it can make Fire OS's TV
        // input service throw IllegalArgumentException on the main thread ("channel").
        // This screen currently supports passthrough inputs (HDMI/AV). For tuner inputs,
        // leave the player in its no-signal state rather than crashing the launcher process.
        if (passthrough) {
            try {
                tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
            } catch (IllegalArgumentException | SecurityException e) {
                label.setText(title + "\\n" + getString(R.string.input_failed));
            }
        } else {
            label.setText(title + "\\n" + getString(R.string.input_failed));
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // TvView hands keys to the input first (HDMI-CEC); keep Back for leaving the player.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(hideLabel);
        if (tv != null) tv.reset();
        finish();
    }
}
