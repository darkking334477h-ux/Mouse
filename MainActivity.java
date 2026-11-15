package com.mousecorsul;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Simple control screen: permissions, Start and Stop.
 * The accessibility service can only be enabled by the user in system settings;
 * this activity just opens that screen.
 */
public class MainActivity extends Activity {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = (TextView) findViewById(R.id.status_text);

        findViewById(R.id.btn_overlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        });

        findViewById(R.id.btn_accessibility).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startMouse();
            }
        });

        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopMouse();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    // ---------------------------------------------------------------- actions

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_overlay_granted, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void startMouse() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_need_overlay, Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, R.string.msg_need_accessibility, Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        MyAccessibilityService service = MyAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, R.string.msg_service_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        if (service.startMouse()) {
            Toast.makeText(this, R.string.msg_started, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.msg_start_failed, Toast.LENGTH_LONG).show();
        }
        updateStatus();
    }

    private void stopMouse() {
        MyAccessibilityService service = MyAccessibilityService.getInstance();
        if (service != null && service.isRunning()) {
            service.stopMouse();
            Toast.makeText(this, R.string.msg_stopped, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.msg_not_running, Toast.LENGTH_SHORT).show();
        }
        updateStatus();
    }

    // ----------------------------------------------------------------- status

    private void updateStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean accessibility = isAccessibilityServiceEnabled();
        MyAccessibilityService service = MyAccessibilityService.getInstance();
        boolean running = service != null && service.isRunning();

        String text = "Overlay permission: " + (overlay ? "GRANTED" : "MISSING")
                + "\nAccessibility service: " + (accessibility ? "ENABLED" : "DISABLED")
                + "\nMouse: " + (running ? "RUNNING" : "STOPPED");
        statusText.setText(text);
    }

    private boolean isAccessibilityServiceEnabled() {
        int enabled = 0;
        try {
            enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            enabled = 0;
        }
        if (enabled != 1) {
            return false;
        }

        String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) {
            return false;
        }

        ComponentName mine = new ComponentName(this, MyAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(services);
        while (splitter.hasNext()) {
            ComponentName cn = ComponentName.unflattenFromString(splitter.next());
            if (cn != null && cn.equals(mine)) {
                return true;
            }
        }
        return false;
    }
}
