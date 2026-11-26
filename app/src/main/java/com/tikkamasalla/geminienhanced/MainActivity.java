/*
Copyright (c) 2017-2019 Divested Computing Group
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package com.tikkamasalla.geminienhanced;

import static android.webkit.WebView.HitTestResult.IMAGE_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;

import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;
import android.webkit.ValueCallback;
import android.net.Uri;

import androidx.webkit.URLUtilCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView chatWebView = null;
    private DrawerWebContainer webContainer = null;
    private NativeSidebar sidebar = null;  // native sidebar (drawer wiring)
    private ImageButton restrictedButton = null;
    private WebSettings chatWebSettings = null;
    private CookieManager chatCookieManager = null;
    private final Context context = this;
    private static final String TAG = "GeminiEnhanced";
    private String urlToLoad = "https://gemini.google.com/";
    // The started (visible) instance, if any. An instance only clears its
    // own entry, so a late onStop of an older instance cannot hide a newer one.
    private static volatile MainActivity visibleActivity = null;
    //Last opened chat, for reopening it on a cold start.
    private static final String PREF_LAST_CHAT_URL = "lastChatUrl";
    private static final Pattern CHAT_URL_PATTERN =
            Pattern.compile("^https://gemini\\.google\\.com/app/[A-Za-z0-9_-]+");
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 102;
    private static boolean restricted = true;
    private boolean buttonDragging = false;  //the user is moving the button, do not snap it back on layout

    private static final ArrayList<String> allowedDomains = new ArrayList<String>();

    private ValueCallback<Uri[]> mUploadMessage;
    private final static int FILE_CHOOSER_REQUEST_CODE = 1;
    private final static int STORAGE_PERMISSION_REQUEST_CODE = 101;

    private static final String GEMINI_ORIGIN = "https://gemini.google.com";
    private static final String DOWNLOAD_JS_OBJECT = "geminiAssistDownload";

    private android.webkit.PermissionRequest pendingPermissionRequest;  //waits for the RECORD_AUDIO runtime permission
    private String[] pendingBlobDownload;  //url, filename, mimetype waiting for the storage permission

    @Override
    protected void onPause() {
        if (chatCookieManager!=null) chatCookieManager.flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (restricted) restrictedButton.setImageDrawable(getDrawable(R.drawable.restricted));
        else restrictedButton.setImageDrawable(getDrawable(R.drawable.unrestricted));

        restrictedButton.setOnClickListener(v -> {
            restricted = !restricted;
            PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("restricted", restricted).apply();
            if (restricted) {
                restrictedButton.setImageDrawable(getDrawable(R.drawable.restricted));
                Toast.makeText(context,R.string.urls_restricted,Toast.LENGTH_SHORT).show();
            }
            else {
                restrictedButton.setImageDrawable(getDrawable(R.drawable.unrestricted));
                Toast.makeText(context,R.string.all_urls,Toast.LENGTH_SHORT).show();
            }
            chatWebView.reload();
        });

        restrictedButton.setOnLongClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.reset_chat)
                    .setMessage(R.string.reset_chat_confirm)
                    .setPositiveButton(R.string.dialog_OK_button, (dialog, which) -> {
                        resetChat();
                        Toast.makeText(context, R.string.reset_chat_done, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.dialog_NO_button, null)
                    .show();
            return true;
        });

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Remember the last mode and whether the button was hidden; a fresh install starts restricted
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        restricted = true; // always Gemini-only (the toggle button is hidden: it covered the menu and a long press reset the chat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webContainer = findViewById(R.id.webContainer);
        restrictedButton = findViewById(R.id.restricted);
        restrictedButton.setVisibility(View.GONE);
        makeButtonDraggable();
        if (prefs.getBoolean("buttonHidden", false)) restrictedButton.setVisibility(View.GONE);

        //Restrict what gets loaded (the allowed-domain list itself is unchanged)
        initURLs();

        //The chat WebView lives for the whole process (see ChatHolder) so a
        //reply keeps streaming without the Activity. Re-attach it if it
        //already exists and do NOT reload; otherwise create and load it.
        boolean firstCreation = !ChatHolder.hasWebView();
        chatWebView = ChatHolder.getOrCreate(this);
        ChatHolder.attachActivity(this);
        // If the reply was streaming off-screen, take the WebView back now;
        // the teardown below re-attaches it to the layout.
        ChatHolder.takeBackTo(this);
        if (chatWebView.getParent() instanceof ViewGroup) {
            ((ViewGroup) chatWebView.getParent()).removeView(chatWebView);
        }
        webContainer.addView(chatWebView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        // ---- drawer wiring: the native sidebar lives above the WebView in
        // webContainer, which keeps it on top whenever the WebView is re-attached.
        try {
            sidebar = new NativeSidebar(this);
            webContainer.setSidebar(sidebar);
        } catch (Exception e) {
            Log.w(TAG, "[sidebar] setup failed", e);
        }
        // ---- end drawer wiring
        registerForContextMenu(chatWebView);

        //Set cookie options
        chatCookieManager = CookieManager.getInstance();
        chatCookieManager.setAcceptCookie(true);
        chatCookieManager.setAcceptThirdPartyCookies(chatWebView, false);
        chatWebSettings = chatWebView.getSettings();

        attachWebViewClients();
        if (firstCreation) {
            initProcessWebView();
            //Cold start: reopen the last chat, fall back to the start page.
            chatWebView.loadUrl(startUrl(prefs));
        }
        //else: a live WebView is already showing the chat; do NOT reload.

        askForNotificationsOnce(prefs);
    }

    // (Re)set on every attach: these touch the current Activity, so they
    // must not outlive it on the process-lifetime WebView.
    private void attachWebViewClients() {
        chatWebView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage.message().contains("NotAllowedError: Write permission denied.")) {  //this error occurs when user copies to clipboard
                    Toast.makeText(context, R.string.error_copy,Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                //ACTION_GET_CONTENT grants access to the picked file, no storage permission needed
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }

                mUploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (request.getResources().length > 0 && request.getResources()[0].equals(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            // Keep the request and answer it in onRequestPermissionsResult, otherwise the first recording never starts
                            if (pendingPermissionRequest != null) pendingPermissionRequest.deny();
                            pendingPermissionRequest = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 123);
                        }
                    } else {
                        request.deny();
                    }
                } else {
                    request.grant(request.getResources()); // For older Android versions, permissions are granted at install time
                }
            }

        });  //needed to share link

        chatWebView.setWebViewClient(new WebViewClient() {
            //Keep these in sync!
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (!restricted) return null;

                if (request.getUrl().toString().equals("about:blank")) {
                    return null;
                }
                if (isAllowedBlob(request.getUrl().toString())) {
                    return null;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked a " + request.getUrl().getScheme() + " request");
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs that aren't HTTPS
                }
                if (!isAllowed(request.getUrl().getHost()) && !isSandboxCdnRequest(request)) {
                    Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs not on ALLOWLIST
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!restricted) return false;

                if (request.getUrl().toString().equals("about:blank")) {
                    return false;
                }
                if (isAllowedBlob(request.getUrl().toString())) {
                    return false;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    if (isLinkTap(request)) return openInBrowser(request.getUrl());
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked a " + request.getUrl().getScheme() + " link");
                    return true; //Deny URLs that aren't HTTPS
                }
                if (!isAllowed(request.getUrl().getHost())) {
                    if (isLinkTap(request)) return openInBrowser(request.getUrl());
                    Log.d(TAG, "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    return true; //Deny URLs not on ALLOWLIST
                }
                return false;
            }

            //WebResourceError is API 23+, and the framework only calls this overload on API 23+
            @TargetApi(Build.VERSION_CODES.M)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.w(TAG, "[onReceivedError] " + error.getErrorCode() + ": " + error.getDescription() + " @ " + request.getUrl().getHost());
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Busy ("Gemini is replying") detection: DOM stop-button
                // check (MutationObserver + 500 ms interval + 2 s debounce,
                // plus a 10 s heartbeat).
                view.evaluateJavascript(ReplyWatch.buildInjectorJs(), null);
                // ---- drawer wiring: sidebar.js feeds the native sidebar
                // (installed once per page, guard flag).
                injectSidebarJs(view);
            }

            // The renderer died (crash or killed for memory). Returning true
            // keeps the app alive; the dead WebView is dropped and, if this
            // Activity is still around, rebuilt with the last chat instead
            // of leaving a blank page.
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail != null && detail.didCrash();
                Log.w(TAG, "renderer gone (crash=" + crashed + "), rebuilding the chat page");
                ChatHolder.dropDead(view, getApplicationContext());
                ReplyKeeperService.cancel(getApplicationContext());
                if (view == chatWebView && !isFinishing() && !isDestroyed()) {
                    recreate();
                }
                return true;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                saveChatUrlIfAny(url);
            }
        });

        chatWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("blob:")) {  //DownloadManager only handles http(s), e.g. generated images are blobs
                downloadBlob(url, URLUtilCompat.getFilenameFromContentDisposition(contentDisposition), mimetype);
                return;
            }
            Uri source = Uri.parse(url);
            Log.d(TAG,"DownloadManager: " + source.getHost());
            DownloadManager.Request request = new DownloadManager.Request(source);
            request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
            request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
            request.addRequestHeader("Referer", url);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); //Notify client once download is completed!
            String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
            if (filename == null) filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);  // only if getFilenameFromContentDisposition does not work and returns null
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            assert dm != null;
            dm.enqueue(request);
        });
    }

    // Runs once per process, before the first load. Must not reference the
    // Activity (the WebView outlives it); the message callback below only
    // calls static methods for that reason.
    private void initProcessWebView() {
        //Bridge used by downloadBlob(). Only the Gemini origin can post to it, even in unrestricted mode.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(chatWebView, DOWNLOAD_JS_OBJECT, Collections.singleton(GEMINI_ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        WebView w = (WebView) view;
                        String data = message.getData();
                        w.post(() -> onBlobReceived(w.getContext().getApplicationContext(), w, data));
                    });
        }
        //Pages often revoke a blob URL right after starting the download, before downloadBlob() can read it
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(chatWebView,
                    "(function(){var revoke=URL.revokeObjectURL;" +
                    "URL.revokeObjectURL=function(u){setTimeout(function(){revoke.call(URL,u);},60000);};})();",
                    Collections.singleton(GEMINI_ORIGIN));
        }

        //Set more options
        chatWebSettings = chatWebView.getSettings();
        //Enable some WebView features
        //Set once at startup and kept: switching it with the toggle changed Gemini's layout under the user (issue #5)
        chatWebSettings.setUserAgentString(modUserAgent());
        chatWebSettings.setJavaScriptEnabled(true);
        chatWebSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        chatWebSettings.setDomStorageEnabled(true);
        //Disable some WebView features
        chatWebSettings.setAllowContentAccess(false);
        chatWebSettings.setAllowFileAccess(false);
        chatWebSettings.setBuiltInZoomControls(false);
        chatWebSettings.setDatabaseEnabled(false);
        chatWebSettings.setDisplayZoomControls(false);
        chatWebSettings.setSaveFormData(false);
        chatWebSettings.setGeolocationEnabled(false);
        chatWebSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        chatWebView.addJavascriptInterface(new ReplyWatch(getApplicationContext()), "ReplyWatch");
        // ---- drawer wiring: data bridge of the native sidebar (sidebar.js)
        chatWebView.addJavascriptInterface(new SidebarBridge(), SidebarBridge.NAME);
    }

    // ---- drawer wiring: read app/src/main/assets/sidebar.js and run it once
    // per page after load (the script itself guards against double install).
    private void injectSidebarJs(WebView view) {
        try {
            InputStream in = getAssets().open("sidebar.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            view.evaluateJavascript(out.toString("UTF-8"), null);
        } catch (Exception e) {
            Log.e(TAG, "[sidebar] inject failed", e);
        }
    }

    //Cold start URL: reopen the last chat, fall back to the start page.
    private String startUrl(SharedPreferences prefs) {
        String saved = prefs.getString(PREF_LAST_CHAT_URL, null);
        return saved != null ? saved : urlToLoad;
    }

    private void saveChatUrlIfAny(String url) {
        if (url != null && CHAT_URL_PATTERN.matcher(url).find()) {
            PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF_LAST_CHAT_URL, url).apply();
        }
    }

    //Android 13+ needs an explicit opt-in for notifications; if refused,
    //everything else must still work.
    private void askForNotificationsOnce(SharedPreferences prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !prefs.getBoolean("askedNotifications", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visibleActivity = this;
        if (chatWebView != ChatHolder.get()) {
            // The renderer died while away and the WebView was dropped:
            // build a fresh page with the last chat.
            Log.d(TAG, "onStart: chat WebView replaced, recreating");
            recreate();
            return;
        }
        boolean attached = chatWebView != null && chatWebView.getParent() == webContainer;
        // Take the WebView back into this layout whenever it is not there:
        // still hosted off-screen, or already released from off-screen
        // hosting when the reply finished in the background (that left it
        // with no parent at all: a blank screen on return).
        if (chatWebView != null && !attached) {
            ChatHolder.takeBackTo(this);
            ChatHolder.attachActivity(this);
            if (chatWebView.getParent() instanceof ViewGroup) {
                ((ViewGroup) chatWebView.getParent()).removeView(chatWebView);
            }
            if (webContainer != null) {
                webContainer.addView(chatWebView,
                        new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT));
                // A re-attached WebView draws nothing until the page changes
                // (measured: blank window, page "visible", until the next
                // stop/start). A hidden->shown cycle makes it send a frame now.
                final WebView shown = chatWebView;
                shown.setVisibility(View.INVISIBLE);
                shown.post(() -> shown.setVisibility(View.VISIBLE));
                // (drawer wiring: webContainer keeps the native sidebar above
                // the re-attached WebView by itself)
            }
        }
    }

    @Override
    protected void onStop() {
        if (visibleActivity == this) visibleActivity = null;
        // While a reply is pending or streams, host the WebView off-screen so
        // the page keeps rendering (and the stop-button check sees it
        // finish). Only from the instance that shows it, and only when no
        // other instance has become visible meanwhile.
        if (chatWebView != null && chatWebView.getParent() == webContainer && visibleActivity == null) {
            ChatHolder.maybeHostOffscreen(getApplicationContext());
        }
        super.onStop();
    }

    //Whether the Activity is in the foreground; the keeper service uses it
    //to decide about the "replied" notification.
    public static boolean isForeground() {
        return visibleActivity != null;
    }

    @Override
    protected void onDestroy() {
        if (!isChangingConfigurations() && chatWebView != null) {
            // While a reply streams, host the WebView off-screen instead of
            // detaching it, so the page keeps rendering. If it is already
            // hosted (from onStop), leave it there; detaching would break
            // the Presentation context.
            boolean mine = chatWebView.getParent() == webContainer;
            if (mine && visibleActivity == null && ReplyKeeperService.needsKeep()) {
                ChatHolder.maybeHostOffscreen(getApplicationContext());
            }
            // Only let go of the WebView if this instance still holds it: a
            // late onDestroy of an older instance must not pull it out of
            // the newer one's layout.
            if (mine && !ChatHolder.isOffscreen()) {
                //Detach the process WebView from the layout but do not destroy
                //it, so a running reply survives. Hand its context back to the app.
                if (chatWebView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) chatWebView.getParent()).removeView(chatWebView);
                }
                ChatHolder.detachActivity(getApplicationContext());
            }
            chatWebView = null;
        }
        sidebar = null;  // drawer wiring (the bridge lets go when the view detaches)
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Credit (CC BY-SA 3.0): https://stackoverflow.com/a/6077173
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    // ---- drawer wiring: Back closes the open sidebar first
                    if (webContainer != null && webContainer.isOpen()) {
                        webContainer.close(true);
                        return true;
                    }
                    if (chatWebView.canGoBack() && !chatWebView.getUrl().equals("about:blank")) {
                        chatWebView.goBack();
                    } else {
                        finish();
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void resetChat()  {

        PreferenceManager.getDefaultSharedPreferences(this).edit().remove(PREF_LAST_CHAT_URL).apply();
        chatWebView.clearCache(true);
        chatWebView.clearFormData();
        chatWebView.clearHistory();
        chatWebView.clearMatches();
        chatWebView.clearSslPreferences();
        chatCookieManager.removeSessionCookie();
        chatCookieManager.removeAllCookie();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        chatWebView.loadUrl(urlToLoad);


    }

    //Let the user move the button away from Gemini's own controls, e.g. the temporary chat button (issue #2).
    //The position is stored as a fraction of the free space, so it survives rotation and resizing.
    @SuppressLint("ClickableViewAccessibility")  //taps are not consumed here, the button still handles click and long click
    private void makeButtonDraggable() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        View parent = (View) restrictedButton.getParent();
        int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        //Re-apply after every layout: a new screen size, the system bar insets arriving (they change the
        //padding, not the size) or the button reappearing after being hidden all reset its position
        parent.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!buttonDragging) placeButton(prefs.getFloat("buttonX", 1f), prefs.getFloat("buttonY", 0f));
        });

        //The button starts at the screen edge, where gesture navigation would take a drag as Back and close the app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            restrictedButton.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    excludeFromSystemGestures(v));
        }

        restrictedButton.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY, downX, downY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = v.getX();
                        downY = v.getY();
                        buttonDragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!buttonDragging && Math.hypot(dx, dy) > touchSlop) {
                            buttonDragging = true;
                            //Cancel the pending click and long click, the drag owns the gesture from now on
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            v.onTouchEvent(cancel);
                            cancel.recycle();
                        }
                        if (buttonDragging) moveButton(downX + dx, downY + dy);
                        return buttonDragging;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!buttonDragging) return false;
                        buttonDragging = false;
                        int freeX = buttonFreeWidth();
                        int freeY = buttonFreeHeight();
                        prefs.edit()
                                .putFloat("buttonX", freeX > 0 ? (v.getX() - parent.getPaddingLeft()) / freeX : 1f)
                                .putFloat("buttonY", freeY > 0 ? (v.getY() - parent.getPaddingTop()) / freeY : 0f)
                                .apply();
                        return true;
                }
                return false;
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static void excludeFromSystemGestures(View v) {
        v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, v.getWidth(), v.getHeight())));
    }

    //The parent pads itself for the system bars (fitsSystemWindows), keep the button inside that padding
    //or it ends up under the status bar
    private int buttonFreeWidth() {
        View parent = (View) restrictedButton.getParent();
        return parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight() - restrictedButton.getWidth();
    }

    private int buttonFreeHeight() {
        View parent = (View) restrictedButton.getParent();
        return parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom() - restrictedButton.getHeight();
    }

    private void placeButton(float fractionX, float fractionY) {
        View parent = (View) restrictedButton.getParent();
        moveButton(parent.getPaddingLeft() + fractionX * buttonFreeWidth(), parent.getPaddingTop() + fractionY * buttonFreeHeight());
    }

    private void moveButton(float x, float y) {
        View parent = (View) restrictedButton.getParent();
        float minX = parent.getPaddingLeft();
        float minY = parent.getPaddingTop();
        restrictedButton.setX(Math.max(minX, Math.min(x, minX + buttonFreeWidth())));
        restrictedButton.setY(Math.max(minY, Math.min(y, minY + buttonFreeHeight())));
    }

    //A navigation the user started by tapping a web link (not a redirect, script or subframe)
    private static boolean isLinkTap(WebResourceRequest request) {
        String scheme = request.getUrl().getScheme();
        return request.isForMainFrame() && request.hasGesture()
                && ("https".equals(scheme) || "http".equals(scheme));
    }

    private boolean openInBrowser(Uri url) {
        Log.d(TAG, "[openInBrowser] " + url.getHost());
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    //A blob URL only exists inside the page that created it, so the page reads it and posts the bytes back
    private void downloadBlob(String url, String filename, String mimetype) {
        if (!url.startsWith("blob:" + GEMINI_ORIGIN + "/") || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "[downloadBlob] Unsupported blob download");
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingBlobDownload = new String[]{url, filename, mimetype};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
            return;
        }
        String js = "(function(){" +
                "var post=function(m){" + DOWNLOAD_JS_OBJECT + ".postMessage(JSON.stringify(m));};" +
                "fetch(" + JSONObject.quote(url) + ")" +
                ".then(function(r){return r.blob();})" +
                ".then(function(b){" +
                "var fr=new FileReader();" +
                "fr.onload=function(){post({name:" + JSONObject.quote(filename == null ? "" : filename) +
                ",type:b.type||" + JSONObject.quote(mimetype == null ? "" : mimetype) + ",data:fr.result});};" +
                "fr.onerror=function(){post({error:String(fr.error)});};" +
                "fr.readAsDataURL(b);})" +
                ".catch(function(e){post({error:String(e)});});" +
                "})();";
        chatWebView.evaluateJavascript(js, null);
    }

    //Static so the process-lifetime WebView's message listener never holds
    //an Activity; results are posted back with the view's handler.
    private static void onBlobReceived(Context appContext, WebView view, String json) {
        new Thread(() -> {  //decoding and writing a large image would block the UI thread
            try {
                JSONObject blob = new JSONObject(json);
                if (blob.has("error")) throw new IOException(blob.getString("error"));
                String mimetype = blob.optString("type");
                String data = blob.getString("data");
                byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
                String filename = blobFilename(blob.optString("name"), mimetype);
                String saved = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? saveToMediaStore(appContext, bytes, filename, mimetype)
                        : saveToDownloadsDir(appContext, bytes, filename, mimetype);
                view.post(() -> Toast.makeText(appContext, appContext.getString(R.string.download_saved) + "\n" + saved, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "[onBlobReceived] Saving blob failed", e);
                view.post(() -> Toast.makeText(appContext, appContext.getString(R.string.download_failed), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static String blobFilename(String name, String mimetype) {
        name = name.replaceAll("[/\\\\:]", "_");  //never let the page pick a directory
        if (name.isEmpty() || name.startsWith(".")) {
            name = "Gemini_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        }
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype);
        if (extension != null && !name.contains(".")) name += "." + extension;
        return name;
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static String saveToMediaStore(Context appContext, byte[] bytes, String filename, String mimetype) throws IOException {
        ContentResolver resolver = appContext.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, mimetype.isEmpty() ? "application/octet-stream" : mimetype);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (item == null) throw new IOException("MediaStore insert failed for " + filename);
        try (OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) throw new IOException("Cannot open " + item);
            out.write(bytes);
        } catch (IOException e) {
            resolver.delete(item, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        return filename;
    }

    private static String saveToDownloadsDir(Context appContext, byte[] bytes, String filename, String mimetype) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        File file = new File(dir, filename);
        for (int i = 1; file.exists(); i++) file = new File(dir, base + " (" + i + ")" + extension);  //do not overwrite
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        MediaScannerConnection.scanFile(appContext, new String[]{file.getAbsolutePath()},
                mimetype.isEmpty() ? null : new String[]{mimetype}, null);
        return file.getName();
    }

    private static void initURLs() {
        allowedDomains.clear();  //onCreate can run more than once, do not accumulate duplicates
        //Allowed domains. An entry covers exactly that host plus its subdomains,
        //see isAllowed(). Keep this list as small as the app still works with.
        allowedDomains.add("gemini.google.com");      //the chat app itself
        allowedDomains.add("accounts.google.com");    //sign-in
        allowedDomains.add("apis.google.com");        //sign-in and account switcher scripts
        allowedDomains.add("play.google.com");        //part of the sign-in redirect chain
        allowedDomains.add("www.google.com");         //reCAPTCHA during sign-in
        allowedDomains.add("gstatic.com");            //static assets and fonts
        allowedDomains.add("googleusercontent.com");  //avatars, uploaded and generated images
        allowedDomains.add("googleapis.com");         //backend APIs used by the app
        allowedDomains.add("scf.usercontent.goog");   //sandboxed iframes for code and HTML previews
        allowedDomains.add("push.clients6.google.com");          //file and image uploads (issue #5)
        allowedDomains.add("geminiweb-pa.clients6.google.com");  //Gemini's own web API, called while uploading
        allowedDomains.add("waa-pa.clients6.google.com");        //WAA/BotGuard attestation; blocked, replies stalled on the spinner
        allowedDomains.add("signaler-pa.clients6.google.com");   //WebChannel signaler used by the reply channel
    }

    //Public CDNs the code/HTML previews load their chart and JS libraries from. NOT in allowedDomains: they are
    //allowed only for requests that come from a preview sandbox, see isSandboxCdnRequest(). Add a host here only
    //when a preview is seen requesting it.
    private static final String[] SANDBOX_CDN_HOSTS = {
            "cdn.jsdelivr.net",        //libraries loaded by the preview scripts (seen in chat previews)
            "cdnjs.cloudflare.com",    //libraries loaded by the preview scripts (seen in chat previews)
    };

    //True when the request goes to one of SANDBOX_CDN_HOSTS AND was made by a page of the preview sandbox
    //(*.scf.usercontent.goog), judged by its Origin or Referer header. The same CDNs requested by
    //gemini.google.com itself stay blocked.
    private static boolean isSandboxCdnRequest(WebResourceRequest request) {
        if (!isCdnHost(request.getUrl().getHost())) return false;
        Map<String, String> h = request.getRequestHeaders();
        if (h == null) return false;
        for (String name : new String[]{"Origin", "Referer"}) {
            String v = null;
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (name.equalsIgnoreCase(e.getKey())) { v = e.getValue(); break; }
            }
            if (v == null) continue;
            String host = Uri.parse(v).getHost();
            if (host != null && host.endsWith(".scf.usercontent.goog")) return true;
        }
        return false;
    }

    private static boolean isCdnHost(String host) {
        if (host == null) return false;
        for (String cdn : SANDBOX_CDN_HOSTS) {
            if (host.equals(cdn)) return true;
        }
        return false;
    }

    //The preview iframes navigate themselves to blob:https://<sandbox host>/<uuid>. A blob URL is only
    //ever created by a page of the origin named in it, so allow it when that origin is on the list.
    private static boolean isAllowedBlob(String url) {
        if (url == null || !url.startsWith("blob:https://")) return false;
        return isAllowed(Uri.parse(url.substring(5)).getHost());
    }

    private static boolean isAllowed(String host) {
        if (host == null) return false;
        for (String domain : allowedDomains) {
            //Match on a dot boundary so that e.g. "notgoogle.com" does not pass as "google.com"
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mUploadMessage == null) return;
            Uri[] result = null;
            if (resultCode == Activity.RESULT_OK) {
                if (intent != null) {
                    String dataString = intent.getDataString();
                    if (dataString != null) {
                        result = new Uri[]{Uri.parse(dataString)};
                    }
                }
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        WebView.HitTestResult result = chatWebView.getHitTestResult();
        String url = "";
        if (result.getExtra() != null) {
            if (result.getType() == IMAGE_TYPE) {
                url = result.getExtra();
                if (url.startsWith("blob:")) {
                    downloadBlob(url, null, null);
                    return;
                }
                Uri source = Uri.parse(url);
                DownloadManager.Request request = new DownloadManager.Request(source);
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                request.addRequestHeader("Accept", "text/html, application/xhtml+xml, *" + "/" + "*");
                request.addRequestHeader("Accept-Language", "en-US,en;q=0.7,he;q=0.3");
                request.addRequestHeader("Referer", url);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); //Notify client once download is completed!
                String filename = URLUtil.guessFileName(url, null, "image/jpeg");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                Toast.makeText(this, getString(R.string.download) + "\n" + filename, Toast.LENGTH_SHORT).show();
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                assert dm != null;
                dm.enqueue(request);
            } else if (result.getType() == SRC_IMAGE_ANCHOR_TYPE) {
                //getExtra() is the image here, the link target is only delivered asynchronously
                Message msg = new Handler(Looper.getMainLooper(), m -> {
                    copyIfNotAllowed(m.getData().getString("url"));
                    return true;
                }).obtainMessage();
                chatWebView.requestFocusNodeHref(msg);
            } else if (result.getType() == SRC_ANCHOR_TYPE) {
                copyIfNotAllowed(result.getExtra());
            }
        }
    }

    //Copy URLs that are not allowed to open to clipboard
    private void copyIfNotAllowed(String url) {
        if (url == null) return;
        String host = Uri.parse(url).getHost();
        if (host != null && !isAllowed(host)) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.app_name), url);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show();
        }
    }

    //Phones get the mobile user agent without the WebView markers ("; wv", "Version/x"), which Google sign-in
    //accepts, so Gemini keeps its mobile layout; its desktop popovers such as the + menu do not fit a phone (issue #5).
    //Tablets (smallest width 600dp and up) get the desktop user agent, they have room for the desktop layout.
    //Decided at startup, a foldable switches the next time the app is opened.
    public String modUserAgent(){
        String userAgent = WebSettings.getDefaultUserAgent(context);
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            int end = userAgent.indexOf(")");
            if (end < 0) return userAgent;
            return "Mozilla/5.0 (X11; Linux " + System.getProperty("os.arch") + ")" + userAgent.substring(end + 1);
        }
        return userAgent.replace("; wv", "").replaceAll("Version/[\\d.]+\\s", "");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == 123) {
            if (pendingPermissionRequest != null) {  //answer the WebView request that was waiting for this
                if (granted) pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                else pendingPermissionRequest.deny();
                pendingPermissionRequest = null;
            }
            if (!granted) {
                Toast.makeText(context, R.string.mic_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            return;  //optional; everything else works without notifications too
        }
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            String[] download = pendingBlobDownload;
            pendingBlobDownload = null;
            if (granted && download != null) downloadBlob(download[0], download[1], download[2]);
            else Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

}
