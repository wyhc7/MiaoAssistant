package com.example.u7e5f3218e9;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class QQAccessibilityService extends AccessibilityService {
    private static final String ID_INPUT_QQ = "com.tencent.mobileqq:id/input";
    private static final String ID_SEND_QQ = "com.tencent.mobileqq:id/send_btn";
    private static final String ID_INPUT_WECHAT_A = "com.tencent.mm:id/bkk";
    private static final String ID_INPUT_WECHAT_B = "com.tencent.mm:id/b4a";
    private static final String PKG_QQ = "com.tencent.mobileqq";
    private static final String PKG_QQI = "com.tencent.mobileqqi";
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String PKG_DOUYIN = "com.ss.android.ugc.aweme";
    private static final String PKG_DOUYIN_LITE = "com.ss.android.ugc.aweme.lite";
    private static final String[] SEND_TEXTS = {"发送", "發送", "Send"};
    private static final String TAG = "QQCatSvc";
    private CatConfig cachedConfig;
    private String userOriginal = "";
    private String lastSet = "";
    private boolean processing = false;
    private long lastWriteTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        String pkg = e.getPackageName() != null ? e.getPackageName().toString() : "";
        if (isSupportedPkg(pkg)) {
            int type = e.getEventType();
            if (type == 32) {
                this.processing = false;
                this.userOriginal = "";
                this.lastSet = "";
                this.lastWriteTime = 0L;
                this.cachedConfig = CatConfig.load(this);
                return;
            }
            if (type == 1) {
                AccessibilityNodeInfo src = e.getSource();
                if (src != null) {
                    String id = src.getViewIdResourceName();
                    if (ID_SEND_QQ.equals(id) || isSendText(src.getText())) {
                        Log.d(TAG, "点击发送，兜底处理");
                        doProcess(true, null);
                    }
                    src.recycle();
                    return;
                }
                return;
            }
            if (type == 16) {
                CatConfig cfg = this.cachedConfig;
                if (cfg == null) {
                    cfg = CatConfig.load(this);
                    this.cachedConfig = cfg;
                }
                String mode = cfg.processingMode != null ? cfg.processingMode : CatConfig.MODE_PUNCTUATION;
                AccessibilityNodeInfo src = e.getSource();
                if (!CatConfig.MODE_REALTIME.equals(mode)) {
                    CharSequence probe = (src != null && src.isEditable()) ? src.getText() : null;
                    if (probe == null || probe.length() == 0 || !isPunctuationEnding(probe.toString().trim())) {
                        if (src != null) {
                            src.recycle();
                        }
                        return;
                    }
                    Log.d(TAG, "标点触发: " + probe.toString().trim());
                }
                doProcess(false, src);
                return;
            }
        }
    }

    private static boolean isSupportedPkg(String pkg) {
        return PKG_QQ.equals(pkg) || PKG_QQI.equals(pkg) || PKG_WECHAT.equals(pkg)
                || PKG_DOUYIN.equals(pkg) || PKG_DOUYIN_LITE.equals(pkg);
    }

    private static boolean isSendText(CharSequence cs) {
        if (cs == null) {
            return false;
        }
        String s = cs.toString().trim();
        for (String t : SEND_TEXTS) {
            if (t.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo currentInput(AccessibilityNodeInfo preferred) {
        if (preferred != null) {
            AccessibilityNodeInfo copy = null;
            if (preferred.isEditable()) {
                copy = AccessibilityNodeInfo.obtain(preferred);
            }
            preferred.recycle();
            if (copy != null) {
                return copy;
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo inp = findInput(root);
        root.recycle();
        return inp;
    }

    private AccessibilityNodeInfo findInput(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo inp = findFocusedEditable(root);
        if (inp == null) {
            inp = findNodeById(root, ID_INPUT_QQ);
        }
        if (inp == null) {
            inp = findNodeById(root, ID_INPUT_WECHAT_A);
        }
        if (inp == null) {
            inp = findNodeById(root, ID_INPUT_WECHAT_B);
        }
        if (inp == null) {
            inp = findEditable(root);
        }
        return inp;
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo n) {
        if (n == null) {
            return null;
        }
        if (n.isEditable() && n.isFocused()) {
            return AccessibilityNodeInfo.obtain(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                AccessibilityNodeInfo r = findFocusedEditable(c);
                c.recycle();
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private boolean isPunctuationEnding(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char last = s.charAt(s.length() - 1);
        return last == 12290 || last == 65281 || last == '!' || last == 65311 || last == '?' || last == ' ';
    }

    private void doProcess(boolean isSendClick, AccessibilityNodeInfo preferred) {
        if (this.processing) {
            if (preferred != null) {
                preferred.recycle();
            }
            return;
        }
        this.processing = true;
        AccessibilityNodeInfo inp = currentInput(preferred);
        if (inp == null) {
            this.processing = false;
            return;
        }
        CharSequence cs = inp.getText();
        if (cs == null || cs.length() == 0) {
            inp.recycle();
            this.processing = false;
            this.userOriginal = "";
            this.lastSet = "";
            return;
        }
        String raw = cs.toString().trim();
        if (raw.isEmpty()) {
            inp.recycle();
            this.processing = false;
            this.userOriginal = "";
            this.lastSet = "";
            return;
        }
        CatConfig cfg = this.cachedConfig;
        if (cfg == null) {
            cfg = CatConfig.load(this);
            this.cachedConfig = cfg;
        }
        long now = System.currentTimeMillis();
        long j = this.lastWriteTime;
        if (j > 0 && now - j < 600 && raw.equals(this.lastSet)) {
            Log.d(TAG, "写入回显跳过");
            this.lastWriteTime = 0L;
            inp.recycle();
            this.processing = false;
            return;
        }
        boolean isRealtime = CatConfig.MODE_REALTIME.equals(cfg.processingMode);
        if (!isRealtime && this.lastSet.isEmpty()) {
            this.userOriginal = stripAll(raw, cfg);
            Log.d(TAG, "标点首次剥离: " + this.userOriginal);
        } else if (this.lastSet.isEmpty() || !raw.startsWith(this.lastSet)) {
            if (this.lastSet.isEmpty()) {
                this.userOriginal = stripAll(raw, cfg);
                Log.d(TAG, "首条剥离: " + this.userOriginal);
            } else {
                this.userOriginal = stripAll(raw, cfg);
                Log.d(TAG, "不匹配剥离: " + this.userOriginal);
            }
        } else {
            String added = raw.substring(this.lastSet.length());
            this.userOriginal += added;
            Log.d(TAG, "前缀增量: +" + added + "  userOriginal=" + this.userOriginal);
        }
        if (this.userOriginal.isEmpty()) {
            Log.d(TAG, "原文为空，跳过");
            inp.recycle();
            this.processing = false;
            return;
        }
        CatConfig effectiveCfg = cfg;
        if (isRealtime && cfg.enableRandomEmoticon && !isSendClick) {
            effectiveCfg = cloneConfigWithoutEmoticon(cfg);
        }
        String target = TextProcessor.process(this.userOriginal, effectiveCfg);
        if (!target.equals(raw)) {
            Log.d(TAG, "写入: raw=" + raw + "  userOriginal=" + this.userOriginal + "  target=" + target);
            boolean ok = setText(inp, target);
            if (ok) {
                this.lastSet = target;
                this.lastWriteTime = System.currentTimeMillis();
            }
            inp.recycle();
            this.processing = false;
            return;
        }
        this.lastSet = target;
        inp.recycle();
        this.processing = false;
    }

    private CatConfig cloneConfigWithoutEmoticon(CatConfig src) {
        CatConfig c = new CatConfig();
        c.enableAppend = src.enableAppend;
        c.appendText = src.appendText;
        c.enableRandomEmoticon = false;
        c.processingMode = src.processingMode;
        c.customEmoticons = src.customEmoticons;
        c.rules = src.rules;
        return c;
    }

    private String stripAll(String text, CatConfig cfg) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;

        List<CatConfig.Rule> reversedRules = new ArrayList<>();
        if (cfg.rules != null) {
            for (CatConfig.Rule r : cfg.rules) {
                if (r != null && !r.from.isEmpty() && !r.to.isEmpty()) {
                    reversedRules.add(r);
                }
            }
        }
        Collections.sort(reversedRules, new Comparator<CatConfig.Rule>() {
            @Override
            public int compare(CatConfig.Rule a, CatConfig.Rule b) {
                return b.to.length() - a.to.length();
            }
        });
        for (CatConfig.Rule r : reversedRules) {
            result = result.replace(r.to, r.from);
        }

        String app = cfg.appendText == null ? "" : cfg.appendText.trim();
        if (!app.isEmpty()) {
            result = result.replace(" " + app, " ");
            result = result.replace(app, "");
        }

        String[] emotes = cfg.getActiveEmoticons();
        List<String> sortedEmotes = new ArrayList<>();
        if (emotes.length == 0) {
            for (String em : CatConfig.BUILTIN_EMOTICONS) {
                sortedEmotes.add(em);
            }
        } else {
            for (String em : emotes) {
                sortedEmotes.add(em);
            }
        }
        Collections.sort(sortedEmotes, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        for (String em : sortedEmotes) {
            if (em == null || em.isEmpty()) {
                continue;
            }
            int idx;
            while ((idx = result.indexOf(em)) >= 0) {
                int st;
                if (idx <= 0 || result.charAt(idx - 1) != ' ') {
                    st = idx;
                } else {
                    st = idx - 1;
                }
                result = result.substring(0, st) + result.substring(idx + em.length());
            }
        }
        return result.replaceAll("\\s*[\\p{S}\\p{So}\\p{Sm}\\p{Sk}\\p{P}]{3,}\\s*", " ").trim();
    }

    private AccessibilityNodeInfo findNodeById(AccessibilityNodeInfo n, String id) {
        if (n == null || id == null) {
            return null;
        }
        if (id.equals(n.getViewIdResourceName())) {
            return AccessibilityNodeInfo.obtain(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                AccessibilityNodeInfo r = findNodeById(c, id);
                c.recycle();
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) {
            return null;
        }
        if (n.isEditable()) {
            return AccessibilityNodeInfo.obtain(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                AccessibilityNodeInfo r = findEditable(c);
                c.recycle();
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private boolean setText(final AccessibilityNodeInfo n, String t) {
        if (n == null) {
            return false;
        }
        try {
            Bundle b = new Bundle();
            b.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", t);
            if (n.performAction(2097152, b)) {
                Bundle a = new Bundle();
                a.putInt("ACTION_ARGUMENT_SELECTION_START_INT", t.length());
                a.putInt("ACTION_ARGUMENT_SELECTION_END_INT", t.length());
                n.performAction(131072, a);
                return true;
            }
            final ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            CharSequence oldClip = null;
            try {
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    oldClip = cm.getPrimaryClip().getItemAt(0).getText();
                }
            } catch (Exception ignore) {
            }
            cm.setPrimaryClip(ClipData.newPlainText("miao", t));
            boolean sel = n.performAction(131072);
            boolean ok = sel && n.performAction(32768);
            Log.d(TAG, "SET_TEXT 失败，剪贴板兜底: " + ok);
            final CharSequence restore = oldClip;
            if (ok) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ClipboardManager cm2 = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm2 != null) {
                                cm2.setPrimaryClip(ClipData.newPlainText("miao", restore == null ? "" : restore));
                            }
                        } catch (Exception ignore) {
                        }
                    }
                }, 500L);
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onInterrupt() {
        this.processing = false;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo i = new AccessibilityServiceInfo();
        i.eventTypes = 49;
        i.feedbackType = 16;
        i.flags = 81;
        i.notificationTimeout = 50L;
        i.packageNames = new String[]{PKG_QQ, PKG_QQI, PKG_WECHAT, PKG_DOUYIN, PKG_DOUYIN_LITE};
        setServiceInfo(i);
        this.cachedConfig = CatConfig.load(this);
    }
}