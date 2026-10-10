package com.gallery.app;

import android.net.Uri;
import android.view.View;

import java.util.List;

/** A video backend. ExoPlayer is the fast default; libVLC plays whatever Exo cannot. */
interface PlayerEngine {
    int FIT = 0, STRETCH = 1, CROP = 2, R16_9 = 3, R4_3 = 4;

    final class Track {
        String label;
        boolean selected;
        Object ref;
        int index;
        int id;
    }

    interface Callback {
        void onPlaying(boolean playing);

        void onBuffering(boolean buffering);

        void onReady();

        void onEnded();

        /** {@code unsupported}: this engine cannot decode the file (try the other engine). */
        void onError(String msg, boolean unsupported);

        void onVideoSize(int w, int h);

        void onTracksChanged();
    }

    String name();

    View view();

    void open(Uri uri, long startMs, boolean play);

    void play();

    void pause();

    boolean isPlaying();

    long position();

    long duration();

    void seekTo(long ms);

    void setSpeed(float s);

    void setLooping(boolean b);

    void setAspect(int mode);

    List<Track> audioTracks();

    List<Track> textTracks();

    void selectAudio(int idx);

    /** idx -1 turns subtitles off. */
    void selectText(int idx);

    void addSubtitle(Uri uri, String ext);

    void release();
}
