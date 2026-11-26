package com.tikkamasalla.geminienhanced;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

// Holds the chat WebView for the whole process lifetime so a Gemini reply
// keeps streaming while the user switches apps, turns the screen off, or
// swipes the app away from Recents.
// The WebView is created once per process with a MutableContextWrapper
// around the application context; the Activity sets the wrapper's base
// context to itself while shown (so dialogs and the file chooser keep
// working) and the Activity hands it back to the application context when
// it goes away.
//
// While a reply is pending or being written (ReplyKeeperService.needsKeep()) and no
// Activity is showing the WebView, the WebView is hosted off-screen: a
// private VirtualDisplay fed by an ImageReader keeps the page rendering, so
// the stop-button check sees the reply finish. The virtual display is never
// kept alive when not busy.
public final class ChatHolder {
    private static final String TAG = "GeminiEnhanced";

    private static WebView webView = null;
    private static MutableContextWrapper wrapper = null;

    // Off-screen hosting state. Non-null presentation means hosted.
    private static Presentation offscreenPresentation = null;
    private static VirtualDisplay offscreenDisplay = null;
    private static ImageReader offscreenReader = null;
    private static HandlerThread offscreenThread = null;

    private ChatHolder() {
    }

    public static synchronized boolean hasWebView() {
        return webView != null;
    }

    // Returns the process WebView, creating it on first call. One-time
    // WebView setup only; per-Activity clients are (re)set by MainActivity.
    public static synchronized WebView getOrCreate(Context appContext) {
        if (webView == null) {
            Context app = appContext.getApplicationContext();
            wrapper = new MutableContextWrapper(app);
            // Remote inspection only in debug builds.
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
            webView = new WebView(wrapper);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
        }
        return webView;
    }

    public static synchronized WebView get() {
        return webView;
    }

    // The renderer died: this WebView can never draw again. Drop it (and any
    // off-screen hosting) so the next getOrCreate builds a fresh one.
    public static synchronized void dropDead(WebView dead, Context appContext) {
        if (dead != webView) {
            return;
        }
        releaseOffscreenLocked();
        try {
            if (dead.getParent() instanceof ViewGroup) {
                ((ViewGroup) dead.getParent()).removeView(dead);
            }
            wrapper.setBaseContext(appContext.getApplicationContext());
            dead.destroy();
        } catch (Exception e) {
            Log.w(TAG, "dead WebView cleanup failed", e);
        }
        webView = null;
        wrapper = null;
    }

    // A reply became pending while no Activity shows the page: host it
    // off-screen now (from any thread). Call only when no Activity is visible.
    public static void hostOffscreenSoon(final Context appContext) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!MainActivity.isForeground()) {
                    maybeHostOffscreen(appContext);
                }
            }
        });
    }

    // While an Activity is shown, WebView-created UI (dialogs, file
    // chooser, permission popups) uses it as its context.
    public static synchronized void attachActivity(Activity activity) {
        if (wrapper != null) {
            wrapper.setBaseContext(activity);
        }
    }

    // When the Activity goes away the WebView must not keep it alive.
    // Ignored while hosted off-screen: the wrapper base must stay the
    // Presentation's context until the WebView is taken back.
    public static synchronized void detachActivity(Context appContext) {
        if (offscreenPresentation != null) {
            return;
        }
        if (wrapper != null) {
            wrapper.setBaseContext(appContext.getApplicationContext());
        }
    }

    public static synchronized boolean isOffscreen() {
        return offscreenPresentation != null;
    }

    // Host the WebView off-screen so the page keeps rendering while no
    // Activity shows it. Call from Activity.onStop/onDestroy (UI thread) when
    // detaching. No-op unless a reply is streaming and hosting is not already
    // active. Never throws: any failure is logged and leaves the WebView
    // where it was.
    public static synchronized void maybeHostOffscreen(Context appContext) {
        if (webView == null || wrapper == null) {
            return;
        }
        if (!ReplyKeeperService.needsKeep()) {
            return;
        }
        if (offscreenPresentation != null) {
            return;
        }
        Context app = appContext.getApplicationContext();
        int width = webView.getWidth();
        int height = webView.getHeight();
        int densityDpi = 0;
        try {
            DisplayMetrics metrics = app.getResources().getDisplayMetrics();
            if (width <= 0) {
                width = metrics.widthPixels;
            }
            if (height <= 0) {
                height = metrics.heightPixels;
            }
            densityDpi = metrics.densityDpi;
        } catch (Exception e) {
            Log.w(TAG, "offscreen metrics failed, continuing without off-screen rendering", e);
            return;
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            Log.w(TAG, "offscreen size unknown (" + width + "x" + height + "/" + densityDpi
                    + "), continuing without off-screen rendering");
            return;
        }
        HandlerThread thread = null;
        ImageReader reader = null;
        VirtualDisplay display = null;
        Presentation presentation = null;
        try {
            thread = new HandlerThread("GeminiEnhancedFrames");
            thread.start();
            Handler background = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    // Drop frames immediately so the producer never blocks;
                    // the pixels are only needed to keep the page rendering.
                    Image image = null;
                    try {
                        image = r.acquireLatestImage();
                    } catch (Exception e) {
                        Log.w(TAG, "offscreen frame acquire failed", e);
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            }, background);
            DisplayManager displays =
                    (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (displays == null) {
                throw new IllegalStateException("no DisplayManager");
            }
            display = displays.createVirtualDisplay(
                    "GeminiEnhanced", width, height, densityDpi, reader.getSurface(), 0);
            if (display == null || display.getDisplay() == null) {
                throw new IllegalStateException("VirtualDisplay not created");
            }
            presentation = new Presentation(app, display.getDisplay());
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            wrapper.setBaseContext(presentation.getContext());
            FrameLayout container = new FrameLayout(presentation.getContext());
            container.addView(webView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
            presentation.setContentView(container,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            presentation.show();
            // Only published after show() succeeded.
            offscreenThread = thread;
            offscreenReader = reader;
            offscreenDisplay = display;
            offscreenPresentation = presentation;
            Log.d(TAG, "offscreen hosting started (" + width + "x" + height + "/" + densityDpi + ")");
        } catch (Exception e) {
            Log.w(TAG, "offscreen hosting failed, continuing without off-screen rendering", e);
            try {
                if (webView.getParent() == null) {
                    wrapper.setBaseContext(app);
                }
            } catch (Exception e2) {
                Log.w(TAG, "offscreen context restore failed", e2);
            }
            if (presentation != null) {
                try {
                    presentation.dismiss();
                } catch (Exception e2) {
                    Log.w(TAG, "offscreen presentation dismiss failed", e2);
                }
            }
            if (display != null) {
                try {
                    display.release();
                } catch (Exception e2) {
                    Log.w(TAG, "offscreen display release failed", e2);
                }
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e2) {
                    Log.w(TAG, "offscreen reader close failed", e2);
                }
            }
            if (thread != null) {
                try {
                    thread.quitSafely();
                } catch (Exception e2) {
                    Log.w(TAG, "offscreen thread quit failed", e2);
                }
            }
        }
    }

    // Take the WebView back under the given context (the Activity when it
    // re-attaches, else the application context). Removes it from the
    // Presentation, dismisses the Presentation, and releases the
    // VirtualDisplay and ImageReader. No-op when not hosted off-screen.
    // Callers re-attach the returned WebView to the layout themselves.
    public static synchronized void takeBackTo(Context newBase) {
        if (offscreenPresentation == null) {
            return;
        }
        try {
            if (webView != null && webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
        } catch (Exception e) {
            Log.w(TAG, "offscreen take-back remove failed", e);
        }
        try {
            if (wrapper != null) {
                wrapper.setBaseContext(newBase);
            }
        } catch (Exception e) {
            Log.w(TAG, "offscreen take-back context failed", e);
        }
        releaseOffscreenLocked();
    }

    // Drop off-screen hosting and hand the WebView back to the application
    // context (used when busy becomes false or the busy cap fires). No-op
    // when not hosted off-screen.
    public static synchronized void releaseToApp(Context appContext) {
        if (offscreenPresentation == null) {
            return;
        }
        takeBackTo(appContext.getApplicationContext());
    }

    private static void releaseOffscreenLocked() {
        if (offscreenPresentation != null) {
            try {
                offscreenPresentation.dismiss();
            } catch (Exception e) {
                Log.w(TAG, "offscreen presentation dismiss failed", e);
            }
            offscreenPresentation = null;
        }
        if (offscreenDisplay != null) {
            try {
                offscreenDisplay.release();
            } catch (Exception e) {
                Log.w(TAG, "offscreen display release failed", e);
            }
            offscreenDisplay = null;
        }
        if (offscreenReader != null) {
            try {
                offscreenReader.close();
            } catch (Exception e) {
                Log.w(TAG, "offscreen reader close failed", e);
            }
            offscreenReader = null;
        }
        if (offscreenThread != null) {
            try {
                offscreenThread.quitSafely();
            } catch (Exception e) {
                Log.w(TAG, "offscreen thread quit failed", e);
            }
            offscreenThread = null;
        }
    }
}
