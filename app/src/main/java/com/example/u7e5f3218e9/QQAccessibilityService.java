package com.example.u7e5f3218e9;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class QQAccessibilityService extends AccessibilityService {
    private static final boolean DEBUG = false;
    public static final String KEY_BLOCKED_PREFIX = "blocked_";
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
    private String stableEmoticon = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        String pkg = e.getPackageName() != null ? e.getPackageName().toString() : "";
        this.currentPkg = isSupportedPkg(pkg) ? pkg : this.currentPkg;
        if (DEBUG) {
            AccessibilityNodeInfo dbgSrc = e.getSource();
            Log.d(TAG, "evt pkg=" + pkg + " type=" + e.getEventType()
                    + " src=" + (dbgSrc != null ? (dbgSrc.isEditable() ? "editable" : "node") + ":" + dbgSrc.getViewIdResourceName() : "null"));
            if (dbgSrc != null) {
                CharSequence dt = dbgSrc.getText();
                if (dt != null && DEBUG) {
                    Log.d(TAG, "srcText=" + dt.toString().trim());
                }
                dbgSrc.recycle();
            }
        }
        if (isSupportedPkg(pkg)) {
            if (!CatConfig.isMasterEnabled(this)) {
                this.processing = false;
                this.userOriginal = "";
                this.lastSet = "";
                this.lastWriteTime = 0L;
                return;
            }
            int type = e.getEventType();
            if (type == 32) {
                this.processing = false;
                this.userOriginal = "";
                this.lastSet = "";
                this.lastWriteTime = 0L;
                this.stableEmoticon = "";
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
                boolean realtime = CatConfig.MODE_REALTIME.equals(mode);
                if (!realtime) {
                    CharSequence probe = null;
                    if (src != null && src.isEditable()) {
                        probe = src.getText();
                    } else {
                        if (DEBUG) {
                            Log.d(TAG, "源节点不可用，回退窗口搜索预检");
                        }
                        AccessibilityNodeInfo tmp = currentInput(src, pkg);
                        src = null;
                        if (tmp != null) {
                            CharSequence t2 = tmp.getText();
                            if (t2 != null) {
                                probe = t2.toString().trim();
                            }
                            tmp.recycle();
                        }
                    }
                    if (probe == null || probe.length() == 0 || !isPunctuationEnding(probe.toString())) {
                        if (DEBUG) {
                            Log.d(TAG, "未满足标点触发条件");
                        }
                        if (src != null) {
                            src.recycle();
                        }
                        return;
                    }
                    Log.d(TAG, "标点触发: " + probe.toString());
                }
                doProcess(false, src);
                return;
            }
        }
    }

    private static boolean isSupportedPkg(String pkg) {
        if (DEBUG) {
            return true;
        }
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

    private int emptyStrikes = 0;

    private void markProbeFailure(String pkg) {
        if (pkg == null) {
            return;
        }
        this.emptyStrikes++;
        if (this.emptyStrikes >= 3) {
            getSharedPreferences("cat_config", MODE_PRIVATE).edit()
                    .putBoolean(KEY_BLOCKED_PREFIX + pkg, true).apply();
            Log.w(TAG, pkg + " 疑似屏蔽无障碍读取，已标记");
            this.emptyStrikes = 0;
        }
    }

    private void markProbeSuccess(String pkg) {
        this.emptyStrikes = 0;
        if (pkg != null) {
            getSharedPreferences("cat_config", MODE_PRIVATE).edit()
                    .remove(KEY_BLOCKED_PREFIX + pkg).apply();
        }
    }

    private AccessibilityNodeInfo currentInput(AccessibilityNodeInfo preferred, String pkg) {
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
            if (DEBUG) {
                Log.d(TAG, "currentInput: getRootInActiveWindow 为 null（可能被系统限制获取窗口内容）");
            }
            return null;
        }
        if (DEBUG) {
            StringBuilder sb = new StringBuilder("tree pkg=").append(root.getPackageName())
                    .append(" childCount=").append(root.getChildCount()).append("\n");
            dumpTree(root, 0, sb);
            Log.d(TAG, sb.toString());
        }
        AccessibilityNodeInfo inp = findInput(root);
        if (inp == null) {
            if (root.getChildCount() == 0) {
                markProbeFailure(pkg);
            }
            if (DEBUG) {
                Log.d(TAG, "currentInput: 窗口树中未找到可编辑节点");
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "currentInput: 定位到输入节点 id=" + inp.getViewIdResourceName() + " cls=" + inp.getClassName());
            }
            markProbeSuccess(pkg);
        }
        root.recycle();
        return inp;
    }

    private void dumpTree(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 4 || sb.length() > 3000) {
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) {
                sb.append("  ".repeat(depth + 1)).append("[").append(i).append("] null\n");
                continue;
            }
            CharSequence txt = c.getText();
            sb.append("  ".repeat(Math.min(depth + 1, 5)))
                    .append("[").append(i).append("] ")
                    .append(c.getClassName()).append(" ed=").append(c.isEditable())
                    .append(" foc=").append(c.isFocused())
                    .append(" id=").append(c.getViewIdResourceName())
                    .append(" txt=").append(txt == null ? "" : txt.toString().substring(0, Math.min(20, txt.length())))
                    .append("\n");
            dumpTree(c, depth + 1, sb);
            c.recycle();
        }
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
        return last == 12290 || last == 65281 || last == '!' || last == 65311 || last == '?'
                || last == '~' || last == 65374 || last == ' ';
    }

    private String currentPkg = "";

    private void doProcess(boolean isSendClick, AccessibilityNodeInfo preferred) {
        if (this.processing) {
            if (preferred != null) {
                preferred.recycle();
            }
            return;
        }
        this.processing = true;
        AccessibilityNodeInfo inp = currentInput(preferred, this.currentPkg);
        if (inp == null) {
            if (DEBUG) {
                Log.d(TAG, "doProcess: 未找到输入节点");
            }
            this.processing = false;
            return;
        }
        CharSequence cs = inp.getText();
        if (cs == null || cs.length() == 0) {
            inp.recycle();
            this.processing = false;
            this.userOriginal = "";
            this.lastSet = "";
            this.stableEmoticon = "";
            return;
        }
        String raw = cs.toString().trim();
        if (raw.isEmpty()) {
            inp.recycle();
            this.processing = false;
            this.userOriginal = "";
            this.lastSet = "";
            this.stableEmoticon = "";
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
        if (raw.equals(this.lastSet)) {
            Log.d(TAG, "内容无变化，跳过");
            inp.recycle();
            this.processing = false;
            return;
        }
        if (this.lastSet.isEmpty()) {
            this.userOriginal = stripAll(raw, cfg);
            Log.d(TAG, "首次剥离: " + this.userOriginal);
        } else if (this.lastSet.startsWith(raw)) {
            this.userOriginal = stripAll(raw, cfg);
            this.lastSet = "";
            Log.d(TAG, "检测到删除，仅同步状态不回写: " + this.userOriginal);
            inp.recycle();
            this.processing = false;
            return;
        } else if (raw.startsWith(this.lastSet)) {
            String added = raw.substring(this.lastSet.length());
            this.userOriginal += added;
            Log.d(TAG, "前缀增量: +" + added + "  userOriginal=" + this.userOriginal);
        } else {
            this.userOriginal = stripAll(raw, cfg);
            Log.d(TAG, "任意编辑剥离: " + this.userOriginal);
        }
        if (this.userOriginal.isEmpty()) {
            Log.d(TAG, "原文为空，跳过");
            inp.recycle();
            this.processing = false;
            this.stableEmoticon = "";
            return;
        }
        String forcedEmoticon = null;
        if (isRealtime && cfg.enableRandomEmoticon) {
            if (this.stableEmoticon.isEmpty()) {
                this.stableEmoticon = TextProcessor.getRandomEmoticon(cfg);
            }
            forcedEmoticon = this.stableEmoticon;
        }
        String target = TextProcessor.process(this.userOriginal, cfg, forcedEmoticon);
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
            boolean setTextOk = n.performAction(2097152, b);
            if (DEBUG) {
                Log.d(TAG, "ACTION_SET_TEXT -> " + setTextOk + "  id=" + n.getViewIdResourceName() + " cls=" + n.getClassName());
            }
            if (setTextOk) {
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

    private final BroadcastReceiver dumpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                StringBuilder wb = new StringBuilder("WINDOWS n=").append(wins.size()).append("\n");
                for (int i = 0; i < wins.size(); i++) {
                    AccessibilityWindowInfo w = wins.get(i);
                    AccessibilityNodeInfo r = w.getRoot();
                    wb.append("win[").append(i).append("] type=").append(w.getType())
                            .append(" layer=").append(w.getLayer())
                            .append(" active=").append(w.isActive())
                            .append(" focused=").append(w.isFocused())
                            .append(" pkg=").append(r == null ? "null-root" : r.getPackageName())
                            .append(" cc=").append(r == null ? -1 : r.getChildCount())
                            .append("\n");
                    if (r != null) {
                        r.recycle();
                    }
                }
                Log.d(TAG, wb.toString());
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.d(TAG, "DUMP: root null");
                return;
            }
            StringBuilder sb = new StringBuilder("DUMP pkg=").append(root.getPackageName())
                    .append(" cc=").append(root.getChildCount()).append("\n");
            dumpTree(root, 0, sb);
            Log.d(TAG, sb.toString());
            root.recycle();
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        if (DEBUG) {
            try {
                registerReceiver(dumpReceiver, new IntentFilter("com.example.u7e5f3218e9.DUMP"), Context.RECEIVER_EXPORTED);
            } catch (Exception e) {
                Log.d(TAG, "register dumpReceiver failed: " + e);
            }
        }
        AccessibilityServiceInfo i = new AccessibilityServiceInfo();
        i.eventTypes = 49;
        i.feedbackType = 16;
        i.flags = 115;
        i.notificationTimeout = 50L;
        i.packageNames = DEBUG ? null : new String[]{PKG_QQ, PKG_QQI, PKG_WECHAT, PKG_DOUYIN, PKG_DOUYIN_LITE};
        setServiceInfo(i);
        this.cachedConfig = CatConfig.load(this);
    }
}