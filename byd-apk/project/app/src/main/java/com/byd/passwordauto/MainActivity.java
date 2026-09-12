package com.byd.passwordauto;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends android.app.Activity {
    private SharedPreferences prefs;
    private SecureStore secureStore;
    private EditText password;
    private TextView status;
    private Switch automationSwitch;
    private boolean updatingSwitch;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        secureStore = new SecureStore(this);
        password = findViewById(R.id.password);
        status = findViewById(R.id.status);
        automationSwitch = findViewById(R.id.automation_switch);
        password.setText("");
        findViewById(R.id.save).setOnClickListener(v -> savePassword());
        findViewById(R.id.accessibility).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        automationSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingSwitch) return;
            prefs.edit().putBoolean("automation_enabled", checked).apply();
            updateStatus();
        });
        updateStatus();
    }

    private void savePassword() {
        String p = password.getText().toString().trim();
        if (!p.matches("\\d{6}")) {
            password.setError("Debe ser una contraseña de 6 dígitos");
            return;
        }
        try {
            secureStore.savePassword(p);
            password.setText("");
            password.clearFocus();
            Toast.makeText(this, "Contraseña guardada de forma local", Toast.LENGTH_SHORT).show();
            updateStatus();
        } catch (Exception e) {
            Toast.makeText(this, "No fue posible guardar la contraseña", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        password.setText("");
        updateStatus();
    }

    private void updateStatus() {
        boolean enabled = prefs.getBoolean("automation_enabled", false);
        updatingSwitch = true;
        automationSwitch.setChecked(enabled);
        updatingSwitch = false;
        status.setText("Contraseña: " + (secureStore.hasPassword() ? "configurada" : "no configurada") + "\n" +
                "Automatización: " + (enabled ? "ACTIVA" : "inactiva") + "\n" +
                "Servicio de Accesibilidad: " + (isAccessibilityServiceEnabled() ? "ACTIVO" : "inactivo"));
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : list) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            String pkg = info.getResolveInfo().serviceInfo.packageName;
            String name = info.getResolveInfo().serviceInfo.name;
            if (getPackageName().equals(pkg) && name != null && name.endsWith("BydPasswordService")) return true;
        }
        return false;
    }
}
