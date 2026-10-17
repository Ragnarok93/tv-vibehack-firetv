package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Outfit in three static weights from assets/fonts. Font resources and variable weights need Android 8,
 * so each TextView carries its weight as a tag ("w300", "w400", "w500") and gets its typeface here.
 */
final class Fonts {
    private static Typeface light, regular, medium;

    private Fonts() {
    }

    static void apply(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setTypeface(forTag(v.getContext(), v.getTag()));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    private static Typeface forTag(Context ctx, Object tag) {
        if (light == null) {
            light = Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit-Light.ttf");
            regular = Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit-Regular.ttf");
            medium = Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit-Medium.ttf");
        }
        if ("w300".equals(tag)) return light;
        if ("w500".equals(tag)) return medium;
        return regular;
    }
}
