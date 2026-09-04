# GraffitiXR

Ang GraffitiXR ay isang android app para sa mga street artist. Maraming app na nag-o-overlay ng larawan sa view ng iyong camera para halos ma-trace mo ito, ngunit kapag nagpinta ako ng mural batay sa isang sketch na na-save ko sa aking telepono, ang paggamit ng tripod ay talagang makakabawas sa daloy. Nandito na kami sa lahat ng lugar. Ako, nilagay ko yung phone ko sa bulsa ko. Kahit na ang mga app na gumagamit ng AR upang panatilihing matatag ang imahe at sa isang lugar ay hindi makayanan ang napakalaking kadiliman ng bulsa.

Kaya ako ay gumagawa ng isang bagay na mas mahusay sa pamamagitan ng repurposing (kung ano ang mga in-the-alam na tawag) ang paraan ng grid. Palagi kong iniisip, "Bakit hindi ma-save ang mga partikular na doodle na ito, tulad ng isang patuloy na anchor, kaya ang overlay ay laging malinaw sa tamang lugar?"

Kaya, ngayon, iyon ang ginagawa ng mga doodle na iyon.

Kinailangan kong mag-imbento ng custom na **fingerprinted relocalizer** na gumagana sa Android nang walang tulong ng cloud — dahil ang graffiti ay, alam mo, ilegal. Kapag na-lock mo na ang isang dingding, kinukuha nito ang isang OpenCV feature **fingerprint** ng iyong mga marka — mga descriptor kasama ang ilang triangulated na 3D point. Kahit pagkatapos mawala ang tracking o mag-off ang screen, itinutugma ng engine ang live camera laban sa fingerprint na iyon at nire-resolba ang pose (PnP) para **bumalik agad** at muling i-align ang iyong mural sa dingding sa loob ng milliseconds — walang cloud, at walang paunang pag-scan ng buong kwarto.

At sinundan ko ito ng tinatawag kong **Teleological SLAM** — dahil alam natin kung ano ang magiging hitsura ng resulta, ginagamit ko ang OpenCV upang hanapin ang iyong pag-unlad, ibig sabihin, habang mas malayo ka, mas mahigpit na dumidikit ang overlay sa dingding. Kung wala ito, sasakupin mo ang mga markang iyon gamit ang mismong pagpipinta, na ginagawang hindi gaanong tumpak ang app habang nagpapatuloy ka. Iyan mismo kung saan ang ibang mga app na tulad nito ay talagang nabigo.

Ang dalawang ito — ang snap-back at ang self-extending fingerprint — ay kasalukuyang naka-ship bilang opt-in na toggle (Settings > diagnostic overlay, naka-off bilang default) habang vine-validate ko sila sa tunay na hardware, kaya huwag asahan ang alinman sa dalawa nang out-of-the-box sa ngayon.

Para lang sa mga kamiseta at salaming de kolor, isinama ko ang hindi AR, ang pag-andar ng overlay ng imahe para sa pagsubaybay sa larawan, tulad ng makukuha mo sa iba pang mga app na iyon, kung sakaling mag-cray ka nang ganoon. O kung cray-cray ka, may **Mockup mode**. Kumuha ng larawan ng dingding, pagkatapos ay kumuha ako ng ilang mabilis na tool para sa isang mabilis na mockup. At kung wala kang dapat patunayan, gusto mo lang ng isang bagay na makopya sa papel nang perpekto, pinapayagan ka ng **Trace mode** na gamitin ang iyong telepono bilang isang lightbox, panatilihing naka-on ang iyong screen nang nakabukas ang liwanag, i-lock ang iyong larawan sa lugar at i-block ang lahat ng pagpindot hanggang sa matapos ka.

At pagkatapos, mayroong isang disenteng suite ng mga nauugnay na tool sa disenyo, para sa paghahanda ng isang solong larawan bago mo ito idikit sa dingding. Maaari akong magpatuloy, ngunit pakiramdam ko ay mayroon na ako.

## Mga Pangunahing Tampok
*   **Offline-Una:** Walang cloud dependencies para sa anumang ginagawa ng app — lokal ang tracking, rendering, at gawaing pang-disenyo. Ang tanging bagay na umaalis sa device ay ang crash report, at opt-in iyon at naka-off bilang default (Settings > Crash reports); tingnan ang [`docs/tl/PRIVACY_POLICY.md`](docs/tl/PRIVACY_POLICY.md).
*   **Fingerprint Relocalization:** isang C++17 native OpenCV pipeline (ORB/SuperPoint descriptors + PnP/RANSAC) na kumukuha ng fingerprint mula sa mga markang iginuhit mo sa dingding at ibinabalik ang overlay pagkatapos mawala ang tracking — lubos na offline, walang paunang pag-scan ng kwarto.
*   **Pocket-Ready (eksperimental, naka-off bilang default):** may drift correction at self-extending fingerprint (para makaligtas ang snap-back kahit mapinturahan na ang orihinal na reference) na maaaring i-on sa Settings > diagnostic overlay, ngunit hindi pa ito na-validate sa tunay na hardware — tingnan ang [Teleological SLAM](docs/TELEOLOGICAL_SLAM.md).
*   **May Kamalayan sa Dual-Lens:** awtomatikong pumipili ng hardware stereo depth sa mga device na may ganitong feature; sa ibang device, gumagamit ng monocular pose ng ARCore nang walang hiwalay na depth estimate.
*   **Co-op Mode:** naka-encrypt, QR-paired na session sharing para makapanood ang isang kasamahan ng live canvas ng host sa AR. Sa kasalukuyan ay host → guest lamang — hindi pa nag-sync pabalik ang mga edit ng guest.
*   **AzNavRail UI:** thumb-driven, isang-kamay na navigation na dinisenyo para sa mga artist na hawak ang spray can.

## Mga Mode
*   **AR Mural:** Ang pangunahing precision instrument para sa pag-anchor ng digital na konsepto sa pisikal na ibabaw, sinusubaybayan gamit ang fingerprint relocalizer sa itaas.
*   **Mockup Mode:** Mabilis na mga tool para sa pag-visualize ng mga layer at blend mode sa ibabaw ng static na larawan ng dingding.
*   **Trace (Lightbox):** Full-brightness na ibabaw para sa pagkopya sa papel na may touch-lock, awtomatikong pag-retract ng rail, at physical-volume-button exit (Up, Down, Up, Down).
*   **Overlay:** Non-AR na pag-trace ng larawan — ang reference image mo ay naka-overlay sa live camera (CameraX) na may nasasaayos na opacity. Sa iilang device na walang ARCore, maaari nitong subaybayan sa halip ang isang hugis na minarkahan mo sa dingding gamit ang parehong OpenCV pipeline, planar-only.
*   **Design:** Pag-edit at paghahanda ng isang solong larawan — mga adjustment sa blend mode, opacity/kulay, at outline extraction — bago ito ilipat sa alinman sa itaas na mga mode.

## Arkitektura
Mahigpit na na-decoupled na multi-module Clean Architecture:
*   `:app` — Navigation, camera orchestration, at Hilt dependency injection.
*   `:feature:ar` — ARCore session management, `ArRenderer`, at SLAM data processing.
*   `:feature:editor` — Pagmamanipula at pag-adjust ng solong larawan.
*   `:feature:dashboard` — Library ng proyekto, onboarding, at mga setting.
*   `:core:nativebridge` — Native C++ engine (`MobileGS`), JNI bridge, at mga relocalization thread. Ang OpenCV mismo ay isang Maven Central dependency (`org.opencv:opencv`), hindi isang naka-vendor na module.
*   `:android_collaboration_module` — peer-to-peer networking para sa Co-op Mode (host → guest; tingnan ang Mga Pangunahing Tampok sa itaas).
*   `:core:data` / `:core:domain` / `:core:common` — Pinag-isang data layer at wearable abstraction.
*   `:core:design` — Ibinahaging Compose design system (mga reusable control at overlay).

## Lisensya
Ang GraffitiXR ay **source-available, hindi open source.** Ang app, ang mga `core:*` module, at ang AR / SLAM / teleological engine ay lisensyado sa ilalim ng **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](LICENSE)); ang deklaradong extension API surface at mga asset importer ay **MIT** ([`docs/licenses/MIT.txt`](docs/licenses/MIT.txt)). Ang **naka-compile na app ay libreng gamitin ng sinuman, kasama na ang mga bayad na komisyon** — ang noncommercial na termino ay sumasakop sa muling paggamit ng *source*, hindi sa mga muralist na gumagawa ng bayad na trabaho. Tingnan ang [`docs/LICENSING.md`](docs/LICENSING.md) para sa awtoritatibong, path-by-path na layout at precedence. Ang mga bundled third party (OpenCV, ML Kit, ...) ay pinananatili ang sarili nilang upstream na lisensya.

## Dokumentasyon
- [Pangkalahatang-ideya ng Arkitektura](docs/ARCHITECTURE.md)
- [Mga Detalye ng Native Engine](docs/NATIVE_ENGINE.md)
- [SLAM Setup & Relocalization](docs/SLAM_SETUP.md)
- [Teleological SLAM](docs/TELEOLOGICAL_SLAM.md)
- [Performance Guide](docs/performance.md)
- [Diskarte sa Pagsubok](docs/testing.md)
- [Data Formats](docs/data_formats.md)
- [Contributing](docs/contributing.md)
- [Release & Google Play Delivery](docs/RELEASE.md)
- [Reference ng Screen at Mode](../en/screens.md)

---
*Na-update ang dokumentasyon noong 2026-09-04: itinama ang mga claim tungkol sa feature laban sa kasalukuyang codebase, kasunod ng parehong pagwawasto sa root README.md. Inalis ang "Persistent Voxel Memory" engine at `slamManager.draw()` na voxel-splat rendering (hindi kailanman naipatupad na disenyo bago ang pivot), gayundin ang multi-layer na composition claim (ang editor ay para sa iisang larawan lamang — ang multi-image compositing ay gawain ng hiwalay na companion app) at ang optical-flow fallback para sa Dual-Lens (walang ganitong fallback — monocular ARCore pose na lang kung walang stereo depth). Itinama ang Co-op Mode (host → guest lamang) at Offline-First (opt-in crash report at user-triggered GitHub update check lamang ang lumalabas sa device). Inalis ang mga link patungong `docs/PIPELINE_3D.md` (wala nang ganitong file) at itinama ang landas patungong `docs/screens.md` (na-move na sa `docs/en/screens.md`). Naunang update: 2026-03-17, sa panahon ng muling pagdidisenyo ng website at yugto ng pagsasama ng Stencil Mode (na tinanggal na rin mula noon).*
