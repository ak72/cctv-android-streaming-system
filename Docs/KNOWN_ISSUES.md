# Known issues

This document lists known limitations and issues for troubleshooting. They are left as-is when device- or platform-specific fixes would be impractical or unsustainable.

---

## Viewer: Zoomed / FILL-like live preview on some devices

**Symptom:** On some devices (e.g. Samsung M30s), the live preview in the Viewer app appears zoomed in (FILL-like) instead of showing full FOV (FIT/letterbox). The stream is correct; only the on-screen scaling is wrong.

**Cause:** The Viewer applies a FIT transform (matrix) to the TextureView so the decoded video fits the view while preserving aspect ratio. On some devices and Android builds, `TextureView` resets or ignores the transform each time the surface texture is updated (every frame). So despite the transform being applied and logged as successful, the next frame draw uses the default (FILL) behavior.

**What we do:** The Viewer uses a 3-gate renderer (view laid out + surface ready + video dimensions known) and re-applies the transform every frame when dimensions are available. This works on many devices; on others the platform behavior overrides it.

**Why we don’t fix it per-device:** There are many untested devices and OEM builds. Adding device-specific callbacks or workarounds for each would be impractical and hard to maintain. A sustainable fix would require a platform/API change (e.g. TextureView honoring setTransform across updates).

**Workaround:** None at app level. Users on affected devices see a zoomed preview; the stream content and recording (if any) are correct.

---

*Last updated: 2025.*
