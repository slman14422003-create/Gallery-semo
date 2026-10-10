package com.gallery.app;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.View;

import org.videolan.libvlc.IMedia;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/** libVLC backend: plays practically every container and codec (AVI, FLV, WMV, RMVB, DTS, AC3, HEVC 10-bit...). */
final class VlcEngine implements PlayerEngine {
    private final Context ctx;
    private final Callback cb;
    private final LibVLC vlc;
    private final MediaPlayer mp;
    private final VLCVideoLayout layout;
    private ParcelFileDescriptor pfd;
    private boolean loop = false;
    private Uri uri;
    private boolean hw;
    private volatile boolean released = false;

    VlcEngine(Context c, Callback cb, boolean hw) {
        this.ctx = c;
        this.cb = cb;
        this.hw = hw;
        ArrayList<String> o = new ArrayList<>();
        o.add("--audio-time-stretch");
        o.add("--avcodec-skiploopfilter=1");
        o.add("--no-drop-late-frames");
        o.add("--no-skip-frames");
        vlc = new LibVLC(c, o);
        mp = new MediaPlayer(vlc);
        layout = new VLCVideoLayout(c);
        mp.attachViews(layout, null, true, false);
        mp.setEventListener(ev -> {
            if (released) return;
            switch (ev.type) {
                case MediaPlayer.Event.Playing:
                    this.cb.onBuffering(false);
                    this.cb.onPlaying(true);
                    this.cb.onReady();
                    break;
                case MediaPlayer.Event.Paused:
                case MediaPlayer.Event.Stopped:
                    this.cb.onPlaying(false);
                    break;
                case MediaPlayer.Event.Buffering:
                    this.cb.onBuffering(ev.getBuffering() < 100f);
                    break;
                case MediaPlayer.Event.EndReached:
                    if (loop && uri != null) open(uri, 0, true);
                    else this.cb.onEnded();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    this.cb.onError("vlc", false);
                    break;
                case MediaPlayer.Event.ESAdded:
                case MediaPlayer.Event.ESDeleted:
                    this.cb.onTracksChanged();
                    break;
                case MediaPlayer.Event.Vout:
                    this.cb.onVideoSize(0, 0);
                    break;
                default:
                    break;
            }
        });
    }

    @Override public String name() { return "VLC"; }

    @Override public View view() { return layout; }

    @Override
    public void open(Uri u, long startMs, boolean play) {
        uri = u;
        try {
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) { }
                pfd = null;
            }
            org.videolan.libvlc.Media m;
            if ("content".equals(u.getScheme())) {
                pfd = ctx.getContentResolver().openFileDescriptor(u, "r");
                m = new org.videolan.libvlc.Media(vlc, pfd.getFileDescriptor());
            } else {
                m = new org.videolan.libvlc.Media(vlc, u);
            }
            m.setHWDecoderEnabled(hw, false);
            if (startMs > 0) m.addOption(":start-time=" + (startMs / 1000.0));
            mp.setMedia(m);
            m.release();
            if (play) mp.play();
        } catch (Exception e) {
            cb.onError("open", false);
        }
    }

    @Override public void play() { mp.play(); }

    @Override public void pause() { mp.pause(); }

    @Override public boolean isPlaying() { return mp.isPlaying(); }

    @Override public long position() { return Math.max(0, mp.getTime()); }

    @Override public long duration() { return Math.max(0, mp.getLength()); }

    @Override public void seekTo(long ms) { mp.setTime(Math.max(0, ms)); }

    @Override public void setSpeed(float s) { mp.setRate(s); }

    @Override public void setLooping(boolean b) { loop = b; }

    @Override
    public void setAspect(int mode) {
        MediaPlayer.ScaleType t;
        switch (mode) {
            case STRETCH: t = MediaPlayer.ScaleType.SURFACE_FILL; break;
            case CROP: t = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN; break;
            case R16_9: t = MediaPlayer.ScaleType.SURFACE_16_9; break;
            case R4_3: t = MediaPlayer.ScaleType.SURFACE_4_3; break;
            default: t = MediaPlayer.ScaleType.SURFACE_BEST_FIT;
        }
        mp.setVideoScale(t);
    }

    private List<Track> conv(MediaPlayer.TrackDescription[] d, int current) {
        List<Track> r = new ArrayList<>();
        if (d == null) return r;
        for (MediaPlayer.TrackDescription t : d) {
            if (t.id < 0) continue;
            Track x = new Track();
            x.label = t.name == null ? ("#" + (r.size() + 1)) : t.name;
            x.id = t.id;
            x.selected = t.id == current;
            r.add(x);
        }
        return r;
    }

    @Override public List<Track> audioTracks() { return conv(mp.getAudioTracks(), mp.getAudioTrack()); }

    @Override public List<Track> textTracks() { return conv(mp.getSpuTracks(), mp.getSpuTrack()); }

    @Override
    public void selectAudio(int idx) {
        List<Track> l = audioTracks();
        if (idx >= 0 && idx < l.size()) mp.setAudioTrack(l.get(idx).id);
    }

    @Override
    public void selectText(int idx) {
        if (idx < 0) {
            mp.setSpuTrack(-1);
            return;
        }
        List<Track> l = textTracks();
        if (idx < l.size()) mp.setSpuTrack(l.get(idx).id);
    }

    @Override
    public void addSubtitle(Uri sub, String ext) {
        try {
            mp.addSlave(IMedia.Slave.Type.Subtitle, sub, true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void release() {
        released = true;
        final MediaPlayer p = mp;
        final LibVLC v = vlc;
        final ParcelFileDescriptor f = pfd;
        new Thread(() -> {
            try {
                p.setEventListener(null);
                p.stop();
                p.detachViews();
                p.release();
                v.release();
            } catch (Exception ignored) {
            }
            try {
                if (f != null) f.close();
            } catch (Exception ignored) {
            }
        }).start();
    }
}
