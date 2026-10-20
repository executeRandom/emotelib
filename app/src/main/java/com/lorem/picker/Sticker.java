package com.lorem.picker;

import android.net.Uri;

public class Sticker {
    public final String name;
    public final Uri uri;
    public final int frames;
    public final long size;
    public final long mtime;

    public Sticker(String name, Uri uri, int frames, long size, long mtime) {
        this.name = name;
        this.uri = uri;
        this.frames = frames;
        this.size = size;
        this.mtime = mtime;
    }

    public boolean isAnim() {
        return frames > 1;
    }
}
