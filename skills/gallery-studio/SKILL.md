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
