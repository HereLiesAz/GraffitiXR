# Release checklist

Quick gate to run before promoting a build to a public Play Store track. Skim
top-to-bottom; the steps that matter most are the device-reach ones.

## Device reach (Play Console → Reach and devices → Supported devices)

This is the metric that surfaces the kind of regression that prompted the
ARCore-optional change in 2026. Before promoting:

1. Open the new release in Play Console.
2. Navigate to **Reach and devices → Supported devices** (the count and the
   filter list).
3. Compare against the previous production release.
4. **Expected band:** the supported-device count should remain in the millions
   (post-ARCore-optional baseline). If it drops by more than ~10% between
   releases, **stop** and investigate before promoting:
   - Inspect Play Console's "excluded by manifest features" reason list.
   - Run `./gradlew :app:processReleaseManifest` locally and inspect
     `app/build/intermediates/merged_manifest/release/AndroidManifest.xml`
     for any new `<uses-feature ... required="true">` entries or any
     `<meta-data android:name="com.google.ar.core" android:value="required" />`
     creeping back in.
   - Check whether a new permission was added that implies a required hardware
     feature (e.g. Bluetooth, NFC, sensors). If so, add the matching
     `<uses-feature ... required="false" />` entry in
     `app/src/main/AndroidManifest.xml`.

## Manifest sanity check

Quick sanity-check command on the merged manifest:

```bash
./gradlew :app:processReleaseManifest
grep -E 'uses-feature|com.google.ar.core' \
  app/build/intermediates/merged_manifest/release/AndroidManifest.xml
```

Confirm:

- `android.hardware.camera.ar` → `required="false"`.
- `com.google.ar.core` meta-data → `value="optional"`.
- No incidental `required="true"` features for Bluetooth / wifi / location.
- `<uses-sdk android:minSdkVersion="26" ... />`.

## Smoke test on a non-ARCore device

Install the AAB on a device without Google Play Services for AR / ARCore support.
An emulator is useful for install/routing checks, but the wall-lock checks require a
real camera and motion sensors. Confirm:

- App installs and launches.
- AR mode is **present** in the mode chooser rail.
- Entering AR does not redirect to Overlay mode and does not attempt to create an
  ARCore `Session`.
- CameraX preview streams normally.
- "Capture Wall Target" captures a wall patch and the four-corner unwarp can be confirmed.
- A textured target produces a SphereSLAM/KPM lock and the artwork remains registered while
  translating/rotating the phone within the target's useful viewing range.
- Pan, pinch-scale, in-plane rotation, X/Y perspective rotation, tone, opacity, and invert use the
  standalone `sphereSlamModeAdjustment` slot; they do not overwrite ARCore's independent
  `modeAdjustments[AR]` placement.
- A brief visual miss can use the short IMU bridge; a longer miss clears the stale overlay and shows
  reacquisition instead of freezing the last pose indefinitely.
- Leaving and reopening the project restores the URI-referenced `sphereslam_reference_<uuid>.png` and can reacquire the
  target without recapturing it.
- Walking far enough to grow/use an additional atlas page does not change the canonical artwork
  frame; returning to page 0 produces no visible coordinate jump.
- Exporting/importing the project relocates page 0 and every referenced SphereSLAM atlas-page URI to
  the imported project directory and can reacquire there.
- No standalone UI reports physical metres unless
  `sphereSlamReferencePhysicallyMetric == true`.
- If co-op is exercised, a normalized standalone wall may join another standalone peer but is
  refused for an ARCore guest; measured metric standalone↔ARCore pairing succeeds only through the
  protocol-v3 host wall-frame descriptor.

## Smoke test on an ARCore-supported device

- AR mode is present and entering it initializes an ARCore session.
- `ArCorePoseSource` remains the primary continuous renderer pose.
- SphereSLAM runs beside ARCore; loss/failure of KPM does not break normal ARCore tracking.
- A target captured on a real ARCore wall plane seeds a perspective-rectified **metric** KPM page;
  the generic 72-DPI default is never treated as a physical hybrid scale.
- With drift correction OFF (the default), KPM cannot move the artwork.
- With drift correction ON, an accepted KPM correction adjusts only the artwork anchor through
  PoseFusion; ARCore remains the renderer camera pose. Covering/removing the KPM target must not
  make the overlay ride the screen or keep correcting from a stale observation.
- Target recapture disables the previous KPM reference immediately; repeated AR enter/exit does not
  race `Anchor.detach()` against the render thread.
- Reopening a project without a live hybrid page still uses the durable MobileGS return-visit path;
  hybrid KPM correction resumes only after a new metric hybrid page is captured.
- Existing anchor, target capture, scan/fingerprint, depth/plane, MobileGS relocalization, and
  teardown flows behave normally.
