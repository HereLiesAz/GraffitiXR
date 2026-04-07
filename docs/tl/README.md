# GraffitiXR

Ang GraffitiXR ay isang android app para sa mga street artist. Maraming app na nag-o-overlay ng larawan sa view ng iyong camera para halos ma-trace mo ito, ngunit kapag nagpinta ako ng mural batay sa isang sketch na na-save ko sa aking telepono, ang paggamit ng tripod ay talagang makakabawas sa daloy. Nandito na kami sa lahat ng lugar. Ako, nilagay ko yung phone ko sa bulsa ko. Kahit na ang mga app na gumagamit ng AR upang panatilihing matatag ang imahe at sa isang lugar ay hindi makayanan ang napakalaking kadiliman ng bulsa.

Kaya ako ay gumagawa ng isang bagay na mas mahusay sa pamamagitan ng repurposing (kung ano ang mga in-the-alam na tawag) ang paraan ng grid. Palagi kong iniisip, "Bakit hindi ma-save ang mga partikular na doodle na ito, tulad ng isang patuloy na anchor, kaya ang overlay ay laging malinaw sa tamang lugar?"

Kaya, ngayon, iyon ang ginagawa ng mga doodle na iyon.

Kinailangan kong mag-imbento ng custom na Gaussian Splatting engine na gumagana sa Android nang walang tulong ng cloud--dahil ang graffiti ay, alam mo, ilegal.

At sinundan ko ito ng tinatawag kong Teleological Slam--dahil alam natin kung ano ang magiging hitsura ng resulta, ginagamit ko ang OpenCV upang hanapin ang iyong pag-unlad, ibig sabihin, habang mas malayo ka, mas mahigpit na dumidikit ang overlay sa dingding. Kung wala ito, sasakupin mo ang mga markang iyon gamit ang mismong pagpipinta, na ginagawang hindi gaanong tumpak ang app habang nagpapatuloy ka. Iyan mismo kung saan ang ibang mga app na tulad nito ay talagang nabigo.

Para lang sa mga kamiseta at salaming de kolor, isinama ko ang hindi AR, ang pag-andar ng overlay ng imahe para sa pagsubaybay sa larawan, tulad ng makukuha mo sa iba pang mga app na iyon, kung sakaling mag-cray ka nang ganoon. O kung cray-cray ka, may Mockup mode. Kumuha ng larawan ng dingding, pagkatapos ay kumuha ako ng ilang mabilis na tool para sa isang mabilis na mockup. At kung wala kang dapat patunayan, gusto mo lang ng isang bagay na makopya sa papel nang perpekto, pinapayagan ka ng Trace mode na gamitin ang iyong telepono bilang isang lightbox, panatilihing naka-on ang iyong screen nang nakabukas ang liwanag, i-lock ang iyong larawan sa lugar at i-block ang lahat ng pagpindot hanggang sa matapos ka.

At pagkatapos, mayroong isang disenteng suite ng mga nauugnay na tool sa disenyo, na may suporta para sa multi-layer na graphical na paglikha. Maaari akong magpatuloy, ngunit pakiramdam ko ay mayroon na ako.

Ang **GraffitiXR** ay isang offline na Android app para sa mga street artist. Gumagamit ito ng AR upang i-project ang mga larawan sa mga dingding gamit ang isang voxel mapping system na nakabatay sa kumpiyansa.

## Mga Pangunahing Tampok
* **Offline-Una:** Walang cloud dependencies;zero data na nakolekta.
* **Custom Engine (MobileGS):** C++17 native engine para sa 3D Gaussian Splatting at spatial mapping.
* **Full ARCore Pipeline:** Live camera feed sa pamamagitan ng `BackgroundRenderer`, color frame relocalization, at ARCore Depth API — lahat ay nagpapakain ng totoong data sa SLAM engine.
* **AzNavRail UI:** Thumb-driven navigation para sa isang kamay na paggamit sa field.
* **Single GL Render Path:** Pinangangasiwaan ng `ArRenderer` ang parehong background ng camera (`BackgroundRenderer`) at SLAM voxel splats (`slamManager.draw()`) sa iisang `GLSurfaceView`.
* **Multi-Lens Support:** Awtomatikong gumagamit ng dual-camera stereo depth sa mga sinusuportahang device;bumabalik sa optical flow.
* **Teleological Correction:** Awtomatikong mapa-to-world alignment gamit ang OpenCV fingerprinting.

## Arkitektura
Mahigpit na na-decoupled na multi-module na arkitektura:
* `:app` — Navigation, `ArViewport` composable, orkestrasyon ng pagmamay-ari ng camera.
* `:feature:ar` — ARCore session, `ArRenderer`, `BackgroundRenderer`, sensor fusion, SLAM data feeding.
* `:feature:editor` — Pagmamanipula ng imahe, pamamahala ng layer.
* `:feature:dashboard` — Library ng proyekto, mga setting.
* `:core:nativebridge` — `SlamManager` JNI bridge, `MobileGS` voxel engine, OpenGL ES rendering.
* `:core:data` / `:core:domain` / `:core:common` — Malinis na layer ng data ng Arkitektura.

## Dokumentasyon
- [Pangkalahatang-ideya ng Arkitektura](docs/ARCHITECTURE.md)
- [Mga Detalye ng Native Engine](docs/NATIVE_ENGINE.md)
- [Configuration at Pag-tune ng SLAM](docs/SLAM_SETUP.md)
- [3D Pipeline Specification](docs/PIPELINE_3D.md)
- [Diskarte sa Pagsubok](docs/testing.md)
- [Reference ng Screen at Mode](docs/screens.md)

---
*Na-update ang dokumentasyon noong 2026-03-17 sa panahon ng muling pagdidisenyo ng website at yugto ng pagsasama ng Stencil Mode.*