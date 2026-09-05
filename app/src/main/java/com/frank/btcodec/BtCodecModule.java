package com.frank.btcodec;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class BtCodecModule extends XposedModule {
    private static final String TAG = "BtCodecCtrl";
    private static final long LHDC_V5_CODEC_ID = 0x4c35053affL;

    private ClassLoader cl;
    private Context ctx;
    private Handler handler;
    private volatile boolean installed;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Contract.BT_PACKAGE.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;
        if (installed) return;
        installed = true;
        cl = param.getClassLoader();
        handler = new Handler(Looper.getMainLooper());

        handler.postDelayed(() -> {
            ctx = currentApplication();
            if (ctx == null) {
                Log.e(TAG, "target Application unavailable");
                return;
            }
            installReceiver();
            scheduleApply(null, 800);
            scheduleApply(null, 2200);
            scheduleApply(null, 4500);
            sendStatus(null);
            Log.i(TAG, "Bluetooth codec controller ready");
        }, 300);
    }

    private Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable t) {
            Log.e(TAG, "currentApplication", t);
            return null;
        }
    }

    private void installReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED");
        f.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
        f.addAction(Contract.ACTION_QUERY);
        f.addAction(Contract.ACTION_APPLY);

        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                BluetoothDevice device = null;
                try {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } catch (Throwable ignored) {
                    try {
                        //noinspection deprecation
                        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    } catch (Throwable ignored2) {}
                }

                if (Contract.ACTION_QUERY.equals(action)) {
                    sendStatus(device);
                    return;
                }

                if (Contract.ACTION_APPLY.equals(action)) {
                    String mac = intent.getStringExtra("mac");
                    BluetoothDevice d = findDeviceByMac(mac);
                    scheduleApply(d, 80);
                    scheduleApply(d, 900);
                    return;
                }

                if ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)) {
                    int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        scheduleApply(device, 700);
                        scheduleApply(device, 1800);
                        scheduleApply(device, 3600);
                    }
                    sendStatus(device);
                    return;
                }

                if ("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED".equals(action)) {
                    scheduleApply(device, 600);
                    scheduleApply(device, 1700);
                    sendStatus(device);
                    return;
                }

                if ("android.bluetooth.adapter.action.STATE_CHANGED".equals(action)) {
                    int state = intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1);
                    if (state == 12 /* BluetoothAdapter.STATE_ON */) {
                        scheduleApply(null, 900);
                        scheduleApply(null, 2400);
                        scheduleApply(null, 4800);
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            //noinspection UnspecifiedRegisterReceiverFlag
            ctx.registerReceiver(r, f);
        }
    }

    private void scheduleApply(BluetoothDevice d, long delay) {
        handler.postDelayed(() -> applyForDevice(d), delay);
    }

    private void applyForDevice(BluetoothDevice preferred) {
        try {
            Object service = a2dpService();
            if (service == null) return;
            BluetoothDevice device = preferred != null ? preferred : activeDevice(service);
            if (device == null) return;

            Config cfg = readConfig(device.getAddress());
            if (!cfg.enabled || cfg.desired == null || cfg.desired.isEmpty()) return;

            Object status = getCodecStatus(service, device);
            if (status == null) return;
            Catalog catalog = Catalog.fromStatus(status);

            Object target = catalog.forKey(cfg.desired);
            if (target == null) {
                if (Contract.LHDC_V3.equals(cfg.desired)) {
                    failV3AndFallback(service, device, cfg, catalog, "V3 capability not found");
                }
                sendResult(device, cfg.desired, catalog.currentKey(), false, "");
                return;
            }

            if (Contract.LHDC_V3.equals(cfg.desired) && !catalog.v3Selectable) {
                // V3 exists only in LocalCapabilities on this ROM. The normal
                // A2dpService API rejects non-selectable codecs. Try the native
                // preference path once, then read back and fall back on failure.
                boolean dispatched = tryNativeCodecPreference(service, device, target);
                if (!dispatched) {
                    failV3AndFallback(service, device, cfg, catalog,
                            "V3 is local-only and native dispatch unavailable");
                    return;
                }
                handler.postDelayed(() -> confirm(service, device, cfg), 1600);
                return;
            }

            setCodec(service, device, target);
            handler.postDelayed(() -> confirm(service, device, cfg), 1300);
        } catch (Throwable t) {
            Log.e(TAG, "applyForDevice", t);
        }
    }

    private void confirm(Object service, BluetoothDevice device, Config cfg) {
        try {
            Object status = getCodecStatus(service, device);
            if (status == null) return;
            Catalog catalog = Catalog.fromStatus(status);
            String actual = catalog.currentKey();
            boolean ok = cfg.desired.equals(actual);
            if (ok) {
                sendResult(device, cfg.desired, actual, true, "");
                return;
            }

            if (Contract.LHDC_V3.equals(cfg.desired)) {
                failV3AndFallback(service, device, cfg, catalog,
                        "readback=" + actual);
            } else {
                sendResult(device, cfg.desired, actual, false, "");
            }
        } catch (Throwable t) {
            Log.e(TAG, "confirm", t);
        }
    }

    private void failV3AndFallback(Object service, BluetoothDevice device,
                                   Config cfg, Catalog catalog, String reason) {
        String fallback = safeFallback(cfg.lastSuccess, catalog);
        Object fb = catalog.forKey(fallback);
        if (fb != null) {
            try {
                setCodec(service, device, fb);
            } catch (Throwable t) {
                Log.e(TAG, "fallback", t);
            }
        }
        Log.w(TAG, "LHDC V3 failed: " + reason + ", fallback=" + fallback);
        try {
            Toast.makeText(ctx, "LHDC V3 切换失败，已恢复 " + pretty(fallback),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
        sendResult(device, Contract.LHDC_V3, catalog.currentKey(), false, fallback);
        handler.postDelayed(() -> sendStatus(device), 1200);
    }

    private String safeFallback(String last, Catalog c) {
        if (Contract.AAC.equals(last) && c.aac != null) return Contract.AAC;
        if (Contract.SBC.equals(last) && c.sbc != null) return Contract.SBC;
        if (c.aac != null) return Contract.AAC;
        return Contract.SBC;
    }

    private Object a2dpService() throws Exception {
        Class<?> k = Class.forName("com.android.bluetooth.a2dp.A2dpService", false, cl);
        try {
            Method m = k.getDeclaredMethod("getA2dpService");
            m.setAccessible(true);
            return m.invoke(null);
        } catch (NoSuchMethodException ignored) {
            for (Method m : k.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && k.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    if (v != null) return v;
                }
            }
            return null;
        }
    }

    private BluetoothDevice activeDevice(Object service) {
        try {
            Object v = invokeNamed(service, "getActiveDevice");
            if (v instanceof BluetoothDevice) return (BluetoothDevice) v;
        } catch (Throwable ignored) {}

        try {
            Object v = invokeNamed(service, "getConnectedDevices");
            for (Object o : iterable(v)) {
                if (o instanceof BluetoothDevice) return (BluetoothDevice) o;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private BluetoothDevice findDeviceByMac(String mac) {
        if (mac == null || mac.isEmpty()) return null;
        try {
            Object service = a2dpService();
            if (service == null) return null;
            Object v = invokeNamed(service, "getConnectedDevices");
            for (Object o : iterable(v)) {
                if (o instanceof BluetoothDevice) {
                    BluetoothDevice d = (BluetoothDevice) o;
                    if (mac.equalsIgnoreCase(d.getAddress())) return d;
                }
            }
            BluetoothDevice active = activeDevice(service);
            if (active != null && mac.equalsIgnoreCase(active.getAddress())) return active;
        } catch (Throwable ignored) {}
        return null;
    }

    private Object getCodecStatus(Object service, BluetoothDevice d) throws Exception {
        for (Method m : service.getClass().getMethods()) {
            if (!"getCodecStatus".equals(m.getName()) || m.getParameterCount() != 1) continue;
            m.setAccessible(true);
            return m.invoke(service, d);
        }
        for (Method m : service.getClass().getDeclaredMethods()) {
            if (!"getCodecStatus".equals(m.getName()) || m.getParameterCount() != 1) continue;
            m.setAccessible(true);
            return m.invoke(service, d);
        }
        return null;
    }

    private void setCodec(Object service, BluetoothDevice d, Object codec) throws Exception {
        Object forced = cloneWithHighestPriority(codec);
        Log.i(TAG, "request codec: " + forced);

        for (Method m : service.getClass().getMethods()) {
            if (!"setCodecConfigPreference".equals(m.getName()) || m.getParameterCount() != 2) continue;
            m.setAccessible(true);
            m.invoke(service, d, forced);
            return;
        }
        for (Method m : service.getClass().getDeclaredMethods()) {
            if (!"setCodecConfigPreference".equals(m.getName()) || m.getParameterCount() != 2) continue;
            m.setAccessible(true);
            m.invoke(service, d, forced);
            return;
        }
        throw new NoSuchMethodException("setCodecConfigPreference");
    }

    /**
     * A2dpService does not mean "pick exactly this codec" when the requested
     * BluetoothCodecConfig still carries its normal priority. It compares that
     * priority against every selectable codec. On the target ROM LHDC V5 has
     * priority 9002, AAC 2001 and SBC 1001, so passing the capability object
     * unchanged makes LHDC win again.
     *
     * Build a new config with CODEC_PRIORITY_HIGHEST while preserving all
     * feeding and vendor-specific fields.
     */
    private Object cloneWithHighestPriority(Object codec) throws Exception {
        if (codec == null) return null;

        Class<?> cfgClass = Class.forName("android.bluetooth.BluetoothCodecConfig", false, cl);
        Class<?> builderClass = Class.forName(
                "android.bluetooth.BluetoothCodecConfig$Builder", false, cl);
        Object b = builderClass.getDeclaredConstructor().newInstance();

        Object extType = noArg(codec, "getExtendedCodecType");
        if (extType != null) {
            tryInvokeBuilder(b, "setExtendedCodecType", extType);
        } else {
            tryInvokeBuilder(b, "setCodecType", intVal(codec, "getCodecType", -1));
        }

        int highest = 1000000;
        try {
            highest = cfgClass.getField("CODEC_PRIORITY_HIGHEST").getInt(null);
        } catch (Throwable ignored) {}

        tryInvokeBuilder(b, "setCodecPriority", highest);
        tryInvokeBuilder(b, "setSampleRate", intVal(codec, "getSampleRate", 0));
        tryInvokeBuilder(b, "setBitsPerSample", intVal(codec, "getBitsPerSample", 0));
        tryInvokeBuilder(b, "setChannelMode", intVal(codec, "getChannelMode", 0));
        tryInvokeBuilder(b, "setCodecSpecific1", longVal(codec, "getCodecSpecific1", 0));
        tryInvokeBuilder(b, "setCodecSpecific2", longVal(codec, "getCodecSpecific2", 0));
        tryInvokeBuilder(b, "setCodecSpecific3", longVal(codec, "getCodecSpecific3", 0));
        tryInvokeBuilder(b, "setCodecSpecific4", longVal(codec, "getCodecSpecific4", 0));

        Method build = builderClass.getMethod("build");
        return build.invoke(b);
    }

    private static void tryInvokeBuilder(Object builder, String methodName, Object value)
            throws Exception {
        Method best = null;
        for (Method m : builder.getClass().getMethods()) {
            if (!m.getName().equals(methodName) || m.getParameterCount() != 1) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (value instanceof Integer && (p == int.class || p == Integer.class)) {
                best = m; break;
            }
            if (value instanceof Long && (p == long.class || p == Long.class)) {
                best = m; break;
            }
            if (value != null && p.isAssignableFrom(value.getClass())) {
                best = m; break;
            }
        }
        if (best != null) {
            best.setAccessible(true);
            best.invoke(builder, value);
        }
    }

    private boolean tryNativeCodecPreference(
            Object service, BluetoothDevice device, Object codec) {
        try {
            Object codecManager = null;
            Class<?> c = service.getClass();
            while (c != null && codecManager == null) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getName().toLowerCase(Locale.ROOT).contains("codec")
                            && f.getType().getName().contains("A2dpCodecConfig")) {
                        f.setAccessible(true);
                        codecManager = f.get(service);
                        if (codecManager != null) break;
                    }
                }
                c = c.getSuperclass();
            }
            if (codecManager == null) return false;

            Object nativeIf = null;
            c = codecManager.getClass();
            while (c != null && nativeIf == null) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().contains("A2dpNativeInterface")
                            || f.getName().toLowerCase(Locale.ROOT).contains("nativeinterface")) {
                        f.setAccessible(true);
                        nativeIf = f.get(codecManager);
                        if (nativeIf != null) break;
                    }
                }
                c = c.getSuperclass();
            }
            if (nativeIf == null) return false;

            Object forced = cloneWithHighestPriority(codec);
            Class<?> cfgClass = Class.forName(
                    "android.bluetooth.BluetoothCodecConfig", false, cl);
            Object arr = Array.newInstance(cfgClass, 1);
            Array.set(arr, 0, forced);

            for (Method m : nativeIf.getClass().getDeclaredMethods()) {
                if (!"setCodecConfigPreference".equals(m.getName())
                        || m.getParameterCount() != 2) continue;
                m.setAccessible(true);
                m.invoke(nativeIf, device, arr);
                Log.w(TAG, "experimental native V3 preference dispatched: " + forced);
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "tryNativeCodecPreference", t);
        }
        return false;
    }

    private Object invokeNamed(Object obj, String name) throws Exception {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m.invoke(obj);
            }
        }
        for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m.invoke(obj);
            }
        }
        return null;
    }

    private Config readConfig(String mac) {
        Config out = new Config();
        Cursor c = null;
        try {
            Uri uri = new Uri.Builder().scheme("content")
                    .authority(Contract.AUTHORITY)
                    .appendPath("device")
                    .appendQueryParameter("mac", mac)
                    .build();
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                out.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
                out.desired = c.getString(c.getColumnIndexOrThrow("desired"));
                out.lastSuccess = c.getString(c.getColumnIndexOrThrow("last_success"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "readConfig", t);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private void sendStatus(BluetoothDevice hint) {
        handler.postDelayed(() -> {
            try {
                Object service = a2dpService();
                if (service == null) return;
                BluetoothDevice d = hint != null ? hint : activeDevice(service);

                Intent out = new Intent(Contract.ACTION_STATUS);
                out.setComponent(new ComponentName("com.frank.btcodec",
                        "com.frank.btcodec.BridgeReceiver"));

                if (d == null) {
                    out.putExtra("connected", false);
                    ctx.sendBroadcast(out);
                    return;
                }

                Object status = getCodecStatus(service, d);
                Catalog c = status == null ? new Catalog() : Catalog.fromStatus(status);
                out.putExtra("connected", true);
                out.putExtra("mac", d.getAddress());
                try { out.putExtra("name", d.getName()); } catch (Throwable ignored) {}
                out.putExtra("current", c.currentKey());
                out.putExtra("support_sbc", c.sbc != null);
                out.putExtra("support_aac", c.aac != null);
                out.putExtra("support_v5", c.v5 != null);
                out.putExtra("support_v3", c.v3 != null);
                out.putExtra("type_v5", c.v5Type);
                out.putExtra("type_v3", c.v3Type);
                ctx.sendBroadcast(out);
            } catch (Throwable t) {
                Log.e(TAG, "sendStatus", t);
            }
        }, 120);
    }

    private void sendResult(BluetoothDevice d, String requested, String actual,
                            boolean success, String fallback) {
        try {
            Intent out = new Intent(Contract.ACTION_RESULT);
            out.setComponent(new ComponentName("com.frank.btcodec",
                    "com.frank.btcodec.BridgeReceiver"));
            out.putExtra("mac", d == null ? "" : d.getAddress());
            out.putExtra("requested", requested);
            out.putExtra("actual", actual);
            out.putExtra("success", success);
            out.putExtra("fallback", fallback);
            ctx.sendBroadcast(out);
            sendStatus(d);
        } catch (Throwable t) {
            Log.e(TAG, "sendResult", t);
        }
    }

    private static String pretty(String k) {
        if (Contract.LHDC_V5.equals(k)) return "LHDC V5";
        if (Contract.LHDC_V3.equals(k)) return "LHDC V3";
        return k == null ? "" : k;
    }

    private static List<Object> iterable(Object o) {
        List<Object> out = new ArrayList<>();
        if (o == null) return out;
        if (o instanceof Collection<?>) {
            out.addAll((Collection<?>) o);
            return out;
        }
        if (o.getClass().isArray()) {
            int n = Array.getLength(o);
            for (int i = 0; i < n; i++) out.add(Array.get(o, i));
        }
        return out;
    }

    private static Object noArg(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = o.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable ignored) {}
        try {
            Method m = o.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable ignored) {}
        return null;
    }

    private static int intVal(Object o, String name, int def) {
        Object v = noArg(o, name);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static long longVal(Object o, String name, long def) {
        Object v = noArg(o, name);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private static final class Config {
        boolean enabled;
        String desired = "";
        String lastSuccess = "";
    }

    private static final class Desc {
        Object config;
        int type;
        int priority;
        long extId;
        String text;
        boolean lhdc;
        boolean explicitV5;

        static Desc of(Object config) {
            Desc d = new Desc();
            d.config = config;
            d.type = intVal(config, "getCodecType", -1);
            d.priority = intVal(config, "getCodecPriority", 0);

            Object ext = noArg(config, "getExtendedCodecType");
            d.extId = longVal(ext, "getCodecId", -1);
            String extName = String.valueOf(noArg(ext, "getCodecName"));
            d.text = (extName + " " + String.valueOf(config)).toUpperCase(Locale.ROOT);
            d.lhdc = d.text.contains("LHDC");
            d.explicitV5 = d.extId == LHDC_V5_CODEC_ID
                    || d.text.contains("LHDCV5")
                    || d.text.contains("LHDC V5");
            return d;
        }
    }

    private static final class Catalog {
        Object sbc;
        Object aac;
        Object v5;
        Object v3;
        int v5Type = -1;
        int v3Type = -1;
        boolean v5Selectable;
        boolean v3Selectable;
        Object current;

        static Catalog fromStatus(Object status) {
            Catalog c = new Catalog();
            c.current = noArg(status, "getCodecConfig");

            Map<Integer, Desc> selectable = new LinkedHashMap<>();
            Map<Integer, Desc> unique = new LinkedHashMap<>();
            add(selectable, noArg(status, "getCodecsSelectableCapabilities"));
            unique.putAll(selectable);
            add(unique, noArg(status, "getCodecsLocalCapabilities"));

            List<Desc> lhdc = new ArrayList<>();
            for (Desc d : unique.values()) {
                if (d.type == 0) c.sbc = d.config;
                if (d.type == 1) c.aac = d.config;
                if (d.lhdc) lhdc.add(d);
            }

            Desc explicit = null;
            for (Desc d : lhdc) {
                if (d.explicitV5) {
                    explicit = d;
                    break;
                }
            }

            if (explicit == null && !lhdc.isEmpty()) {
                // OPlus stacks commonly expose both LHDC generations with the newer
                // generation having the higher codec priority. This is only a fallback;
                // standard extended codec-id/name detection above wins when available.
                lhdc.sort(Comparator.comparingInt((Desc x) -> x.priority).reversed());
                explicit = lhdc.get(0);
            }

            if (explicit != null) {
                c.v5 = explicit.config;
                c.v5Type = explicit.type;
                c.v5Selectable = selectable.containsKey(explicit.type);
            }

            for (Desc d : lhdc) {
                if (explicit == null || d.type != explicit.type) {
                    c.v3 = d.config;
                    c.v3Type = d.type;
                    c.v3Selectable = selectable.containsKey(d.type);
                    break;
                }
            }

            // On the target K30 Pro / ColorOS 16 port the local stack exposes
            // LHDC V5 + V3 as two LHDC entries. If only one exists, V3 remains
            // unavailable rather than inventing a numeric codec type.
            return c;
        }

        private static void add(Map<Integer, Desc> unique, Object raw) {
            for (Object o : iterable(raw)) {
                Desc d = Desc.of(o);
                if (d.type >= 0 && !unique.containsKey(d.type)) unique.put(d.type, d);
            }
        }

        Object forKey(String key) {
            if (Contract.SBC.equals(key)) return sbc;
            if (Contract.AAC.equals(key)) return aac;
            if (Contract.LHDC_V5.equals(key)) return v5;
            if (Contract.LHDC_V3.equals(key)) return v3;
            return null;
        }

        String currentKey() {
            if (current == null) return "";
            int type = intVal(current, "getCodecType", -1);
            if (type == 0) return Contract.SBC;
            if (type == 1) return Contract.AAC;
            if (type == v5Type && v5Type >= 0) return Contract.LHDC_V5;
            if (type == v3Type && v3Type >= 0) return Contract.LHDC_V3;

            Desc d = Desc.of(current);
            if (d.explicitV5) return Contract.LHDC_V5;
            return d.text.contains("LHDC") ? "LHDC" : "TYPE_" + type;
        }
    }
}
