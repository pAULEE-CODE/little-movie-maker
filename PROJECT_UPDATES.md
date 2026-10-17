# Project Updates

## Batch 02 — Editor, Projects, Settings and Advanced Timelapse

Implemented in test build **1.4-test2 (versionCode 6)**:

- Reworked the editor around a large aspect-fit movie preview, a compact timeline and three thumbnail zoom sizes that change actual image dimensions. Selection mode keeps the same layout and supports long-press, tap selection and drag-range selection.
- Moved secondary editor operations into More tools, including speed, holds, apply-to-all, split, move, rotate and export. Existing frame and project data formats remain readable.
- Added persistent non-destructive movie rotation, applied consistently to preview, thumbnails and MP4 export after EXIF orientation correction.
- Added folder rename, safe folder move/delete with reparenting, and multi-project move to an existing or new folder.
- Added persistent light/dark/system theme choice, new-project FPS and zoom defaults, automatic gallery preview, countdown sound and delete-warning preferences, plus About information.
- Added a compact optional Camera2 manual-exposure section that checks reported rear-camera capabilities and falls back to automatic exposure if the CameraX session rejects the settings.
- Added optional 5/10/20-source brightest-pixel light-trail stacking. Source captures are temporary, combined off the UI thread, and stored as one ordinary frame; the normal timelapse path remains unchanged.
- Improved unselected timelapse controls and layout contrast.
- Selected Hold +/− now adjusts every selected frame independently, preserves unselected durations, and clamps values to ×1–×10.

Verification: host unit tests pass; Android instrumentation tests compile. A physical Samsung camera check was not available in the build environment. Manual exposure support and grouped capture timing therefore still require device verification. The language preference is saved, but the current interface remains predominantly bilingual rather than dynamically switching every screen to a single language.

## Future Effects

The following remain out of scope for this build:

- Low-light capture, exposure and white-balance locking, and multi-frame noise reduction beyond the dedicated light-trail stack mode.
- Tilt-shift, vintage film and comic-book effects.
- Video compositing, green-screen tools and a node-based effects architecture.
- HDR exposure bracketing and HDRI light probes.
- Guided panoramic photography, photogrammetry and 3D scanning.
- MP4 audio or music.
