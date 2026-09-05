package com.frank.btcodec;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class ConfigProvider extends ContentProvider {
    private static final String[] COLUMNS = {
            "enabled", "desired", "last_success"
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        SharedPreferences p = getContext().getSharedPreferences(Contract.PREF, 0);
        String mac = uri.getQueryParameter("mac");
        boolean enabled = p.getBoolean("enabled", false);
        String desired = mac == null ? "" :
                p.getString(Contract.key("desired", mac), "");
        String last = mac == null ? "" :
                p.getString(Contract.key("last_success", mac), "");
        c.addRow(new Object[]{enabled ? 1 : 0, desired, last});
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/btcodec"; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
