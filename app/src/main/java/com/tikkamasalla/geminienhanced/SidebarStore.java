package com.tikkamasalla.geminienhanced;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The chat list shown by the native sidebar. Process-wide (the WebView outlives
// the Activity), cached on disk so a cold start shows the last list at once; the page
// refreshes it (sidebar.js). UI thread only, except the disk write.
public final class SidebarStore {
    private static final String TAG = "GeminiEnhanced";
    private static final String FILE = "sidebar-list.json";

    public static final class Chat {
        public final String id;      // "c_..." without the prefix as in /app/<id>
        public final String title;
        public final boolean pinned;

        Chat(String id, String title, boolean pinned) {
            this.id = id;
            this.title = title;
            this.pinned = pinned;
        }
    }

    public interface Listener {
        void onSidebarData();
    }

    private static List<Chat> chats = Collections.emptyList();
    private static String activeId = null;
    private static boolean loaded = false;
    private static boolean fresh = false;          // a list arrived from the page in this process
    private static long freshAt = 0;
    private static final ArrayList<Listener> listeners = new ArrayList<>();
    // one writer thread: saves never overlap on the temp file
    private static final java.util.concurrent.ExecutorService SAVER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private SidebarStore() {
    }

    public static List<Chat> chats() {
        return chats;
    }

    public static String activeId() {
        return activeId;
    }

    public static boolean isFresh() {
        return fresh;
    }

    public static long freshAt() {
        return freshAt;
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onSidebarData();
            } catch (Exception e) {
                Log.w(TAG, "sidebar listener failed", e);
            }
        }
    }

    // Disk cache, read once per process (small file, ~10-40 KB).
    public static void loadCache(Context ctx) {
        if (loaded) return;
        loaded = true;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(out.toString("UTF-8"));
            List<Chat> l = parse(o.optJSONArray("chats"));
            if (!l.isEmpty()) chats = l;
            if (activeId == null) activeId = o.optString("active", null);
            Log.d(TAG, "sidebar: cache " + chats.size() + " chats");
        } catch (Exception e) {
            Log.w(TAG, "sidebar cache read failed", e);
        }
    }

    private static List<Chat> parse(JSONArray a) {
        ArrayList<Chat> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("id", "");
            if (!MainActivityIds.valid(id)) continue;
            l.add(new Chat(id, c.optString("t", ""), c.optBoolean("p", false)));
        }
        return l;
    }

    // From the page (posted to the UI thread). json = {"chats":[{id,t,p}], "complete":bool}
    public static void setFromPage(final Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json);
            List<Chat> l = parse(o.optJSONArray("chats"));
            if (l.isEmpty()) return;
            if (o.optBoolean("partial", false)) {
                // newest page only: keep the older chats we already have below it
                java.util.HashSet<String> ids = new java.util.HashSet<>();
                for (Chat c : l) ids.add(c.id);
                for (Chat c : chats) if (!ids.contains(c.id)) l.add(c);
            }
            if (sameAs(l)) {
                fresh = true;
                freshAt = System.currentTimeMillis();
                return;
            }
            chats = l;
            fresh = true;
            freshAt = System.currentTimeMillis();
            changed();
            save(ctx.getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "sidebar list from page failed", e);
        }
    }

    private static boolean sameAs(List<Chat> l) {
        if (l.size() != chats.size()) return false;
        for (int i = 0; i < l.size(); i++) {
            Chat a = l.get(i), b = chats.get(i);
            if (!a.id.equals(b.id) || a.pinned != b.pinned || !a.title.equals(b.title)) return false;
        }
        return true;
    }

    public static void setActive(Context ctx, String id) {
        if (id != null && !MainActivityIds.valid(id)) id = null;
        if (id == null ? activeId == null : id.equals(activeId)) return;
        activeId = id;
        changed();
        save(ctx.getApplicationContext());
    }

    private static void save(final Context app) {
        final List<Chat> snap = chats;
        final String act = activeId;
        SAVER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray a = new JSONArray();
                    for (Chat c : snap) {
                        JSONObject o = new JSONObject();
                        o.put("id", c.id);
                        o.put("t", c.title);
                        if (c.pinned) o.put("p", true);
                        a.put(o);
                    }
                    JSONObject root = new JSONObject();
                    root.put("chats", a);
                    if (act != null) root.put("active", act);
                    File tmp = new File(app.getFilesDir(), FILE + ".tmp");
                    FileOutputStream out = new FileOutputStream(tmp);
                    out.write(root.toString().getBytes("UTF-8"));
                    out.close();
                    if (!tmp.renameTo(new File(app.getFilesDir(), FILE))) Log.w(TAG, "sidebar cache rename failed");
                } catch (Exception e) {
                    Log.w(TAG, "sidebar cache write failed", e);
                }
            }
        });
    }

    // Chat ids as they appear in /app/<id>: letters, digits, _ and -.
    static final class MainActivityIds {
        static boolean valid(String id) {
            return id != null && id.length() > 0 && id.length() < 80 && id.matches("[A-Za-z0-9_-]+");
        }
    }
}
