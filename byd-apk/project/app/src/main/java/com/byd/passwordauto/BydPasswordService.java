package com.byd.passwordauto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.Normalizer;
import java.util.Locale;

public class BydPasswordService extends AccessibilityService {
    private static final String BYD_PACKAGE = "com.byd.bydautolink";
    private static final long DELAY = 120L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean filling;
    private boolean screenActive;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("automation_enabled", false)) {
            cancelFill(); screenActive = false; return;
        }
        if (!BYD_PACKAGE.contentEquals(event.getPackageName())) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        boolean present = containsPrompt(root);
        root.recycle();
        if (!present) { screenActive = false; if (filling) cancelFill(); return; }
        if (screenActive || filling) return;
        screenActive = true;
        String password = new SecureStore(this).getPassword();
        if (!password.matches("\\d{6}")) return;
        filling = true;
        handler.postDelayed(() -> fillDigit(password, 0), 250L);
    }

    private void fillDigit(String password, int index) {
        if (!filling) return;
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("automation_enabled", false)) { cancelFill(); return; }
        if (index >= password.length()) { handler.postDelayed(() -> filling = false, 400L); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !containsPrompt(root)) {
            if (root != null) root.recycle();
            cancelFill(); screenActive = false; return;
        }
        char digit = password.charAt(index);
        AccessibilityNodeInfo node = findDigitNode(root, digit);
        root.recycle();
        if (node != null) {
            if (clickNodeOrAncestor(node)) {
                node.recycle();
                handler.postDelayed(() -> fillDigit(password, index + 1), DELAY);
                return;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            node.recycle();
            if (!bounds.isEmpty()) {
                tap(bounds.centerX(), bounds.centerY(), () -> handler.postDelayed(() -> fillDigit(password, index + 1), DELAY));
                return;
            }
        }
        tapFallback(digit, () -> handler.postDelayed(() -> fillDigit(password, index + 1), DELAY));
    }

    private boolean containsPrompt(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (matchesPrompt(node.getText()) || matchesPrompt(node.getContentDescription())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean found = containsPrompt(child);
            child.recycle();
            if (found) return true;
        }
        return false;
    }

    private boolean matchesPrompt(CharSequence value) {
        if (value == null) return false;
        String s = normalize(value.toString());
        return s.contains("introduzca contrasena de operacion") || (s.contains("contrasena") && s.contains("operacion"));
    }

    private String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    private AccessibilityNodeInfo findDigitNode(AccessibilityNodeInfo node, char digit) {
        if (node == null) return null;
        if (matchesDigit(node.getText(), digit) || matchesDescription(node, digit)) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findDigitNode(child, digit);
            child.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private boolean matchesDigit(CharSequence value, char digit) {
        if (value == null) return false;
        String s = normalize(value.toString());
        return s.length() == 1 && s.charAt(0) == digit;
    }

    private boolean matchesDescription(AccessibilityNodeInfo node, char digit) {
        CharSequence value = node.getContentDescription();
        if (value == null) return false;
        String s = normalize(value.toString());
        if (s.length() == 1 && s.charAt(0) == digit) return true;
        String d = String.valueOf(digit);
        boolean token = s.matches(".*(^|\\D)" + d + "(\\D|$).*");
        boolean key = s.contains("tecla") || s.contains("boton") || s.contains("button") || s.contains("key") || s.contains("numero");
        return token && (node.isClickable() || key);
    }

    private boolean clickNodeOrAncestor(AccessibilityNodeInfo start) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(start);
        try {
            for (int depth = 0; depth < 5 && current != null; depth++) {
                if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally { if (current != null) current.recycle(); }
        return false;
    }

    private void tapFallback(char digit, Runnable done) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !containsPrompt(root)) {
            if (root != null) root.recycle();
            cancelFill(); screenActive = false; return;
        }
        root.recycle();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels;
        float[] xs = {0.28f, 0.50f, 0.72f};
        float[] ys = {0.57f, 0.67f, 0.77f};
        int n = digit - '0';
        float x, y;
        if (n == 0) { x = 0.50f * w; y = 0.87f * h; }
        else { int k = n - 1; x = xs[k % 3] * w; y = ys[k / 3] * h; }
        tap(x, y, done);
    }

    private void tap(float x, float y, Runnable done) {
        Path path = new Path(); path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 55)).build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { if (done != null) done.run(); }
            @Override public void onCancelled(GestureDescription g) { cancelFill(); }
        }, null);
    }

    private void cancelFill() { handler.removeCallbacksAndMessages(null); filling = false; }
    @Override public void onInterrupt() { cancelFill(); screenActive = false; }
    @Override public void onDestroy() { cancelFill(); super.onDestroy(); }
}
