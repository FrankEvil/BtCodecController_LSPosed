package com.frank.btcodec;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int ID_SBC = 1001;
    private static final int ID_AAC = 1002;
    private static final int ID_V5 = 1003;
    private static final int ID_V3 = 1004;

    private SharedPreferences prefs;
    private TextView deviceText;
    private TextView codecText;
    private TextView statusText;
    private Switch master;
    private RadioGroup group;
    private RadioButton sbc;
    private RadioButton aac;
    private RadioButton v5;
    private RadioButton v3;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean updating;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(Contract.PREF, 0);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("蓝牙 Codec 控制");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("关闭总开关：完全不干预系统。开启后：按当前耳机记忆 Codec，蓝牙重启/耳机重连后自动重新应用。");
        desc.setTextSize(14);
        desc.setPadding(0, dp(8), 0, dp(18));
        root.addView(desc);

        master = new Switch(this);
        master.setText("总开关");
        master.setTextSize(18);
        master.setChecked(prefs.getBoolean("enabled", false));
        root.addView(master);

        deviceText = label(root, "当前耳机：正在读取…");
        codecText = label(root, "当前 Codec：正在读取…");

        TextView choose = label(root, "目标 Codec");
        choose.setTypeface(Typeface.DEFAULT_BOLD);
        choose.setPadding(0, dp(18), 0, dp(4));

        group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        sbc = radio("SBC", ID_SBC);
        aac = radio("AAC", ID_AAC);
        v5 = radio("LHDC V5", ID_V5);
        v3 = radio("LHDC V3（实验）", ID_V3);
        group.addView(sbc);
        group.addView(aac);
        group.addView(v5);
        group.addView(v3);
        root.addView(group);

        Button refresh = new Button(this);
        refresh.setText("刷新当前耳机 / Codec");
        refresh.setOnClickListener(v -> queryStatus());
        root.addView(refresh);

        statusText = label(root, "");
        statusText.setPadding(0, dp(14), 0, dp(20));

        master.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            setControlsEnabled(checked);
            if (checked) {
                queryStatus();
                h.postDelayed(() -> {
                    refreshUi();
                    String mac = prefs.getString("status_mac", "");
                    if (!mac.isEmpty()) {
                        String desired = prefs.getString(Contract.key("desired", mac), "");
                        if (!desired.isEmpty()) sendApply(mac);
                    }
                }, 700);
            }
        });

        group.setOnCheckedChangeListener((g, id) -> {
            if (updating || !master.isChecked()) return;
            String mac = prefs.getString("status_mac", "");
            if (mac.isEmpty() || !prefs.getBoolean("status_connected", false)) {
                Toast.makeText(this, "当前没有可用的 A2DP 耳机", Toast.LENGTH_SHORT).show();
                return;
            }
            String desired = idToCodec(id);
            if (desired == null) return;
            prefs.edit().putString(Contract.key("desired", mac), desired).apply();
            statusText.setText("正在切换到 " + pretty(desired) + "…");
            sendApply(mac);
            h.postDelayed(this::queryStatus, 900);
            h.postDelayed(this::refreshUi, 1600);
            h.postDelayed(this::refreshUi, 2800);
        });

        setControlsEnabled(master.isChecked());
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        queryStatus();
        h.postDelayed(this::refreshUi, 500);
        h.postDelayed(this::refreshUi, 1200);
    }

    private TextView label(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setPadding(0, dp(8), 0, dp(4));
        root.addView(t);
        return t;
    }

    private RadioButton radio(String text, int id) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(text);
        r.setTextSize(17);
        r.setPadding(0, dp(5), 0, dp(5));
        return r;
    }

    private void setControlsEnabled(boolean enabled) {
        group.setEnabled(enabled);
        sbc.setEnabled(enabled);
        aac.setEnabled(enabled);
        v5.setEnabled(enabled);
        v3.setEnabled(enabled);
    }

    private void refreshUi() {
        boolean connected = prefs.getBoolean("status_connected", false);
        String mac = prefs.getString("status_mac", "");
        String name = prefs.getString("status_name", "");
        String current = prefs.getString("status_current", "");

        if (!connected || mac.isEmpty()) {
            deviceText.setText("当前耳机：未连接");
            codecText.setText("当前 Codec：—");
            statusText.setText("等待 A2DP 耳机连接");
            return;
        }

        deviceText.setText("当前耳机：" + (name == null || name.isEmpty() ? "未知设备" : name)
                + "\n" + mac);
        codecText.setText("当前 Codec：" + pretty(current));

        boolean enabled = master.isChecked();
        sbc.setEnabled(enabled && prefs.getBoolean("support_sbc", true));
        aac.setEnabled(enabled && prefs.getBoolean("support_aac", true));
        v5.setEnabled(enabled && prefs.getBoolean("support_v5", false));
        v3.setEnabled(enabled && prefs.getBoolean("support_v3", false));

        String desired = prefs.getString(Contract.key("desired", mac), "");
        updating = true;
        if (!desired.isEmpty()) {
            int id = codecToId(desired);
            if (id != -1) group.check(id);
        } else {
            int id = codecToId(current);
            if (id != -1) group.check(id);
        }
        updating = false;

        int v5Type = prefs.getInt("type_v5", -1);
        int v3Type = prefs.getInt("type_v3", -1);
        String last = prefs.getString("last_result", "");
        statusText.setText(
                "动态识别：V5 type=" + v5Type + "，V3 type=" + v3Type
                        + (last.isEmpty() ? "" : "\n" + last));
    }

    private void queryStatus() {
        Intent i = new Intent(Contract.ACTION_QUERY);
        i.setPackage(Contract.BT_PACKAGE);
        sendBroadcast(i);
        h.postDelayed(this::refreshUi, 650);
    }

    private void sendApply(String mac) {
        Intent i = new Intent(Contract.ACTION_APPLY);
        i.setPackage(Contract.BT_PACKAGE);
        i.putExtra("mac", mac);
        sendBroadcast(i);
    }

    private String idToCodec(int id) {
        if (id == ID_SBC) return Contract.SBC;
        if (id == ID_AAC) return Contract.AAC;
        if (id == ID_V5) return Contract.LHDC_V5;
        if (id == ID_V3) return Contract.LHDC_V3;
        return null;
    }

    private int codecToId(String c) {
        if (Contract.SBC.equals(c)) return ID_SBC;
        if (Contract.AAC.equals(c)) return ID_AAC;
        if (Contract.LHDC_V5.equals(c)) return ID_V5;
        if (Contract.LHDC_V3.equals(c)) return ID_V3;
        return -1;
    }

    private String pretty(String c) {
        if (Contract.LHDC_V5.equals(c)) return "LHDC V5";
        if (Contract.LHDC_V3.equals(c)) return "LHDC V3";
        return c == null || c.isEmpty() ? "未知" : c;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
