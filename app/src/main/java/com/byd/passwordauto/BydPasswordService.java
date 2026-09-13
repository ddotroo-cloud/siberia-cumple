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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BydPasswordService extends AccessibilityService {
    private static final String BYD_PACKAGE = "com.byd.bydautolink";
    private static final long DIGIT_DELAY = 150L;
    private static final long START_DELAY = 220L;
    private static final long RETRY_COOLDOWN = 3500L;

    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private final Handler fillHandler = new Handler(Looper.getMainLooper());
    private boolean filling;
    private long lastFillAt;
    private String lastEventText = "";

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!BYD_PACKAGE.contentEquals(event.getPackageName())) return;

        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("automation_enabled", false)) {
            cancelFill();
            saveDiag("Automatización desactivada");
            return;
        }

        lastEventText = eventSummary(event);
        scanHandler.removeCallbacksAndMessages(null);
        scanHandler.postDelayed(this::scanAndMaybeFill, 120L);
    }

    private void scanAndMaybeFill() {
        if (filling) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            saveDiag("BYD detectado; raíz de Accesibilidad no disponible");
            return;
        }

        boolean prompt = containsPrompt(root) || matchesPrompt(lastEventText);
        boolean passwordNode = containsPasswordNode(root);
        DigitProfile profile = analyzeDigits(root);
        int geometryCandidates = collectGeometryCandidates(root).size();
        String rootClass = root.getClassName() == null ? "?" : root.getClassName().toString();
        root.recycle();

        boolean keypadStrong = profile.uniqueDigits >= 9;
        boolean safePasswordScreen = prompt || (passwordNode && keypadStrong);

        saveDiag("BYD activo | ventana=" + rootClass
                + " | textoClave=" + (prompt ? "sí" : "no")
                + " | campoPassword=" + (passwordNode ? "sí" : "no")
                + " | dígitos=" + profile.digits
                + " (" + profile.uniqueDigits + "/10)"
                + " | candidatos=" + geometryCandidates
                + " | " + (safePasswordScreen ? "pantalla compatible" : "esperando pantalla compatible"));

        if (!safePasswordScreen) return;
        if (System.currentTimeMillis() - lastFillAt < RETRY_COOLDOWN) return;

        String password = new SecureStore(this).getPassword();
        if (!password.matches("\\d{6}")) {
            saveDiag("Pantalla compatible detectada, pero no se pudo leer la contraseña guardada");
            return;
        }

        filling = true;
        lastFillAt = System.currentTimeMillis();
        fillHandler.postDelayed(() -> fillDigit(password, 0), START_DELAY);
    }

    private void fillDigit(String password, int index) {
        if (!filling) return;
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("automation_enabled", false)) {
            cancelFill();
            return;
        }
        if (index >= password.length()) {
            saveDiag("Contraseña enviada: 6/6 pulsaciones");
            fillHandler.postDelayed(() -> filling = false, 500L);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            saveDiag("Interrumpido en dígito " + (index + 1) + ": ventana no disponible");
            cancelFill();
            return;
        }

        boolean prompt = containsPrompt(root) || matchesPrompt(lastEventText);
        boolean passwordNode = containsPasswordNode(root);
        DigitProfile profile = analyzeDigits(root);
        boolean stillSafe = prompt || (passwordNode && profile.uniqueDigits >= 9);
        if (!stillSafe) {
            root.recycle();
            saveDiag("Interrumpido en dígito " + (index + 1) + ": la pantalla dejó de coincidir");
            cancelFill();
            return;
        }

        char digit = password.charAt(index);
        AccessibilityNodeInfo node = findDigitNode(root, digit);
        if (node != null) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            boolean clicked = clickNodeOrAncestor(node);
            node.recycle();
            root.recycle();
            if (clicked) {
                saveDiag("Introduciendo contraseña: " + (index + 1) + "/6 (nodo accesible)");
                fillHandler.postDelayed(() -> fillDigit(password, index + 1), DIGIT_DELAY);
                return;
            }
            if (!bounds.isEmpty()) {
                saveDiag("Introduciendo contraseña: " + (index + 1) + "/6 (posición del nodo)");
                tap(bounds.centerX(), bounds.centerY(), () ->
                        fillHandler.postDelayed(() -> fillDigit(password, index + 1), DIGIT_DELAY));
                return;
            }
        }

        Rect geometric = findDigitBoundsByGeometry(root, digit);
        root.recycle();
        if (geometric != null && !geometric.isEmpty()) {
            saveDiag("Introduciendo contraseña: " + (index + 1) + "/6 (geometría del teclado)");
            tap(geometric.centerX(), geometric.centerY(), () ->
                    fillHandler.postDelayed(() -> fillDigit(password, index + 1), DIGIT_DELAY));
            return;
        }

        if (prompt) {
            saveDiag("Introduciendo contraseña: " + (index + 1) + "/6 (rejilla de respaldo)");
            tapFallback(digit, () -> fillHandler.postDelayed(() -> fillDigit(password, index + 1), DIGIT_DELAY));
        } else {
            saveDiag("Pantalla detectada, pero no pude localizar el teclado con seguridad");
            cancelFill();
        }
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

    private boolean containsPasswordNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isPassword()) return true;
        String text = normalize(join(node.getText(), node.getContentDescription(), node.getHintText()));
        if (text.contains("contrasena") || text.contains("password") || text.contains("codigo de operacion")) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean found = containsPasswordNode(child);
            child.recycle();
            if (found) return true;
        }
        return false;
    }

    private boolean matchesPrompt(CharSequence value) {
        if (value == null) return false;
        return matchesPrompt(value.toString());
    }

    private boolean matchesPrompt(String value) {
        if (value == null) return false;
        String s = normalize(value);
        return s.contains("introduzca contrasena de operacion")
                || s.contains("ingrese contrasena de operacion")
                || s.contains("introducir contrasena de operacion")
                || (s.contains("contrasena") && s.contains("operacion"))
                || (s.contains("operation") && s.contains("password"));
    }

    private String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).trim();
    }

    private AccessibilityNodeInfo findDigitNode(AccessibilityNodeInfo node, char digit) {
        if (node == null) return null;
        if (matchesDigit(node.getText(), digit) || matchesDigit(node.getContentDescription(), digit)) {
            return AccessibilityNodeInfo.obtain(node);
        }
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
        if (s.length() == 1 && s.charAt(0) == digit) return true;
        String d = String.valueOf(digit);
        return s.matches("^(tecla|boton|button|key|numero|number)?\\s*" + d + "$")
                || s.matches("^" + d + "\\s*(tecla|boton|button|key|numero|number)$");
    }

    private DigitProfile analyzeDigits(AccessibilityNodeInfo root) {
        Set<Character> found = new HashSet<>();
        collectDigits(root, found);
        List<Character> sorted = new ArrayList<>(found);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (Character c : sorted) sb.append(c);
        return new DigitProfile(found.size(), sb.toString());
    }

    private void collectDigits(AccessibilityNodeInfo node, Set<Character> out) {
        if (node == null) return;
        for (char d = '0'; d <= '9'; d++) {
            if (matchesDigit(node.getText(), d) || matchesDigit(node.getContentDescription(), d)) {
                out.add(d);
                break;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectDigits(child, out);
            child.recycle();
        }
    }

    private boolean clickNodeOrAncestor(AccessibilityNodeInfo start) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(start);
        try {
            for (int depth = 0; depth < 6 && current != null; depth++) {
                if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
        }
        return false;
    }

    private List<Rect> collectGeometryCandidates(AccessibilityNodeInfo root) {
        List<Rect> out = new ArrayList<>();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        collectGeometryCandidates(root, out, dm.widthPixels, dm.heightPixels);
        return dedupe(out);
    }

    private void collectGeometryCandidates(AccessibilityNodeInfo node, List<Rect> out, int screenW, int screenH) {
        if (node == null) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.isEmpty()) {
            int w = r.width(), h = r.height();
            boolean lower = r.centerY() > screenH * 0.38f;
            boolean plausibleSize = w > screenW * 0.12f && w < screenW * 0.45f
                    && h > screenH * 0.035f && h < screenH * 0.20f;
            boolean interactive = node.isClickable() || node.isFocusable() || node.getChildCount() == 0;
            if (lower && plausibleSize && interactive) out.add(new Rect(r));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectGeometryCandidates(child, out, screenW, screenH);
            child.recycle();
        }
    }

    private List<Rect> dedupe(List<Rect> in) {
        List<Rect> out = new ArrayList<>();
        for (Rect r : in) {
            boolean duplicate = false;
            for (Rect e : out) {
                if (Math.abs(r.centerX() - e.centerX()) < 8 && Math.abs(r.centerY() - e.centerY()) < 8) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(r);
        }
        return out;
    }

    private Rect findDigitBoundsByGeometry(AccessibilityNodeInfo root, char digit) {
        List<Rect> candidates = collectGeometryCandidates(root);
        if (candidates.size() < 10) return null;

        Collections.sort(candidates, Comparator.comparingInt(Rect::centerY).thenComparingInt(Rect::centerX));
        List<List<Rect>> rows = new ArrayList<>();
        int tolerance = Math.max(18, getResources().getDisplayMetrics().heightPixels / 35);
        for (Rect r : candidates) {
            List<Rect> target = null;
            for (List<Rect> row : rows) {
                if (Math.abs(avgY(row) - r.centerY()) <= tolerance) {
                    target = row;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                rows.add(target);
            }
            target.add(r);
        }
        for (List<Rect> row : rows) Collections.sort(row, Comparator.comparingInt(Rect::centerX));
        rows.sort(Comparator.comparingInt(this::avgY));

        List<List<Rect>> keypadRows = new ArrayList<>();
        for (List<Rect> row : rows) {
            if (row.size() >= 3) keypadRows.add(row);
        }
        if (keypadRows.size() < 3) return null;

        List<List<Rect>> numericRows = keypadRows.subList(Math.max(0, keypadRows.size() - 3), keypadRows.size());
        int n = digit - '0';
        if (n >= 1 && n <= 9) {
            int idx = n - 1;
            List<Rect> row = numericRows.get(idx / 3);
            if (row.size() < 3) return null;
            return new Rect(row.get(centerThreeIndex(row, idx % 3)));
        }

        int lastY = avgY(numericRows.get(2));
        Rect best = null;
        int centerX = getResources().getDisplayMetrics().widthPixels / 2;
        for (Rect r : candidates) {
            if (r.centerY() <= lastY + tolerance / 2) continue;
            if (best == null || Math.abs(r.centerX() - centerX) < Math.abs(best.centerX() - centerX)) best = r;
        }
        return best == null ? null : new Rect(best);
    }

    private int centerThreeIndex(List<Rect> row, int col) {
        if (row.size() == 3) return col;
        int start = Math.max(0, (row.size() - 3) / 2);
        return Math.min(row.size() - 1, start + col);
    }

    private int avgY(List<Rect> row) {
        int sum = 0;
        for (Rect r : row) sum += r.centerY();
        return row.isEmpty() ? 0 : sum / row.size();
    }

    private void tapFallback(char digit, Runnable done) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels;
        float[] xs = {0.25f, 0.50f, 0.75f};
        float[] ys = {0.58f, 0.68f, 0.78f};
        int n = digit - '0';
        float x, y;
        if (n == 0) {
            x = 0.50f * w;
            y = 0.88f * h;
        } else {
            int k = n - 1;
            x = xs[k % 3] * w;
            y = ys[k / 3] * h;
        }
        tap(x, y, done);
    }

    private void tap(float x, float y, Runnable done) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 65)).build();
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                if (done != null) done.run();
            }
            @Override public void onCancelled(GestureDescription g) {
                saveDiag("Android canceló el gesto de Accesibilidad");
                cancelFill();
            }
        }, null);
    }

    private String eventSummary(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getClassName() != null) sb.append(event.getClassName()).append(' ');
        if (event.getContentDescription() != null) sb.append(event.getContentDescription()).append(' ');
        for (CharSequence s : event.getText()) if (s != null) sb.append(s).append(' ');
        return sb.toString();
    }

    private String join(CharSequence... parts) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence p : parts) if (p != null) sb.append(p).append(' ');
        return sb.toString();
    }

    private void saveDiag(String message) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("last_diag", message)
                .putLong("last_diag_time", System.currentTimeMillis())
                .apply();
    }

    private void cancelFill() {
        fillHandler.removeCallbacksAndMessages(null);
        filling = false;
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        saveDiag("Servicio de Accesibilidad conectado; esperando BYD");
    }

    @Override public void onInterrupt() {
        cancelFill();
        saveDiag("Servicio de Accesibilidad interrumpido");
    }

    @Override public void onDestroy() {
        scanHandler.removeCallbacksAndMessages(null);
        cancelFill();
        super.onDestroy();
    }

    private static final class DigitProfile {
        final int uniqueDigits;
        final String digits;
        DigitProfile(int uniqueDigits, String digits) {
            this.uniqueDigits = uniqueDigits;
            this.digits = digits;
        }
    }
}
