---
name: gallery-studio
description: Progress log and architecture notes for the Gallery Studio Android app (app.gallery.studio, Java/Views). Read before continuing any work on this project.
---

# Gallery Studio - progress

## Project facts
- Package app.gallery.studio, source package com.gallery.app, Java with programmatic Views, no Compose.
- Screens extend BaseActivity. Insets are applied globally in App (API 35+), do not add inset listeners per screen.
- Media comes from Library.all (MediaStore snapshot); Library.albums / itemsFor build album lists.
- Grid tiles: Tile + Thumbs (Glide). Strings live in res/values (ar) and res/values-en.
- Do not create README files. Code files contain English only.

## Done
- Launcher icon: petals group scaled to 0.7 in ic_launcher_foreground.xml and ic_launcher_monochrome.xml.
- VideoEditorActivity: brightness/contrast sliders update the live preview while dragging (60ms debounce); preview refreshes while paused; original file is untouched until Export.

- Splash: ic_splash_folder.xml petals named p0..p7 plus core, staggered overshoot in anim_splash.xml; MainActivity has a custom exit animation (icon scales up and fades) and fades the content in.
- AiTools.java (pure Java, no new dependencies): dehaze (dark channel prior), localTone (clarity + shadow lift), suggest (values for the AI button), straightenAngle (gradient orientation histogram), isDark (video black frames). EditState has dehaze/clarity/shadows; Renderer.color applies them before denoise/sharpen.
- Image editor labelled Beta 4 (was 3): new sliders, "Auto straighten" chip in the Adjust tab.
- Video editor labelled Beta 3 (was 2): tabbed tools (Edit, Speed, Filters, Adjust, Audio, Text and stickers, AI), speed 0.25x-4x (export uses Effects.createExperimentalSpeedChangingEffect, or SpeedChangeEffect when audio is muted or missing), volume via ChannelMixingAudioProcessor, fill/fit canvas (Presentation LAYOUT_SCALE_TO_FIT), 8 extra looks as fixed colour matrices (VideoFx.look), saturation/warmth sliders, text and emoji overlays (VideoFx.overlays, Media3 TextOverlay) that can be dragged on the live preview. Earlier Beta 2 content: AI chips (Enhance from a sampled frame, Low light, Dehaze presets, Smart trim of black ends via TrimBar.setRange).

## Notes / possible follow-ups
- No network in the build sandbox, so no GitHub projects were pulled in; the algorithms were written from the published methods.
- Auto straighten sign was reasoned, not run: verify on a device that a tilted horizon becomes level, flip the sign in AiTools.straightenAngle if it goes the wrong way.
- Dehaze runs on every preview rebuild, check smoothness on large photos.
- Not compiled or run in this environment, build through the GitHub workflow and test on a device.

- CI guard in .github/workflows/build.yml allows exactly MainActivity and OpenActivity to be exported; any new exported screen must be added there on purpose.
- Live Text selection restyled in OcrLayer (rounded merged-line highlight with edge, ringed knobs with shadow, pop-in animation selA) and the viewer menu (dark capsule, ripple items, scale/fade-in).
- Video editor AI actions show a progress dialog (showAi/aiProgress/hideAi, aiGen guards stale threads and cancel). Any new long AI task must use the same dialog, never a bare toast.

- Image editor: Text tab has an emoji sticker row (labels with an emoji and a larger size); VideoFx.EMOJI is shared by both editors.
- Media3 stays on 1.8.0: StaticOverlaySettings (not OverlaySettings) is the settings type since 1.6; EditedMediaItem.Builder.setSpeed only exists from 1.9, so the older speed effect pair is used. Guava is declared explicitly for ImmutableList.
- The in-app media picker (PickerActivity) was removed on request; the system picker is used again.

- Beta 4 (video + image, no new dependencies): VideoCompress.java holds the export presets (short side, bits per pixel, audio bitrate, H.265 check, size estimate); VideoEditorActivity has a Compress tab, fade in/out chips in the Edit tab, effects(boolean export) adds fade, Presentation.createForHeight and FrameDropEffect(30) only on export, Transformer gets setEncoderFactory(VideoCompress.encoders) and H.265 mime when chosen. VideoFx.Fade (RgbMatrix, preview offsets by trim start) and VideoFx.AudioFade (PCM16, last in the audio chain). Image editor save() opens a format / size / quality dialog, doSave() scales and calls MediaSaver.savePicture(ctx, bmp, format, quality) which returns Saved{uri, bytes}.
- Open-source projects reviewed for this step: PhotoEditor (Android) already matches what the app has (brush, shapes, eraser, text, emoji, filters, undo); OpenCut, OpenChatCut and photocraft are Rust / TypeScript desktop or web code, not linkable into an Android APK, so only their ideas (fade, compression presets) were ported.
- Not compiled here: check on CI that FrameDropEffect.createDefaultFrameDropEffect, VideoEncoderSettings.Builder.setBitrateMode, BaseAudioProcessor.onConfigure and Transformer.Builder.setEncoderFactory exist in Media3 1.8.0 as used; verify fade timing and that the H.265 file plays in the gallery.

- Predictive back: Motion.java wraps overrideActivityTransition (API 34+) and legacy overridePendingTransition (below 34). Viewer, Player, Album and Settings install their open/close transitions in onCreate and finish() only calls the legacy path on API below 34, so the gesture no longer plays two animations. Back callbacks must stay disabled unless they have something to consume (selection in MainActivity / AlbumActivity via MediaGrid.setOnSelectChange, lock in PlayerActivity); an always-enabled callback blocks the system back animation. Image and video editors still use always-enabled callbacks (unsaved-changes dialog).
- hold_close.xml (190ms) pairs with viewer_close_exit so the screen underneath is not held longer than the exit animation.

- Video editor preview no longer uses Media3 effects: VideoStage (TextureView) draws the player through a ColorMatrixColorFilter layer paint (a new Paint per change, TextureView ignores the same instance), rotation/flip/fit-fill are plain view transforms, text and emoji are TextViews, the fade is a black mask view driven from the playhead (output-time = media-time / speed). Export still uses Transformer with exportEffects() and VideoFx.Grade / Fade / LiveText. refreshPreview() never marks the project dirty, applyEffects() does.
- Image editor preview: Frame{shown, base, tone, draft}. Without heavy ops (EditState.heavy()) the colour matrix is drawn live by EditorView (tonePaint) so sliders and filters cost nothing; heavy ops render a 720px draft while dragging and a full pass 240ms after the last change (finalPass). Crop and draft bitmaps are cached per transform / crop key.
- OCR: OcrPrep (scaleUp small images, grey + percentile contrast stretch, invert) feeds TessEngine, which retries with sparse text and inverted input when fewer than 3 words are found. Ocr.run falls back to ML Kit when the Arabic model cannot be downloaded in auto mode, and caches per engine actually used.
- Viewer: DismissLayout wraps the pager (swipe down to close, disabled while zoomed or in Live Text), slideshow button in the top bar (3.2s per page, any tap stops it).
- Not compiled here (no Android SDK in the sandbox): check on CI that TextureView.setLayerPaint, Player.Listener.onPlayerError and TessBaseAPI.setVariable resolve, and verify on a device that the preview colours, rotation direction and text positions match the exported file.
