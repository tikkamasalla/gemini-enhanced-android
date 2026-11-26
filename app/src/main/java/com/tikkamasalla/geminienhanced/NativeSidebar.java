package com.tikkamasalla.geminienhanced;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Gemini's sidebar as native Android views, fed with the page's data (SidebarStore,
// sidebar.js). Always laid out (off-screen at -width when closed) so the first frame of
// a drag only moves it. Styled like Gemini's own dark sidebar.
public class NativeSidebar extends FrameLayout implements SidebarStore.Listener {
    private static final String TAG = "GeminiEnhanced";
    static final boolean PUSH = true;            // the open sidebar pushes the chat aside
    private static final float DIM = 0.45f;

    // Gemini dark sidebar (sampled from the page)
    private static final int BG = 0xFF1C1C1C;
    private static final int PILL = 0xFF141414;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT2 = 0xFF999999;
    private static final int RIPPLE = 0x33FFFFFF;

    private final float dp;
    private final View scrim;
    private final LinearLayout panel;
    private final ListView list;
    private final Adapter adapter;
    private final TextView accName, accPlan, accInitials;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DrawerWebContainer container;
    private int panelW = 0;
    private float progress = 0f;

    // static nav entries (the page's own anchors; refreshed from the page)
    private final ArrayList<Nav> navs = new ArrayList<>();
    private String path = "/app";
    private volatile boolean listFailed = false;
    private boolean pageReady = false;

    static final class Nav {
        final String label, href, key;
        Nav(String label, String href, String key) {
            this.label = label;
            this.href = href;
            this.key = key;
        }
    }

    static int panelWidth(int containerW, float density) {
        return Math.min(Math.round(containerW * 0.8f), Math.round(360 * density));
    }

    public NativeSidebar(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        setClipChildren(false);

        // no offscreen layer for the dim: its alpha is applied to the one colour fill
        // (a plain View with alpha < 1 is drawn through a full-screen saveLayer each frame)
        scrim = new View(ctx) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        scrim.setVisibility(INVISIBLE);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BG);
        panel.setClickable(true);                 // touches on the panel never fall through to the chat
        addView(panel, new LayoutParams(1, LayoutParams.MATCH_PARENT));

        // header: sparkle + "Gemini", search, close
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(16), px(8), px(8), px(8));
        ImageView logo = icon(SidebarIcons.SPARKLE, 0xFF4C8DF6, 24);
        head.addView(logo);
        TextView title = text("Gemini", 22, TEXT, false);
        title.setPadding(px(12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View search = iconButton(SidebarIcons.SEARCH, new OnClickListener() {
            @Override
            public void onClick(View v) {
                navTo("/search");
            }
        });
        head.addView(search);
        View close = iconButton(SidebarIcons.CLOSE, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (container != null) container.close(true);
            }
        });
        head.addView(close);
        panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(64)));

        list = new ListView(ctx);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRow(adapter.getItem(position));
            }
        });
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // footer: account + settings
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(px(16), px(6), px(8), px(6));
        accInitials = text("", 14, TEXT, true);
        accInitials.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF3C4043);
        accInitials.setBackground(circle);
        foot.addView(accInitials, new LinearLayout.LayoutParams(px(32), px(32)));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(px(12), 0, 0, 0);
        accName = text("", 16, TEXT, false);
        accPlan = text("", 13, TEXT2, false);
        names.addView(accName);
        names.addView(accPlan);
        foot.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(iconButton(SidebarIcons.SETTINGS, new OnClickListener() {
            @Override
            public void onClick(View v) {
                pageSettings();
            }
        }));
        panel.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(64)));

        // the page's own entries as defaults; replaced by what the page reports
        navs.add(new Nav("New chat", "/app", "new"));
        navs.add(new Nav("Library", "/library", "library"));
        navs.add(new Nav("Images", "/images", "images"));
        navs.add(new Nav("Scheduled", "/scheduled", "scheduled"));
        navs.add(new Nav("Students", "/students", "students"));
        loadAccount();

        SidebarStore.loadCache(ctx);
        String act = SidebarStore.activeId();
        if (act != null) path = "/app/" + act.replaceFirst("^c_", "");
        rebuild();
    }

    // ---- wiring ----

    void setContainer(DrawerWebContainer c) {
        container = c;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SidebarStore.addListener(this);
        SidebarBridge.attach(this);
        rebuild();
        // a new Activity on a live page: ask the page to send path, entries and regions again
        handler.post(new Runnable() {
            @Override
            public void run() {
                SidebarBridge.js(webView(), "window.__geSB&&__geSB.hello()");
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        SidebarStore.removeListener(this);
        SidebarBridge.detach(this);
        super.onDetachedFromWindow();
    }

    void setPanelWidth(int w) {
        if (w == panelW || w <= 0) return;
        panelW = w;
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.width = w;
        panel.setLayoutParams(lp);
        setProgress(progress);
    }

    // UI thread, every frame of a drag: transforms only, no layout.
    void setProgress(float p) {
        progress = p;
        if (panelW <= 0) return;
        panel.setTranslationX(-panelW + p * panelW);
        float push = PUSH ? p * panelW : 0f;
        scrim.setTranslationX(push);
        scrim.setAlpha(p * DIM);
        int vis = p > 0f ? VISIBLE : INVISIBLE;
        if (scrim.getVisibility() != vis) scrim.setVisibility(vis);
    }

    boolean isEnabledForSwipe() {
        // Fallback (no native data at all): the page's own sidebar takes over.
        return !(listFailed && SidebarStore.chats().isEmpty());
    }

    // ---- events from the container ----

    void onOpening() {
        SidebarBridge.js(webView(), "window.__geSB&&__geSB.refresh(false)");
    }

    void onShown() {
    }

    void onHidden() {
    }

    void onSettled(boolean open) {
    }

    // ---- data ----

    @Override
    public void onSidebarData() {
        rebuild();
    }

    void onPageList(boolean ok, String json) {
        if (ok) {
            listFailed = false;
            SidebarStore.setFromPage(getContext(), json);
        } else {
            listFailed = true;
            Log.w(TAG, "sidebar: list failed: " + json + (SidebarStore.chats().isEmpty() ? " (fallback: page sidebar)" : " (showing the cached list)"));
        }
    }

    void onPageUrl(String p) {
        if (p == null) return;
        path = p;
        String id = null;
        if (p.startsWith("/app/")) id = "c_" + p.substring(5).replaceAll("[/?#].*$", "");
        SidebarStore.setActive(getContext(), id);
        rebuild();
    }

    void onPageNav(String json) {
        try {
            JSONObject o = new JSONObject(json);
            org.json.JSONArray a = o.optJSONArray("nav");
            if (a != null && a.length() > 0) {
                ArrayList<Nav> n = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject e = a.getJSONObject(i);
                    String href = e.optString("href", "");
                    if (!href.startsWith("/")) href = "/" + href;
                    if (href.equals("/search")) continue;     // in the header, like Gemini
                    n.add(new Nav(e.optString("label", ""), href, e.optString("key", "")));
                }
                if (!n.isEmpty()) {
                    navs.clear();
                    navs.addAll(n);
                }
            }
            String name = o.optString("name", "");
            String plan = o.optString("plan", "");
            if (!name.isEmpty()) {
                getContext().getSharedPreferences("sidebar", Context.MODE_PRIVATE).edit()
                        .putString("name", name).putString("plan", plan).apply();
                setAccount(name, plan);
            }
            rebuild();
        } catch (Exception e) {
            Log.w(TAG, "sidebar nav failed", e);
        }
    }

    void onPageReady() {
        pageReady = true;
        handler.removeCallbacks(readyCheck);
        handler.postDelayed(readyCheck, 15000);
    }

    // No list 15 s after the page is ready: from now on a swipe opens the page's own sidebar
    // (only while there is no list at all; a cached list stays usable).
    private final Runnable readyCheck = new Runnable() {
        @Override
        public void run() {
            if (!SidebarStore.isFresh()) {
                listFailed = true;
                Log.w(TAG, "sidebar: no list from the page 15 s after load" + (SidebarStore.chats().isEmpty() ? " (fallback: page sidebar)" : ""));
            }
        }
    };

    DrawerWebContainer containerOrNull() {
        return container;
    }

    private void loadAccount() {
        android.content.SharedPreferences p = getContext().getSharedPreferences("sidebar", Context.MODE_PRIVATE);
        setAccount(p.getString("name", ""), p.getString("plan", ""));
    }

    private void setAccount(String name, String plan) {
        accName.setText(name);
        accPlan.setText(plan);
        accPlan.setVisibility(plan.isEmpty() ? GONE : VISIBLE);
        StringBuilder ini = new StringBuilder();
        for (String w : name.trim().split("\\s+")) if (!w.isEmpty() && ini.length() < 2) ini.append(Character.toUpperCase(w.charAt(0)));
        accInitials.setText(ini.toString());
    }

    // ---- rows ----

    private static final int R_NAV = 0, R_HEAD = 1, R_CHAT = 2, R_GAP = 3;

    static final class Row {
        final int type;
        final Nav nav;
        final SidebarStore.Chat chat;
        final String text;
        Row(int type, Nav nav, SidebarStore.Chat chat, String text) {
            this.type = type;
            this.nav = nav;
            this.chat = chat;
            this.text = text;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    private void rebuild() {
        rows.clear();
        for (Nav n : navs) rows.add(new Row(R_NAV, n, null, n.label));
        List<SidebarStore.Chat> chats = SidebarStore.chats();
        rows.add(new Row(R_GAP, null, null, null));
        if (!chats.isEmpty()) rows.add(new Row(R_HEAD, null, null, "Chats"));
        for (SidebarStore.Chat c : chats) rows.add(new Row(R_CHAT, null, c, c.title));
        adapter.notifyDataSetChanged();
    }

    private void onRow(Row r) {
        if (r == null) return;
        if (r.type == R_CHAT) {
            String id = r.chat.id.replaceFirst("^c_", "");
            if (container != null) container.close(true);
            SidebarStore.setActive(getContext(), r.chat.id);
            path = "/app/" + id;
            rebuild();
            SidebarBridge.js(webView(), "window.__geSB&&__geSB.openChat(" + JSONObject.quote(id) + ")");
        } else if (r.type == R_NAV) {
            navTo(r.nav.href);
        }
    }

    private void navTo(String href) {
        if (container != null) container.close(true);
        if ("/app".equals(href)) SidebarStore.setActive(getContext(), null);
        SidebarBridge.js(webView(), "window.__geSB&&__geSB.nav(" + JSONObject.quote(href) + ")");
    }

    private void pageSettings() {
        if (container != null) container.close(false);
        SidebarBridge.js(webView(), "window.__geSB&&__geSB.settings()");
    }

    void openPageSidebar() {
        SidebarBridge.js(webView(), "window.__geSB&&__geSB.openPageSidebar()");
    }

    void openFromPage() {
        if (container != null && isEnabledForSwipe()) container.open(true);
    }

    private WebView webView() {
        if (container == null) return null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            if (v instanceof WebView) return (WebView) v;
        }
        return null;
    }

    private boolean isActive(Row r) {
        if (r.type == R_CHAT) return r.chat.id.equals(SidebarStore.activeId());
        if (r.type == R_NAV) return r.nav.href.equals(path) || ("/app".equals(r.nav.href) && "/app".equals(path));
        return false;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int i) {
            return i >= 0 && i < rows.size() ? rows.get(i) : null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i).type;
        }

        @Override
        public boolean isEnabled(int i) {
            int t = rows.get(i).type;
            return t == R_NAV || t == R_CHAT;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            Row r = rows.get(i);
            switch (r.type) {
                case R_GAP: {
                    View v = cv != null ? cv : new View(getContext());
                    v.setLayoutParams(new AbsListParams(px(16)));
                    return v;
                }
                case R_HEAD: {
                    TextView t = cv instanceof TextView ? (TextView) cv : text("", 16, TEXT2, false);
                    t.setText(r.text);
                    t.setPadding(px(24), px(4), px(24), px(8));
                    return t;
                }
                default: {
                    RowView v = cv instanceof RowView ? (RowView) cv : new RowView(getContext());
                    v.bind(r, isActive(r));
                    return v;
                }
            }
        }
    }

    private static final class AbsListParams extends android.widget.AbsListView.LayoutParams {
        AbsListParams(int h) {
            super(ViewGroup.LayoutParams.MATCH_PARENT, h);
        }
    }

    // One row: optional icon, single-line title, optional pin; active = Gemini's pill.
    private final class RowView extends LinearLayout {
        final ImageView ic;
        final TextView tv;
        final ImageView pin;
        final GradientDrawable pill = new GradientDrawable();

        RowView(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new AbsListParams(px(48)));
            ic = new ImageView(c);
            addView(ic, new LayoutParams(px(24), px(24)));
            tv = text("", 16, TEXT, false);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            addView(tv, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pin = new ImageView(c);
            pin.setImageDrawable(SidebarIcons.get(getContext(), SidebarIcons.PIN, TEXT));
            addView(pin, new LayoutParams(px(20), px(20)));
            pill.setCornerRadius(px(24));
            pill.setColor(PILL);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(px(24));
            mask.setColor(Color.WHITE);
            android.graphics.drawable.InsetDrawable bg = new android.graphics.drawable.InsetDrawable(pill, px(8), px(2), px(8), px(2));
            android.graphics.drawable.InsetDrawable mk = new android.graphics.drawable.InsetDrawable(mask, px(8), px(2), px(8), px(2));
            setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), bg, mk));
        }

        void bind(Row r, boolean active) {
            tv.setText(r.text);
            pill.setAlpha(active ? 255 : 0);
            if (r.type == R_NAV) {
                ic.setVisibility(VISIBLE);
                ic.setImageDrawable(SidebarIcons.get(getContext(), SidebarIcons.forNav(r.nav.key, r.nav.href), TEXT));
                tv.setPadding(px(16), 0, px(8), 0);
                setPadding(px(24), 0, px(16), 0);
                tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                pin.setVisibility(GONE);
            } else {
                ic.setVisibility(GONE);
                tv.setPadding(0, 0, px(8), 0);
                setPadding(px(24), 0, px(20), 0);
                tv.setTypeface(Typeface.create("sans-serif", active ? Typeface.BOLD : Typeface.NORMAL));
                pin.setVisibility(r.chat.pinned ? VISIBLE : GONE);
            }
        }
    }

    // ---- small view helpers ----

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private ImageView icon(int which, int color, int sizeDp) {
        ImageView v = new ImageView(getContext());
        v.setImageDrawable(SidebarIcons.get(getContext(), which, color));
        v.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        return v;
    }

    private View iconButton(int which, OnClickListener l) {
        ImageView v = new ImageView(getContext());
        v.setImageDrawable(SidebarIcons.get(getContext(), which, TEXT));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(48), px(48)));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask));
        v.setOnClickListener(l);
        return v;
    }
}
