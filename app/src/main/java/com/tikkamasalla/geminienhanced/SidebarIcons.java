package com.tikkamasalla.geminienhanced;

import android.content.Context;
import android.graphics.drawable.Drawable;

// Vector icons of the native sidebar (res/drawable/sb_*.xml), tinted per use.
final class SidebarIcons {
    static final int SEARCH = R.drawable.sb_search, CLOSE = R.drawable.sb_close, SETTINGS = R.drawable.sb_settings,
            NEW = R.drawable.sb_new, PIN = R.drawable.sb_pin, LIBRARY = R.drawable.sb_library,
            IMAGES = R.drawable.sb_images, SCHEDULED = R.drawable.sb_scheduled, STUDENTS = R.drawable.sb_students,
            DOT = R.drawable.sb_dot, SPARKLE = R.drawable.sb_sparkle;

    private SidebarIcons() {
    }

    static Drawable get(Context c, int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    static int forNav(String key, String href) {
        String k = (key + " " + href).toLowerCase();
        if (k.contains("new") || "/app".equals(href)) return NEW;
        if (k.contains("library")) return LIBRARY;
        if (k.contains("image")) return IMAGES;
        if (k.contains("schedul")) return SCHEDULED;
        if (k.contains("student") || k.contains("learn")) return STUDENTS;
        return DOT;
    }
}
