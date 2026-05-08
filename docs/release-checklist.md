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

Install the AAB on a device or emulator without ARCore (an Android 8.0 / API 26
emulator without Google Play Services for AR is the easiest). Confirm:

- App installs and launches.
- AR mode is **absent** from the mode chooser rail.
- The first-launch "ARCore not supported" overlay appears once and is
  dismissed by tapping; relaunching the app does not show it again.
- Trace, Mockup, and Live Overlay all work; camera preview shows live frames.

## Smoke test on an ARCore-supported device

- AR mode is present and entering it initializes a session.
- Anchoring, scan-fog, and capture flows all behave normally.
