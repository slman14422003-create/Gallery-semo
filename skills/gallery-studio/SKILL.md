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
- PickerActivity (exported): handles GET_CONTENT, OPEN_DOCUMENT, PICK, PICK_IMAGES for image/* and video/*. Single pick returns at once, multiple pick uses Done. Honors EXTRA_ALLOW_MULTIPLE, EXTRA_MIME_TYPES and PICK_IMAGES_MAX. Returns data + ClipData with FLAG_GRANT_READ_URI_PERMISSION.

- Splash: ic_splash_folder.xml petals named p0..p7 plus core, staggered overshoot in anim_splash.xml; MainActivity has a custom exit animation (icon scales up and fades) and fades the content in.
- AiTools.java (pure Java, no new dependencies): dehaze (dark channel prior), localTone (clarity + shadow lift), suggest (values for the AI button), straightenAngle (gradient orientation histogram), isDark (video black frames). EditState has dehaze/clarity/shadows; Renderer.color applies them before denoise/sharpen.
- Image editor labelled Beta 4 (was 3): new sliders, "Auto straighten" chip in the Adjust tab.
- Video editor labelled Beta 2: AI chips (Enhance from a sampled frame, Low light, Dehaze presets, Smart trim of black ends via TrimBar.setRange).

## Notes / possible follow-ups
- No network in the build sandbox, so no GitHub projects were pulled in; the algorithms were written from the published methods.
- Auto straighten sign was reasoned, not run: verify on a device that a tilted horizon becomes level, flip the sign in AiTools.straightenAngle if it goes the wrong way.
- Dehaze runs on every preview rebuild, check smoothness on large photos.
- The user must choose Gallery Studio in the system chooser once (or set it as default) for other apps to show the in-app picker.
- Android 13+ apps that call PickVisualMedia use the system photo picker action; this app registers for it, but the OS may still prefer its own picker on some devices.
- Not compiled or run in this environment, build through the GitHub workflow and test on a device.

- CI guard in .github/workflows/build.yml allows exactly MainActivity, OpenActivity and PickerActivity to be exported; any new exported screen must be added there on purpose.
