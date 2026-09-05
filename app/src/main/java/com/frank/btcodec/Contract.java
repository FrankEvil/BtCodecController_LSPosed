package com.frank.btcodec;

final class Contract {
    static final String PREF = "codec_config";
    static final String AUTHORITY = "com.frank.btcodec.config";

    static final String ACTION_QUERY = "com.frank.btcodec.ACTION_QUERY";
    static final String ACTION_APPLY = "com.frank.btcodec.ACTION_APPLY_NOW";
    static final String ACTION_STATUS = "com.frank.btcodec.ACTION_STATUS";
    static final String ACTION_RESULT = "com.frank.btcodec.ACTION_RESULT";

    static final String BT_PACKAGE = "com.android.bluetooth";

    static final String SBC = "SBC";
    static final String AAC = "AAC";
    static final String LHDC_V5 = "LHDC_V5";
    static final String LHDC_V3 = "LHDC_V3";

    static String key(String prefix, String mac) {
        return prefix + "_" + normalize(mac);
    }

    static String normalize(String mac) {
        return mac == null ? "" : mac.toUpperCase().replace(':', '_');
    }

    private Contract() {}
}
