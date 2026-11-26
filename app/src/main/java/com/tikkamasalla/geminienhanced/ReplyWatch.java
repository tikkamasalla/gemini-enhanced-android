package com.tikkamasalla.geminienhanced;

import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

// @JavascriptInterface bridge that reports whether Gemini is currently
// writing a reply (busy state and send detection). The sidebar has its
// own bridge (SidebarBridge).
public class ReplyWatch {
    private static final String TAG = "GeminiEnhanced";

    // ONE editable constant for the "stop generating" control. Busy = one
    // of these is visible. Edit here if Gemini's markup changes.
    public static final String STOP_SELECTORS =
            "button[aria-label*=\"Stop\" i], button[aria-label*=\"stop\" i], "
            + "[data-test-id*=\"stop\" i], .stop-icon, mat-icon[fonticon=\"stop\"]";

    private final Context appContext;
    private boolean lastBusy = false;

    public ReplyWatch(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @JavascriptInterface
    public void setBusy(boolean busy) {
        synchronized (this) {
            if (busy == lastBusy) {
                return;
            }
            lastBusy = busy;
        }
        Log.d(TAG, "busy=" + busy);
        if (busy) {
            ReplyKeeperService.startBusy(appContext);
            if (!MainActivity.isForeground()) {
                ChatHolder.hostOffscreenSoon(appContext);
            }
        } else {
            ReplyKeeperService.setIdle(appContext);
        }
    }

    // The user just sent a message (send button or Enter in the composer).
    // Arms the keep at once, before Gemini shows its stop button, so closing
    // the app right after sending does not lose the turn.
    @JavascriptInterface
    public void sendStarted(String how) {
        Log.d(TAG, "send seen (" + how + "), fg=" + MainActivity.isForeground());
        ReplyKeeperService.arm(appContext);
        if (!MainActivity.isForeground()) {
            ChatHolder.hostOffscreenSoon(appContext);
        }
    }

    // ONE editable constant for the send control (the stop control is
    // excluded) and the composer.
    public static final String SEND_SELECTORS =
            "button.send-button, button[aria-label*=\"Send\" i], [data-test-id*=\"send-button\" i]";
    public static final String COMPOSER_SELECTORS =
            ".ql-editor, [contenteditable=\"true\"], textarea";

    // Injected in onPageFinished. Busy = a stop control is visible; false
    // only after it has been absent for 2 s (debounce). Checks run on DOM
    // changes (coalesced to one per 150 ms) and, only while busy or a send
    // is pending, on a 500 ms poll with a 10 s re-send of the state (Java
    // ignores repeats). An idle page runs no timers at all.
    // Send detection: a click on the send control, or Enter in the composer
    // that empties it, calls ReplyWatch.sendStarted at once.
    public static String buildInjectorJs() {
        return "(function(){"
                + "if(window.__geReplyInstalled)return;"
                + "window.__geReplyInstalled=true;"
                + "var SEL=" + JSONObject.quote(STOP_SELECTORS) + ";"
                + "var SEND=" + JSONObject.quote(SEND_SELECTORS) + ";"
                + "var ED=" + JSONObject.quote(COMPOSER_SELECTORS) + ";"
                + "var busy=false,absentSince=0,poll=0,pend=0,ticks=0,armUntil=0;"
                + "function call(f,a){try{ReplyWatch[f](a);}catch(e){}}"
                + "function vis(el){"
                + "if(!el||!el.getBoundingClientRect)return false;"
                + "var r=el.getBoundingClientRect();"
                + "if(r.width<=0||r.height<=0)return false;"
                + "try{var s=window.getComputedStyle(el);"
                + "if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return false;}"
                + "catch(e){}"
                + "return true;}"
                + "function check(){"
                + "pend=0;var found=false;"
                + "try{var els=document.querySelectorAll(SEL);"
                + "for(var i=0;i<els.length;i++){if(vis(els[i])){found=true;break;}}}"
                + "catch(e){}"
                + "var now=Date.now();"
                + "if(found){absentSince=0;"
                + "if(!busy){busy=true;call('setBusy',true);}}"
                + "else if(busy){"
                + "if(!absentSince)absentSince=now;"
                + "if(now-absentSince>=2000){busy=false;absentSince=0;call('setBusy',false);}}"
                + "var want=busy||now<armUntil;"
                + "if(want&&!poll){poll=setInterval(tick,500);}"
                + "else if(!want&&poll){clearInterval(poll);poll=0;}}"
                // Lighter: Gemini's loading glow (chat-loading-animation, 9
                // endless CSS animations) kept running at opacity 0 after every
                // load: measured 65% of a core in the renderer at idle. It is
                // always off and hidden; the stop button shows that Gemini is
                // working. Nothing of ours reads this element.
                + "var css=document.createElement('style');"
                + "css.textContent='chat-loading-animation{visibility:hidden!important}"
                + "chat-loading-animation,chat-loading-animation *{animation:none!important}';"
                + "(document.head||document.documentElement).appendChild(css);"
                + "function tick(){check();if(busy&&++ticks%20===0)call('setBusy',busy);}"
                + "function soon(){if(!pend)pend=setTimeout(check,150);}"
                + "function arm(how){armUntil=Date.now()+45000;call('sendStarted',how);check();}"
                + "function txt(e){return ((e.value!==undefined?e.value:e.innerText)||'').trim();}"
                + "document.addEventListener('click',function(e){try{"
                + "if(!e.target||!e.target.closest||e.target.closest(SEL))return;"
                + "var b=e.target.closest(SEND);"
                + "if(!b||b.matches(SEL)||b.querySelector(SEL)||b.disabled"
                + "||b.getAttribute('aria-disabled')==='true')return;"
                + "arm('button');}catch(x){}},true);"
                + "document.addEventListener('keydown',function(e){try{"
                + "if(e.key!=='Enter'||e.shiftKey||e.isComposing)return;"
                + "var ed=e.target&&e.target.closest&&e.target.closest(ED);"
                + "if(!ed)return;var before=txt(ed);if(!before)return;"
                + "setTimeout(function(){var t=txt(ed);"
                + "if(!t||t.length<before.length/2)arm('enter');},120);}catch(x){}},true);"
                + "try{"
                + "new MutationObserver(soon).observe(document.body,"
                + "{childList:true,subtree:true,attributes:true,"
                + "attributeFilter:['aria-label','class','style','hidden','disabled','fonticon','aria-hidden']});"
                + "}catch(e){}"
                + "check();if(!busy)call('setBusy',false);"
                + "})();";
    }
}
