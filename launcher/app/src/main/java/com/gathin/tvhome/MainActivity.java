package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private static final long DEFAULT_SCREENSAVER_IDLE_MS = 10 * MINUTE_MS;
    private static final String PREFS = "launcher_settings";
    private static final String PREF_BACKGROUND = "aerial_background_enabled";
    private static final String PREF_SCREENSAVER = "aerial_screensaver_enabled";
    private static final String PREF_SCREENSAVER_DELAY = "screensaver_delay_minutes";
    private static final String PREF_INCLUDE_PHONE_APPS = "include_phone_apps";
    private static final String PREF_DOCK_APPS = "dock_apps";
    private static final String PREF_HIDDEN_APPS = "hidden_apps";
    private static final String PREF_LIVE_TV_PACKAGE = "live_tv_package";
    private static final String PREF_COLUMNS = "columns";
    private static final String PREF_TEXT_MODE = "home_text_color_mode";
    private static final String TEXT_MODE_ADAPTIVE = "adaptive";
    private static final String TEXT_MODE_SOLID_WHITE = "solid_white";
    private static final String AERIAL_VIEWS_PACKAGE = "com.neilturner.aerialviews";
    private static final String AERIAL_VIEWS_SCREENSAVER_ACTION = "com.gathin.tvhome.action.START_SCREENSAVER";
    private boolean launcherResumed;
    private final Runnable launchAerialViewsScreensaver = () -> {
        if (!launcherResumed || isFinishing() || !getPreferencesStore().getBoolean(PREF_SCREENSAVER, true)) return;
        try {
            getPackageManager().getApplicationInfo(AERIAL_VIEWS_PACKAGE, 0);
            Intent screensaver = new Intent(AERIAL_VIEWS_SCREENSAVER_ACTION)
                    .setClassName(AERIAL_VIEWS_PACKAGE, AERIAL_VIEWS_PACKAGE + ".ui.screensaver.TestActivity");
            startActivity(screensaver);
        } catch (PackageManager.NameNotFoundException | ActivityNotFoundException | SecurityException ignored) {
            // AerialViews is optional; the launcher must remain usable without it.
        }
    };
    private String liveTvPkg;
    private PopupWindow launcherSettingsPopup;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    private float density;
    private Bitmap snapshot;
    private PlayerView backgroundPlayerView;
    private TextureView backgroundTextureView;
    private ExoPlayer backgroundPlayer;
    private int overlayTextColor = Color.WHITE;
    private final Runnable adaptiveTextTick = new Runnable() {
        @Override
        public void run() {
            updateAdaptiveTextColor();
            if (backgroundPlayer != null && backgroundPlayer.isPlaying()) {
                handler.postDelayed(this, 1200L);
            }
        }
    };
    private final ExecutorService wallpaperExecutor = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dropHomeOverlay = HomeKeyService::homeVisible;
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getPreferencesStore().getInt(PREF_COLUMNS, getResources().getInteger(R.integer.columns)));
        liveTvPkg = getPreferencesStore().getString(PREF_LIVE_TV_PACKAGE, getString(R.string.live_tv_package)).trim();

        HomeKeyService.start(this);
        root = findViewById(R.id.root);
        backgroundPlayerView = findViewById(R.id.backgroundPlayerView);
        backgroundPlayerView.setUseController(false);
        backgroundPlayerView.setKeepContentOnPlayerReset(true);
        backgroundPlayerView.setFocusable(false);
        backgroundPlayerView.setFocusableInTouchMode(false);
        backgroundTextureView = findTextureView(backgroundPlayerView);
        backgroundPlayerView.setVisibility(View.GONE);
        Fonts.apply(root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);
        adapter.setOverlayTextColor(overlayTextColor);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        View btnLauncherSettings = findViewById(R.id.btnLauncherSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        btnLauncherSettings.setOnClickListener(v -> showLauncherSettings());

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnLauncherSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        launcherResumed = true;
        handler.removeCallbacks(launchAerialViewsScreensaver);
        handler.postDelayed(launchAerialViewsScreensaver, screensaverDelayMs());
        startBackgroundPlayback();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);
        // Keep the Home overlay up until the tiles and top shelf have faded back in, but let keys through now.
        HomeKeyService.homeResumed();
        handler.postDelayed(dropHomeOverlay, 700);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopBackgroundPlayback();
        launcherResumed = false;
        handler.removeCallbacks(launchAerialViewsScreensaver);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(dropHomeOverlay);
        if (root.getWidth() > 0 && root.getHeight() > 0) {
            if (snapshot == null || snapshot.getWidth() != root.getWidth() || snapshot.getHeight() != root.getHeight()) {
                snapshot = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            }
            root.draw(new Canvas(snapshot));
            HomeKeyService.setSnapshot(snapshot);
        }
    }

    private void startBackgroundPlayback() {
        if (backgroundPlayer != null || !getPreferencesStore().getBoolean(PREF_BACKGROUND, true)) return;
        ExoPlayer player = new ExoPlayer.Builder(this).build();
        backgroundPlayer = player;
        backgroundPlayerView.setPlayer(player);
        wallpaperExecutor.execute(() -> {
            List<MediaItem> items = new ArrayList<>();
            Uri catalogue = Uri.parse("content://com.neilturner.aerialviews.launcher.wallpapers/wallpapers");
            try (Cursor cursor = getContentResolver().query(catalogue, null, null, null, null)) {
                if (cursor != null) {
                    int uriColumn = cursor.getColumnIndex("uri");
                    int typeColumn = cursor.getColumnIndex("type");
                    while (cursor.moveToNext()) {
                        if (uriColumn < 0 || typeColumn < 0) break;
                        if ("VIDEO".equals(cursor.getString(typeColumn))) {
                            String mediaUri = cursor.getString(uriColumn);
                            if (mediaUri != null && !mediaUri.isEmpty()) items.add(MediaItem.fromUri(mediaUri));
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // AerialViews is optional and may have no configured or accessible media.
            }
            handler.post(() -> {
                if (!launcherResumed || backgroundPlayer != player || items.isEmpty()) {
                    if (backgroundPlayer == player) {
                        backgroundPlayer = null;
                        backgroundPlayerView.setPlayer(null);
                        player.release();
                    }
                    // If AerialViews has no playable videos, return to the regular launcher look.
                    if (launcherResumed && items.isEmpty() && heroShown) {
                        ambient.animate().cancel();
                        ambient.animate().alpha(1f).setDuration(300).start();
                    }
                    return;
                }
                player.setVolume(0f);
                player.setMediaItems(items);
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                player.setShuffleModeEnabled(true);
                player.prepare();
                player.play();
                backgroundPlayerView.setVisibility(View.VISIBLE);
                ambient.animate().cancel();
                ambient.setAlpha(0f);
                updateAdaptiveTextColor();
                handler.removeCallbacks(adaptiveTextTick);
                handler.postDelayed(adaptiveTextTick, 1200L);
            });
        });
    }

    private void stopBackgroundPlayback() {
        handler.removeCallbacks(adaptiveTextTick);
        backgroundPlayerView.setVisibility(View.GONE);
        backgroundPlayerView.setPlayer(null);
        if (backgroundPlayer != null) {
            backgroundPlayer.release();
            backgroundPlayer = null;
        }
        applyOverlayTextColor(Color.WHITE);
        // Preserve the clean AerialViews look while pausing for another activity. When the
        // user explicitly disables the video background, restore the focused-app glow.
        if (heroShown && !getPreferencesStore().getBoolean(PREF_BACKGROUND, true)) {
            ambient.animate().cancel();
            ambient.animate().alpha(1f).setDuration(300).start();
        }
    }

    private TextureView findTextureView(View view) {
        if (view instanceof TextureView) return (TextureView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextureView found = findTextureView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isAerialBackgroundActive() {
        return backgroundPlayer != null && backgroundPlayer.isPlaying()
                && backgroundPlayerView != null && backgroundPlayerView.getVisibility() == View.VISIBLE;
    }

    /**
     * Sample the video beneath each visible text element instead of averaging the entire frame.
     * Whole-frame averages are usually dominated by dark edges and can leave text white over a
     * bright scene. TextureView supplies the actual decoded frame without the launcher text on it.
     */
    private void updateAdaptiveTextColor() {
        SharedPreferences prefs = getPreferencesStore();
        if (!TEXT_MODE_ADAPTIVE.equals(prefs.getString(PREF_TEXT_MODE, TEXT_MODE_ADAPTIVE))
                || !isAerialBackgroundActive() || backgroundTextureView == null) {
            applyOverlayTextColor(Color.WHITE);
            return;
        }
        Bitmap frame = backgroundTextureView.getBitmap(96, 54);
        if (frame == null || root == null || backgroundTextureView.getWidth() <= 0
                || backgroundTextureView.getHeight() <= 0) {
            applyOverlayTextColor(Color.WHITE);
            if (frame != null) frame.recycle();
            return;
        }
        int[] videoLocation = new int[2];
        backgroundTextureView.getLocationOnScreen(videoLocation);
        updateAdaptiveTextColors(root, frame, videoLocation[0], videoLocation[1],
                backgroundTextureView.getWidth(), backgroundTextureView.getHeight());
        frame.recycle();
    }

    private void updateAdaptiveTextColors(View view, Bitmap frame, int videoX, int videoY,
            int videoWidth, int videoHeight) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getText() != null && text.getText().length() > 0) {
                int luminance = sampleTextBackground(frame, text, videoX, videoY, videoWidth, videoHeight);
                if (luminance >= 0) {
                    int current = text.getCurrentTextColor();
                    boolean currentlyLight = Color.red(current) > 180
                            && Color.green(current) > 180 && Color.blue(current) > 180;
                    int next = currentlyLight
                            ? (luminance >= 160 ? 0xFF111111 : Color.WHITE)
                            : (luminance <= 135 ? Color.WHITE : 0xFF111111);
                    int shadow = next == Color.WHITE ? 0xCC000000 : 0xCCFFFFFF;
                    text.setTextColor(next);
                    text.setShadowLayer(Math.max(1f, 2f * density), 0f,
                            Math.max(1f, density), shadow);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                updateAdaptiveTextColors(group.getChildAt(i), frame, videoX, videoY,
                        videoWidth, videoHeight);
            }
        }
    }

    /** Returns average background luminance under the text, or -1 when the region is unavailable. */
    private int sampleTextBackground(Bitmap frame, TextView text, int videoX, int videoY,
            int videoWidth, int videoHeight) {
        int[] location = new int[2];
        text.getLocationOnScreen(location);
        float left = location[0] - videoX;
        float top = location[1] - videoY;
        float right = left + text.getWidth();
        float bottom = top + text.getHeight();
        android.text.Layout layout = text.getLayout();
        if (layout != null && layout.getLineCount() > 0) {
            left += text.getCompoundPaddingLeft() + layout.getLineLeft(0);
            right = location[0] - videoX + text.getCompoundPaddingLeft() + layout.getLineRight(0);
            top += text.getCompoundPaddingTop() + layout.getLineTop(0);
            bottom = location[1] - videoY + text.getCompoundPaddingTop() + layout.getLineBottom(0);
        }
        float padX = Math.max(2f, (right - left) * 0.12f);
        float padY = Math.max(2f, (bottom - top) * 0.20f);
        int x0 = Math.max(0, (int) Math.floor((left - padX) * frame.getWidth() / videoWidth));
        int x1 = Math.min(frame.getWidth(), (int) Math.ceil((right + padX) * frame.getWidth() / videoWidth));
        int y0 = Math.max(0, (int) Math.floor((top - padY) * frame.getHeight() / videoHeight));
        int y1 = Math.min(frame.getHeight(), (int) Math.ceil((bottom + padY) * frame.getHeight() / videoHeight));
        if (x0 >= x1 || y0 >= y1) return -1;
        long total = 0L;
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int pixel = frame.getPixel(x, y);
                total += (int) (0.2126 * Color.red(pixel)
                        + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel));
                count++;
            }
        }
        return count == 0 ? -1 : (int) (total / count);
    }

    private void applyOverlayTextColor(int color) {
        boolean changed = overlayTextColor != color;
        overlayTextColor = color;
        applySolidTextColor(root, color);
        if (changed && adapter != null) adapter.setOverlayTextColor(color);
    }

    private void applySolidTextColor(View view, int color) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(color);
            text.setShadowLayer(Math.max(1f, 2f * density), 0f, Math.max(1f, density),
                    color == Color.WHITE ? 0xCC000000 : 0xCCFFFFFF);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applySolidTextColor(group.getChildAt(i), color);
        }
    }

    @Override
    protected void onDestroy() {
        stopBackgroundPlayback();
        wallpaperExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (launcherResumed) {
            handler.removeCallbacks(launchAerialViewsScreensaver);
            handler.postDelayed(launchAerialViewsScreensaver, screensaverDelayMs());
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            if (isAerialBackgroundActive()) {
                ambient.animate().cancel();
                ambient.setAlpha(0f);
            } else {
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (isAerialBackgroundActive()) {
                ambient.animate().cancel();
                ambient.setAlpha(0f);
            } else if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private SharedPreferences getPreferencesStore() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private long screensaverDelayMs() {
        int minutes = getPreferencesStore().getInt(PREF_SCREENSAVER_DELAY,
                (int) (DEFAULT_SCREENSAVER_IDLE_MS / MINUTE_MS));
        return Math.max(1, minutes) * MINUTE_MS;
    }

    /** TV-remote-friendly launcher configuration menu, separate from Android system Settings. */
    private void showLauncherSettings() {
        View anchor = findViewById(R.id.btnLauncherSettings);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) (6 * density), (int) (6 * density),
                (int) (6 * density), (int) (6 * density));
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setElevation(12 * density);
        launcherSettingsPopup = new PopupWindow(panel, (int) (340 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        launcherSettingsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        launcherSettingsPopup.setOutsideTouchable(true);
        launcherSettingsPopup.setAnimationStyle(0);
        launcherSettingsPopup.setElevation(0f);

        SharedPreferences prefs = getPreferencesStore();
        boolean background = prefs.getBoolean(PREF_BACKGROUND, true);
        boolean screensaver = prefs.getBoolean(PREF_SCREENSAVER, true);
        boolean phoneApps = prefs.getBoolean(PREF_INCLUDE_PHONE_APPS,
                getResources().getBoolean(R.bool.include_phone_launcher_apps));
        int delay = prefs.getInt(PREF_SCREENSAVER_DELAY,
                (int) (DEFAULT_SCREENSAVER_IDLE_MS / MINUTE_MS));
        int columns = prefs.getInt(PREF_COLUMNS, getResources().getInteger(R.integer.columns));
        boolean solidWhiteText = TEXT_MODE_SOLID_WHITE.equals(
                prefs.getString(PREF_TEXT_MODE, TEXT_MODE_ADAPTIVE));

        addLauncherSettingRow(panel, "AerialViews video background", background ? "On" : "Off",
                () -> {
                    boolean enabled = !background;
                    prefs.edit().putBoolean(PREF_BACKGROUND, enabled).apply();
                    if (enabled) startBackgroundPlayback(); else stopBackgroundPlayback();
                    refreshLauncherSettings();
                });
        addLauncherSettingRow(panel, "Home text color", solidWhiteText ? "Solid White" : "Adaptive",
                () -> {
                    String next = solidWhiteText ? TEXT_MODE_ADAPTIVE : TEXT_MODE_SOLID_WHITE;
                    prefs.edit().putString(PREF_TEXT_MODE, next).apply();
                    updateAdaptiveTextColor();
                    refreshLauncherSettings();
                });
        addLauncherSettingRow(panel, "AerialViews screensaver", screensaver ? "On" : "Off",
                () -> {
                    boolean enabled = !screensaver;
                    prefs.edit().putBoolean(PREF_SCREENSAVER, enabled).apply();
                    handler.removeCallbacks(launchAerialViewsScreensaver);
                    if (enabled && launcherResumed) handler.postDelayed(launchAerialViewsScreensaver, screensaverDelayMs());
                    refreshLauncherSettings();
                });
        addLauncherSettingRow(panel, "Screensaver idle delay", delay + " min  ›",
                () -> {
                    int[] values = new int[]{5, 10, 15, 30, 60};
                    int next = values[0];
                    for (int i = 0; i < values.length; i++) {
                        if (values[i] == delay) { next = values[(i + 1) % values.length]; break; }
                        if (i == values.length - 1) next = values[0];
                    }
                    prefs.edit().putInt(PREF_SCREENSAVER_DELAY, next).apply();
                    handler.removeCallbacks(launchAerialViewsScreensaver);
                    if (launcherResumed && prefs.getBoolean(PREF_SCREENSAVER, true))
                        handler.postDelayed(launchAerialViewsScreensaver, screensaverDelayMs());
                    refreshLauncherSettings();
                });
        addLauncherSettingRow(panel, "Include phone-only apps", phoneApps ? "On" : "Off",
                () -> {
                    prefs.edit().putBoolean(PREF_INCLUDE_PHONE_APPS, !phoneApps).apply();
                    adapter.reload();
                    refreshLauncherSettings();
                });
        addLauncherSettingRow(panel, "Grid columns", columns + "  ›", () -> {
            int[] values = new int[]{3, 4, 5, 6};
            int next = values[0];
            for (int i = 0; i < values.length; i++) {
                if (values[i] == columns) { next = values[(i + 1) % values.length]; break; }
                if (i == values.length - 1) next = values[0];
            }
            prefs.edit().putInt(PREF_COLUMNS, next).apply();
            COLUMNS = next;
            grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
            while (grid.getItemDecorationCount() > 0) grid.removeItemDecorationAt(0);
            grid.addItemDecoration(new DockDecoration(COLUMNS, density));
            adapter = new AppAdapter(this, this, COLUMNS);
            grid.setAdapter(adapter);
            adapter.reload();
            adapter.setOverlayTextColor(overlayTextColor);
            refreshLauncherSettings();
        });
        addLauncherSettingRow(panel, "Dock apps", "Choose  ›",
                () -> choosePackageList("Choose dock apps", PREF_DOCK_APPS, R.array.dock_apps));
        addLauncherSettingRow(panel, "Dock app order", "Reorder  ›",
                () -> chooseDockOrder());
        addLauncherSettingRow(panel, "Hidden apps", "Choose  ›",
                () -> choosePackageList("Choose hidden apps", PREF_HIDDEN_APPS, R.array.hidden_apps));
        addLauncherSettingRow(panel, "Live TV app", liveTvPkg.isEmpty() ? "Disabled" : "Choose  ›",
                () -> chooseLiveTvApp());

        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        launcherSettingsPopup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.END);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(180).start();
        if (panel.getChildCount() > 0) panel.getChildAt(0).requestFocus();
    }

    private void addLauncherSettingRow(LinearLayout panel, String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
        row.setMinimumHeight((int) (44 * density));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.input_row_bg);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(getColorCompat(0xFFFFFFFF));
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setTag("w500");
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, titleParams);

        TextView status = new TextView(this);
        status.setText(value);
        status.setTextColor(getColorCompat(0xB3FFFFFF));
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setTag("w400");
        row.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Fonts.apply(row);
        row.setOnFocusChangeListener((v, focused) -> {
            title.setTextColor(getColorCompat(focused ? 0xFF111111 : 0xFFFFFFFF));
            status.setTextColor(getColorCompat(focused ? 0xCC111111 : 0xB3FFFFFF));
        });
        row.setOnClickListener(v -> action.run());
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int getColorCompat(int color) {
        return color;
    }

    private void refreshLauncherSettings() {
        if (launcherSettingsPopup != null && launcherSettingsPopup.isShowing()) launcherSettingsPopup.dismiss();
        showLauncherSettings();
    }

    private static final class AppChoice {
        final String packageName;
        final String label;

        AppChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    /** Returns one selectable entry per installed app that exposes a TV or standard launcher activity. */
    private List<AppChoice> installedAppChoices() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));

        Set<String> seen = new HashSet<>();
        List<AppChoice> choices = new ArrayList<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (packageName.equals(getPackageName()) || !seen.add(packageName)) continue;
            CharSequence loadedLabel = info.loadLabel(pm);
            String label = loadedLabel == null ? packageName : loadedLabel.toString();
            choices.add(new AppChoice(packageName, label));
        }
        Collections.sort(choices, (left, right) -> {
            int byLabel = left.label.compareToIgnoreCase(right.label);
            return byLabel != 0 ? byLabel : left.packageName.compareTo(right.packageName);
        });
        return choices;
    }

    private List<String> parsePackages(String value) {
        List<String> packages = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return packages;
        for (String part : value.split(",")) {
            String packageName = part.trim();
            if (!packageName.isEmpty() && !packages.contains(packageName)) packages.add(packageName);
        }
        return packages;
    }

    private String joinPackages(String[] packages) {
        StringBuilder joined = new StringBuilder();
        for (String packageName : packages) {
            if (joined.length() > 0) joined.append(',');
            joined.append(packageName);
        }
        return joined.toString();
    }

    private List<String> dockOrderLabels(List<String> packages, List<AppChoice> apps) {
        List<String> labels = new ArrayList<>();
        for (String packageName : packages) {
            String label = packageName;
            for (AppChoice app : apps) {
                if (app.packageName.equals(packageName)) {
                    label = app.label;
                    break;
                }
            }
            labels.add(label);
        }
        return labels;
    }

    private TextView dockOrderAction(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding((int) (14 * density), (int) (8 * density),
                (int) (14 * density), (int) (8 * density));
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackgroundResource(R.drawable.input_row_bg);
        button.setOnFocusChangeListener((v, focused) ->
                button.setTextColor(focused ? 0xFF111111 : Color.WHITE));
        return button;
    }

    /** Remote-friendly reorder dialog; the stored package list is the dock's persistent order. */
    private void chooseDockOrder() {
        if (launcherSettingsPopup != null) launcherSettingsPopup.dismiss();
        SharedPreferences prefs = getPreferencesStore();
        List<String> order = parsePackages(prefs.getString(PREF_DOCK_APPS,
                joinPackages(getResources().getStringArray(R.array.dock_apps))));
        if (order.isEmpty()) {
            Toast.makeText(this, "Choose dock apps first", Toast.LENGTH_SHORT).show();
            showLauncherSettings();
            return;
        }
        List<AppChoice> apps = installedAppChoices();
        List<String> labels = dockOrderLabels(order, apps);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (8 * density), (int) (4 * density),
                (int) (8 * density), (int) (4 * density));
        TextView hint = new TextView(this);
        hint.setText("Select an app, then move it up or down.");
        hint.setTextColor(0xFFB3B3B3);
        hint.setPadding((int) (8 * density), (int) (4 * density),
                (int) (8 * density), (int) (8 * density));
        content.addView(hint);

        ListView list = new ListView(this);
        list.setId(View.generateViewId());
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        ArrayAdapter<String> orderAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_activated_1, new ArrayList<>(labels));
        list.setAdapter(orderAdapter);
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (250 * density)));
        final int[] selectedIndex = {0};
        list.setItemChecked(0, true);
        list.setSelection(0);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex[0] = position;
            list.setItemChecked(position, true);
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        TextView moveUp = dockOrderAction("Move up ↑");
        moveUp.setId(View.generateViewId());
        TextView moveDown = dockOrderAction("Move down ↓");
        moveDown.setId(View.generateViewId());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actionParams.setMargins((int) (4 * density), (int) (6 * density),
                (int) (4 * density), (int) (2 * density));
        actions.addView(moveUp, actionParams);
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        downParams.setMargins((int) (4 * density), (int) (6 * density),
                (int) (4 * density), (int) (2 * density));
        actions.addView(moveDown, downParams);
        content.addView(actions);
        list.setNextFocusDownId(moveUp.getId());
        moveUp.setNextFocusRightId(moveDown.getId());
        moveUp.setNextFocusUpId(list.getId());
        moveDown.setNextFocusUpId(moveUp.getId());

        Runnable refreshOrder = () -> {
            orderAdapter.clear();
            orderAdapter.addAll(dockOrderLabels(order, apps));
            orderAdapter.notifyDataSetChanged();
            if (!order.isEmpty()) {
                selectedIndex[0] = Math.max(0, Math.min(selectedIndex[0], order.size() - 1));
                list.setItemChecked(selectedIndex[0], true);
                list.setSelection(selectedIndex[0]);
            }
        };
        moveUp.setOnClickListener(v -> {
            int index = selectedIndex[0];
            if (index <= 0) return;
            Collections.swap(order, index, index - 1);
            selectedIndex[0] = index - 1;
            refreshOrder.run();
        });
        moveDown.setOnClickListener(v -> {
            int index = selectedIndex[0];
            if (index >= order.size() - 1) return;
            Collections.swap(order, index, index + 1);
            selectedIndex[0] = index + 1;
            refreshOrder.run();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Dock app order")
                .setView(content)
                .setNegativeButton(android.R.string.cancel, (d, which) -> showLauncherSettings())
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    prefs.edit().putString(PREF_DOCK_APPS,
                            joinPackages(order.toArray(new String[0]))).apply();
                    adapter.reload();
                    dialog.dismiss();
                    showLauncherSettings();
                }));
        dialog.show();
        list.requestFocus();
    }

    /** Multi-select app picker for the dock and hidden-app list; no package-name typing required. */
    private void choosePackageList(String title, String key, int defaultArray) {
        if (launcherSettingsPopup != null) launcherSettingsPopup.dismiss();
        SharedPreferences prefs = getPreferencesStore();
        String currentValue = prefs.getString(key, joinPackages(getResources().getStringArray(defaultArray)));
        List<String> currentOrder = parsePackages(currentValue);
        Set<String> selected = new HashSet<>(currentOrder);
        List<AppChoice> apps = installedAppChoices();
        CharSequence[] labels = new CharSequence[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppChoice app = apps.get(i);
            labels[i] = app.label;
            checked[i] = selected.contains(app.packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    String packageName = apps.get(which).packageName;
                    if (isChecked) selected.add(packageName);
                    else selected.remove(packageName);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showLauncherSettings())
                .setNeutralButton("Reset", (dialog, which) -> {
                    prefs.edit().remove(key).apply();
                    adapter.reload();
                    showLauncherSettings();
                })
                .setPositiveButton("Save", (dialog, which) -> {
                    // Preserve the user's existing dock order; append newly selected apps alphabetically.
                    List<String> ordered = new ArrayList<>();
                    for (String packageName : currentOrder) {
                        if (selected.contains(packageName)) ordered.add(packageName);
                    }
                    for (AppChoice app : apps) {
                        if (selected.contains(app.packageName) && !ordered.contains(app.packageName)) {
                            ordered.add(app.packageName);
                        }
                    }
                    prefs.edit().putString(key, joinPackages(ordered.toArray(new String[0]))).apply();
                    adapter.reload();
                    showLauncherSettings();
                })
                .show();
    }

    /** Single-select app picker for the Live TV shortcut, with an explicit disable option. */
    private void chooseLiveTvApp() {
        if (launcherSettingsPopup != null) launcherSettingsPopup.dismiss();
        List<AppChoice> apps = installedAppChoices();
        CharSequence[] labels = new CharSequence[apps.size() + 1];
        labels[0] = "Disabled";
        int selectedIndex = 0;
        for (int i = 0; i < apps.size(); i++) {
            AppChoice app = apps.get(i);
            labels[i + 1] = app.label;
            if (app.packageName.equals(liveTvPkg)) selectedIndex = i + 1;
        }
        final int[] selected = {selectedIndex};
        new AlertDialog.Builder(this)
                .setTitle("Choose Live TV app")
                .setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> selected[0] = which)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showLauncherSettings())
                .setNeutralButton("Reset", (dialog, which) -> {
                    liveTvPkg = getString(R.string.live_tv_package).trim();
                    getPreferencesStore().edit().remove(PREF_LIVE_TV_PACKAGE).apply();
                    showLauncherSettings();
                })
                .setPositiveButton("Save", (dialog, which) -> {
                    liveTvPkg = selected[0] == 0 ? "" : apps.get(selected[0] - 1).packageName;
                    getPreferencesStore().edit().putString(PREF_LIVE_TV_PACKAGE, liveTvPkg).apply();
                    showLauncherSettings();
                })
                .show();
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (200 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // The built-in tuner comes from the same service as the HDMI ports; streaming apps' TIF inputs don't.
            Set<String> hardwarePkgs = new HashSet<>();
            for (TvInputInfo info : inputs) {
                if (info.isPassthroughInput()) hardwarePkgs.add(info.getServiceInfo().packageName);
            }
            TvInputInfo tuner = null;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) {
                    if (tuner == null && info.getType() == TvInputInfo.TYPE_TUNER
                            && hardwarePkgs.contains(info.getServiceInfo().packageName)) tuner = info;
                    continue;
                }
                CharSequence l = info.loadCustomLabel(this);
                if (l == null || l.length() == 0) l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                label = label.replaceFirst("(?i)^HDMI\\s*(\\d)", "HDMI $1");
                if (info.getType() == TvInputInfo.TYPE_COMPOSITE) label = "AV";
                final String id = info.getId();
                final String name = label;
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(tuneIntent(id, name, true)));
            }
            if (tuner != null) {
                final String id = tuner.getId();
                if (rows.getChildCount() > 0) addSeparator(rows);
                addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1,
                        () -> open(tuneIntent(id, getString(R.string.live_tv), false)));
            }
        }
        Intent liveTv = LiveTvActivity.launchIntent(this, liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            String appLabel;
            try {
                appLabel = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(liveTvPkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                appLabel = getString(R.string.live_tv);
            }
            addInputRow(rows, popup, R.drawable.ic_livetv, appLabel, -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    /**
     * Plays the input in InputActivity. Passthrough inputs go through the TV's own viewer when one handles
     * the standard channel URI; Fire OS has none for third-party apps (its player needs an Amazon permission).
     */
    private Intent tuneIntent(String inputId, String label, boolean passthrough) {
        if (passthrough) {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                    TvContract.buildChannelUriForPassthroughInput(inputId), "vnd.android.cursor.item/channel");
            if (view.resolveActivity(getPackageManager()) != null) return view;
        }
        return new Intent(this, InputActivity.class)
                .putExtra(InputActivity.EXTRA_INPUT_ID, inputId)
                .putExtra(InputActivity.EXTRA_LABEL, label)
                .putExtra(InputActivity.EXTRA_PASSTHROUGH, passthrough);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        Fonts.apply(row);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
