package com.tikkamasalla.geminienhanced;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

// Page -> Android bridge of the native sidebar (window.GESidebar, added once per
// process WebView). Methods run on the JavaBridge thread; all UI work is posted. The
// page only ever sends data here; it never waits for an answer.
public final class SidebarBridge {
    private static final String TAG = "GeminiEnhanced";
    public static final String NAME = "GESidebar";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile NativeSidebar current = null;

    static void attach(NativeSidebar s) {
        current = s;
    }

    static void detach(NativeSidebar s) {
        if (current == s) current = null;
    }

    static void js(WebView w, String code) {
        try {
            if (w != null) w.evaluateJavascript(code, null);
        } catch (Exception e) {
            Log.w(TAG, "sidebar js failed", e);
        }
    }

    private interface Job {
        void run(NativeSidebar s);
    }

    private static void post(final Job j) {
        UI.post(new Runnable() {
            @Override
            public void run() {
                NativeSidebar s = current;
                if (s == null) return;
                try {
                    j.run(s);
                } catch (Exception e) {
                    Log.w(TAG, "sidebar bridge failed", e);
                }
            }
        });
    }

    // {"chats":[{"id":"c_..","t":"title","p":true}]}
    @JavascriptInterface
    public void onList(final String json) {
        post(new Job() {
            @Override
            public void run(NativeSidebar s) {
                s.onPageList(true, json);
            }
        });
    }

    @JavascriptInterface
    public void onListError(final String why) {
        post(new Job() {
            @Override
            public void run(NativeSidebar s) {
                s.onPageList(false, why);
            }
        });
    }

    @JavascriptInterface
    public void onUrl(final String path) {
        post(new Job() {
            @Override
            public void run(NativeSidebar s) {
                s.onPageUrl(path);
            }
        });
    }

    // {"nav":[{label,href,key}],"name":"..","plan":".."}
    @JavascriptInterface
    public void onNav(final String json) {
        post(new Job() {
            @Override
            public void run(NativeSidebar s) {
                s.onPageNav(json);
            }
        });
    }

    // {"dpr":3.475,"all":[l,t,r,b,...],"right":[...]} in css px of the viewport.
    // Stored straight away (no UI hop): the next touch-down reads it.
    @JavascriptInterface
    public void onRegions(String json) {
        try {
            NativeSidebar s = current;
            if (s == null) return;
            JSONObject o = new JSONObject(json);
            DrawerWebContainer c = s.containerOrNull();
            if (c != null) c.setPageRegions((float) o.optDouble("dpr", 0), rects(o.optJSONArray("all")), rects(o.optJSONArray("right")));
        } catch (Exception e) {
            Log.w(TAG, "sidebar regions failed", e);
        }
    }

    private static float[] rects(JSONArray a) {
        if (a == null) return new float[0];
        float[] r = new float[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = (float) a.optDouble(i, 0);
        return r;
    }

    // The page's own menu button was tapped: open the native sidebar instead.
    @JavascriptInterface
    public void openNative() {
        post(new Job() {
            @Override
            public void run(NativeSidebar s) {
                s.openFromPage();
            }
        });
    }

    // Whether the native sidebar is in use (false = fallback to the page's own sidebar).
    @JavascriptInterface
    public boolean nativeEnabled() {
        NativeSidebar s = current;
        return s != null && s.isEnabledForSwipe();
    }

    @JavascriptInterface
    public void onReady() {
        post(new Job() {
            @Override
            public void run(NativeSidebar s) {
                s.onPageReady();
            }
        });
    }
}
