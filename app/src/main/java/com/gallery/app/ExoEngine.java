package com.gallery.app;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.SubtitleView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ExoPlayer (Media3) backend: hardware decoding, smooth seeking, embedded and external subtitles. */
@OptIn(markerClass = UnstableApi.class)
final class ExoEngine implements PlayerEngine {
    private final Context ctx;
    private final Callback cb;
    private final ExoPlayer player;
    private final AspectFrame frame;
    private final SurfaceView sv;
    private final SubtitleView subs;
    private final FrameLayout root;
    private Uri uri;
    private boolean checkedTracks = false;

    ExoEngine(Context c, Callback cb) {
        this.ctx = c;
        this.cb = cb;
        DefaultRenderersFactory rf = new DefaultRenderersFactory(c)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        player = new ExoPlayer.Builder(c, rf)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        root = new FrameLayout(c);
        frame = new AspectFrame(c);
        sv = new SurfaceView(c);
        frame.addView(sv);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        subs = new SubtitleView(c);
        subs.setUserDefaultStyle();
        subs.setUserDefaultTextSize();
        root.addView(subs, new FrameLayout.LayoutParams(-1, -1));
        player.setVideoSurfaceView(sv);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) ExoEngine.this.cb.onBuffering(true);
                else {
                    ExoEngine.this.cb.onBuffering(false);
                    if (state == Player.STATE_READY) ExoEngine.this.cb.onReady();
                    if (state == Player.STATE_ENDED) ExoEngine.this.cb.onEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                ExoEngine.this.cb.onPlaying(playing);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                boolean unsupported = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                        || e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                        || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                        || e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED;
                ExoEngine.this.cb.onError(e.getErrorCodeName(), unsupported);
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width <= 0 || vs.height <= 0) return;
                float w = vs.width * vs.pixelWidthHeightRatio;
                float h = vs.height;
                if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) {
                    float t = w;
                    w = h;
                    h = t;
                }
                frame.setVideoRatio(w / h);
                ExoEngine.this.cb.onVideoSize((int) w, (int) h);
            }

            @Override
            public void onTracksChanged(Tracks t) {
                if (!checkedTracks && !t.getGroups().isEmpty()) {
                    checkedTracks = true;
                    boolean bad = (t.containsType(C.TRACK_TYPE_VIDEO) && !t.isTypeSupported(C.TRACK_TYPE_VIDEO))
                            || (t.containsType(C.TRACK_TYPE_AUDIO) && !t.isTypeSupported(C.TRACK_TYPE_AUDIO));
                    if (bad) {
                        ExoEngine.this.cb.onError("unsupported tracks", true);
                        return;
                    }
                }
                ExoEngine.this.cb.onTracksChanged();
            }

            @Override
            public void onCues(CueGroup cg) {
                subs.setCues(cg.cues);
            }
        });
    }

    @Override public String name() { return "ExoPlayer"; }

    @Override public View view() { return root; }

    @Override
    public void open(Uri u, long startMs, boolean play) {
        uri = u;
        checkedTracks = false;
        player.setMediaItem(MediaItem.fromUri(u), Math.max(0, startMs));
        player.prepare();
        player.setPlayWhenReady(play);
    }

    @Override public void play() { player.play(); }

    @Override public void pause() { player.pause(); }

    @Override public boolean isPlaying() { return player.isPlaying() || player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING; }

    @Override public long position() { return player.getCurrentPosition(); }

    @Override public long duration() { long d = player.getDuration(); return d == C.TIME_UNSET ? 0 : d; }

    @Override public void seekTo(long ms) { player.seekTo(Math.max(0, ms)); }

    @Override public void setSpeed(float s) { player.setPlaybackSpeed(s); }

    @Override public void setLooping(boolean b) { player.setRepeatMode(b ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF); }

    @Override public void setAspect(int mode) { frame.setMode(mode); }

    private List<Track> tracksOf(int type) {
        List<Track> r = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Track t = new Track();
                t.label = label(g.getTrackFormat(i), r.size() + 1);
                t.selected = g.isTrackSelected(i);
                t.ref = g;
                t.index = i;
                r.add(t);
            }
        }
        return r;
    }

    private String label(Format f, int n) {
        StringBuilder sb = new StringBuilder();
        if (f.label != null) sb.append(f.label);
        else if (f.language != null && !"und".equals(f.language)) {
            sb.append(new Locale(f.language).getDisplayLanguage());
        } else sb.append("#").append(n);
        if (f.sampleMimeType != null && MimeTypes.isAudio(f.sampleMimeType)) {
            String m = f.sampleMimeType.substring(f.sampleMimeType.indexOf('/') + 1).replace("vnd.dts", "dts").toUpperCase(Locale.ROOT);
            sb.append("  ·  ").append(m);
            if (f.channelCount > 0) sb.append(" ").append(f.channelCount).append("ch");
        }
        return sb.toString();
    }

    @Override public List<Track> audioTracks() { return tracksOf(C.TRACK_TYPE_AUDIO); }

    @Override public List<Track> textTracks() { return tracksOf(C.TRACK_TYPE_TEXT); }

    @Override
    public void selectAudio(int idx) {
        List<Track> l = audioTracks();
        if (idx < 0 || idx >= l.size()) return;
        Track t = l.get(idx);
        Tracks.Group g = (Tracks.Group) t.ref;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), t.index)).build());
    }

    @Override
    public void selectText(int idx) {
        if (idx < 0) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            return;
        }
        List<Track> l = textTracks();
        if (idx >= l.size()) return;
        Track t = l.get(idx);
        Tracks.Group g = (Tracks.Group) t.ref;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), t.index)).build());
    }

    @Override
    public void addSubtitle(Uri sub, String ext) {
        if (uri == null) return;
        String mime = "vtt".equals(ext) ? MimeTypes.TEXT_VTT
                : "ass".equals(ext) || "ssa".equals(ext) ? MimeTypes.TEXT_SSA
                : "ttml".equals(ext) || "xml".equals(ext) || "dfxp".equals(ext) ? MimeTypes.APPLICATION_TTML
                : MimeTypes.APPLICATION_SUBRIP;
        MediaItem.SubtitleConfiguration sc = new MediaItem.SubtitleConfiguration.Builder(sub)
                .setMimeType(mime).setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
        long pos = player.getCurrentPosition();
        boolean play = player.getPlayWhenReady();
        MediaItem item = new MediaItem.Builder().setUri(uri).setSubtitleConfigurations(java.util.Collections.singletonList(sc)).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        checkedTracks = true;
        player.setMediaItem(item, pos);
        player.prepare();
        player.setPlayWhenReady(play);
    }

    @Override
    public void release() {
        try {
            player.clearVideoSurface();
            player.release();
        } catch (Exception ignored) {
        }
    }
}
