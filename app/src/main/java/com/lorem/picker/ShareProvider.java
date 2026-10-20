package com.lorem.picker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** 只管发我们在 cache/send 里准备好的那几张图，别的什么都不给。 */
public class ShareProvider extends ContentProvider {
    public static final String AUTHORITY = "com.lorem.picker.share";

    public static File sendDir(android.content.Context c) {
        File d = new File(c.getCacheDir(), "send");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = new File(uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment()).getName();
        File f = new File(sendDir(getContext()), name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/gif";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
