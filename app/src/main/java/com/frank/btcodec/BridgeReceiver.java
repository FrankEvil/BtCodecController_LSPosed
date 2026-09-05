package com.frank.btcodec;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

public final class BridgeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        SharedPreferences p = context.getSharedPreferences(Contract.PREF, 0);
        SharedPreferences.Editor e = p.edit();

        if (Contract.ACTION_STATUS.equals(intent.getAction())) {
            e.putBoolean("status_connected", intent.getBooleanExtra("connected", false));
            e.putString("status_mac", intent.getStringExtra("mac"));
            e.putString("status_name", intent.getStringExtra("name"));
            e.putString("status_current", intent.getStringExtra("current"));
            e.putBoolean("support_sbc", intent.getBooleanExtra("support_sbc", false));
            e.putBoolean("support_aac", intent.getBooleanExtra("support_aac", false));
            e.putBoolean("support_v5", intent.getBooleanExtra("support_v5", false));
            e.putBoolean("support_v3", intent.getBooleanExtra("support_v3", false));
            e.putInt("type_v5", intent.getIntExtra("type_v5", -1));
            e.putInt("type_v3", intent.getIntExtra("type_v3", -1));
            e.putLong("status_time", System.currentTimeMillis());
            e.apply();
            return;
        }

        if (Contract.ACTION_RESULT.equals(intent.getAction())) {
            String mac = intent.getStringExtra("mac");
            String requested = intent.getStringExtra("requested");
            String actual = intent.getStringExtra("actual");
            boolean success = intent.getBooleanExtra("success", false);
            String fallback = intent.getStringExtra("fallback");

            if (mac != null) {
                if (success && actual != null && !actual.isEmpty()) {
                    e.putString(Contract.key("last_success", mac), actual);
                } else if (Contract.LHDC_V3.equals(requested)
                        && fallback != null && !fallback.isEmpty()) {
                    e.putString(Contract.key("desired", mac), fallback);
                    e.putString(Contract.key("last_success", mac), fallback);
                    Toast.makeText(context,
                            "LHDC V3 切换失败，已恢复 " + pretty(fallback),
                            Toast.LENGTH_LONG).show();
                }
            }
            e.putString("last_result",
                    success ? "切换成功：" + pretty(actual)
                            : "切换失败：" + pretty(requested));
            e.putLong("last_result_time", System.currentTimeMillis());
            e.apply();
        }
    }

    private static String pretty(String v) {
        if (Contract.LHDC_V5.equals(v)) return "LHDC V5";
        if (Contract.LHDC_V3.equals(v)) return "LHDC V3";
        return v == null ? "" : v;
    }
}
