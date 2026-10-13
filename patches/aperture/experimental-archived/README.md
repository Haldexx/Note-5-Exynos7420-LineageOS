# Withdrawn camera experiments

Patches 0004 and 0005 are preserved as historical experiments, outside the active
Aperture `*.patch` glob. Their black-preview diagnosis was superseded on October 12:
Live HDR ON at 60 FPS caused the fault. HDR OFF produces a live preview even on a
cold start with the earlier app, without either experiment.

The active correction is `../0006-disable-live-hdr-above-30fps.patch`.
See `reports/n920i-camera-60fps-preview-black-2026-10-12.md` for the controlled comparison.
