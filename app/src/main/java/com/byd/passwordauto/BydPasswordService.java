package com.byd.passwordauto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class BydPasswordService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean filling;
    private long lastFillAt;
    private String lastEventText = "";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putLong("service_connected_at", System.currentTimeMillis())
                .putString("last_diag", "Servicio conectado. Esperando eventos.")
                .apply();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (getPackageName().equals(pkg)) return;

        long now = System.currentTimeMillis();
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("last_seen_package", pkg)
                .putLong("last_event_at", now).apply();

        long learnUntil = getSharedPreferences("cfg", MODE_PRIVATE).getLong("learn_until", 0L);
        if (now < learnUntil && learnable(pkg)) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putString("target_package", pkg)
                    .putLong("learn_until", 0L)
                    .putString("last_diag", "App BYD aprendida: " + pkg + ". Abre ahora la pantalla de contraseña.")
                    .apply();
            return;
        }

        if (!isTarget(pkg)) return;
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("automation_enabled", false)) {
            saveDiag("BYD detectado (" + pkg + "), pero automatización está desactivada.");
            return;
        }

        lastEventText = eventText(event);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::scan, 140L);
    }

    private boolean learnable(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        return !p.equals("android") && !p.contains("systemui") && !p.contains("launcher")
                && !p.startsWith("com.sec.android") && !p.startsWith("com.samsung.android");
    }

    private boolean isTarget(String pkg) {
        String learned = getSharedPreferences("cfg", MODE_PRIVATE).getString("target_package", "");
        if (!learned.isEmpty()) return learned.equals(pkg);
        return pkg.toLowerCase(Locale.ROOT).contains("byd");
    }

    private void scan() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            saveDiag("BYD detectado, pero Android no entrega la raíz de la ventana.");
            return;
        }
        boolean prompt = containsPrompt(root) || matchesPrompt(lastEventText);
        boolean password = containsPassword(root);
        Map<Character, Rect> digits = new HashMap<>();
        collectDigits(root, digits);
        String cls = root.getClassName() == null ? "?" : root.getClassName().toString();
        root.recycle();

        boolean compatible = prompt || (password && digits.size() >= 9) || digits.size() == 10;
        saveDiag("Ventana=" + cls + " | textoClave=" + yes(prompt) + " | campoPassword=" + yes(password)
                + " | dígitos visibles=" + digits.size() + "/10 | "
                + (compatible ? "pantalla compatible" : "pantalla aún no compatible"));

        if (!compatible || filling || System.currentTimeMillis() - lastFillAt < 3500L) return;
        String pass = new SecureStore(this).getPassword();
        if (!pass.matches("\\d{6}")) {
            saveDiag("Pantalla compatible, pero no pude leer una contraseña válida guardada.");
            return;
        }
        filling = true;
        lastFillAt = System.currentTimeMillis();
        handler.postDelayed(() -> fill(pass, 0), 220L);
    }

    private void fill(String pass, int index) {
        if (!filling) return;
        if (index >= 6) {
            filling = false;
            saveDiag("Contraseña enviada: 6/6 pulsaciones.");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            filling = false;
            saveDiag("Se perdió la ventana en la pulsación " + (index + 1) + "/6.");
            return;
        }
        char d = pass.charAt(index);
        AccessibilityNodeInfo node = findDigit(root, d);
        if (node != null) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            boolean clicked = clickSelfOrParent(node);
            node.recycle();
            root.recycle();
            if (clicked) {
                saveDiag("Introduciendo: " + (index + 1) + "/6 por nodo accesible.");
                handler.postDelayed(() -> fill(pass, index + 1), 170L);
                return;
            }
            if (!r.isEmpty()) {
                saveDiag("Introduciendo: " + (index + 1) + "/6 por posición del nodo.");
                tap(r.centerX(), r.centerY(), () -> handler.postDelayed(() -> fill(pass, index + 1), 170L));
                return;
            }
        }

        Map<Character, Rect> digits = new HashMap<>();
        collectDigits(root, digits);
        root.recycle();
        Rect r = digits.get(d);
        if (r != null && !r.isEmpty()) {
            saveDiag("Introduciendo: " + (index + 1) + "/6 por geometría accesible.");
            tap(r.centerX(), r.centerY(), () -> handler.postDelayed(() -> fill(pass, index + 1), 170L));
        } else {
            filling = false;
            saveDiag("Detecté la pantalla, pero Android no expone el botón " + d + ".");
        }
    }

    private boolean containsPrompt(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (matchesPrompt(join(n.getText(), n.getContentDescription(), n.getHintText()))) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            boolean ok = containsPrompt(c);
            c.recycle();
            if (ok) return true;
        }
        return false;
    }

    private boolean containsPassword(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isPassword()) return true;
        String t = norm(join(n.getText(), n.getContentDescription(), n.getHintText()));
        if (t.contains("contrasena") || t.contains("password") || t.contains("operacion")) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            boolean ok = containsPassword(c);
            c.recycle();
            if (ok) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findDigit(AccessibilityNodeInfo n, char digit) {
        if (n == null) return null;
        if (isDigitNode(n, digit)) return AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo found = findDigit(c, digit);
            c.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private void collectDigits(AccessibilityNodeInfo n, Map<Character, Rect> out) {
        if (n == null) return;
        String s = digitText(n);
        if (s.length() == 1 && Character.isDigit(s.charAt(0))) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!r.isEmpty()) out.putIfAbsent(s.charAt(0), r);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            collectDigits(c, out);
            c.recycle();
        }
    }

    private boolean isDigitNode(AccessibilityNodeInfo n, char d) {
        String s = digitText(n);
        return s.length() == 1 && s.charAt(0) == d;
    }

    private String digitText(AccessibilityNodeInfo n) {
        String t = n.getText() == null ? "" : n.getText().toString().trim();
        if (t.length() == 1 && Character.isDigit(t.charAt(0))) return t;
        String c = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        return c.length() == 1 && Character.isDigit(c.charAt(0)) ? c : "";
    }

    private boolean clickSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                cur.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            cur.recycle();
            cur = parent;
        }
        if (cur != null) cur.recycle();
        return false;
    }

    private void tap(float x, float y, Runnable done) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 70)).build();
        dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                if (done != null) done.run();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                filling = false;
                saveDiag("Android canceló el gesto de Accesibilidad.");
            }
        }, null);
    }

    private boolean matchesPrompt(CharSequence text) {
        String t = norm(text == null ? "" : text.toString());
        return t.contains("introduzca contrasena de operacion")
                || (t.contains("contrasena") && t.contains("operacion"));
    }

    private String eventText(AccessibilityEvent e) {
        StringBuilder b = new StringBuilder();
        if (e.getContentDescription() != null) b.append(e.getContentDescription()).append(' ');
        for (CharSequence s : e.getText()) if (s != null) b.append(s).append(' ');
        return b.toString();
    }

    private String join(CharSequence... xs) {
        StringBuilder b = new StringBuilder();
        for (CharSequence x : xs) if (x != null) b.append(x).append(' ');
        return b.toString();
    }

    private String norm(String s) {
        String x = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return x.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String yes(boolean b) { return b ? "sí" : "no"; }

    private void saveDiag(String s) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("last_diag", s).apply();
    }

    @Override public void onInterrupt() {
        filling = false;
        handler.removeCallbacksAndMessages(null);
        saveDiag("Servicio interrumpido por Android.");
    }
}
