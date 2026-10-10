package com.gallery.app;

/** A folder of media (or one of the smart albums). */
public final class Album {
    public static final int NORMAL = 0, FAVORITES = 1, VIDEOS = 2, SHOTS = 3;

    public int kind;
    public long bucketId;
    public String name;
    public int count;
    public int videos;
    public Media cover;
}
