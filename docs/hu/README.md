# GraffitiXR

A GraffitiXR egy androidos alkalmazás utcai művészek számára. Rengeteg olyan alkalmazás létezik, amely rátakar egy képet a kameranézetre, így virtuálisan átrajzolhatod azt, de amikor egy falfestményt festek egy a telefonomon elmentett vázlat alapján, egy állvány használata nagyon akadályozhatja a folyamatot. Mindannyian rohangálunk. Én például a zsebembe teszem a telefont. Még az AR-t használó alkalmazások sem, amelyek stabilan és egy helyben tartják a képet, tudnak mit kezdeni a zseb sötétségével.

Így készítek valami jobbat a (szakértők által hívott) rácsos módszer átdolgozásával. Mindig arra gondoltam: "Miért nem lehet ezeket a specifikus firkákat elmenteni egyfajta állandó horgonyként, hogy a rétegzés mindig tökéletesen a megfelelő helyen maradjon?"

Tehát most pontosan ezt csinálják ezek a firkák.

Fel kellett találnom egy egyedi Gauss-splatting motort, amely a felhő segítsége nélkül működik Androidon – mert a graffiti, mint tudjuk, illegális.

Ezt egy úgynevezett Teleologikus SLAM-mel követtem: mivel tudjuk, hogyan kellene kinéznie az eredménynek, az OpenCV segítségével figyelem a haladásodat. Ez azt jelenti, hogy minél előrébb tartasz, a rétegzés annál szorosabban tapad a falra. Enélkül magával a festménnyel takarnád le azokat a jeleket, ami miatt az alkalmazás egyre kevésbé lenne pontos a folyamat során. Pontosan itt bukik el a többi hasonló alkalmazás.

Csak a móka kedvéért beépítettem a nem AR-alapú képrétegzési funkciót is a képátrajzoláshoz, pont olyat, amit a többi alkalmazásnál is megkapsz, ha éppen így szeretnéd csinálni. Vagy ha teljesen másra vágysz, ott a Makett mód. Kapj el egy fotót a falról, majd használd a gyors eszközeimet egy gyors maketthez. És ha nincs mit bizonyítanod, csak egyszerűen papírra szeretnél másolni valamit tökéletesen, a Rajzolás mód lehetővé teszi, hogy a telefonodat világítótáblaként használd: a képernyő bekapcsolva marad, a fényerő maximumon, a képet a helyére rögzíti, és minden érintést blokkol, amíg be nem fejezed.

Ezen kívül pedig ott van egy tisztességes, a témához illő tervezőeszközökből álló csomag is, többrétegű grafikai alkotás támogatásával. Folytathatnám, de úgy érzem, már így is eleget mondtam.

A **GraffitiXR** egy offline Android-alkalmazás utcai művészek számára. Az AR segítségével képeket vetít a falakra egy megbízhatóságon alapuló voxel-térképezési rendszer segítségével.

## Főbb jellemzők
*   **Elsősorban Offline:** Nincsenek felhőfüggőségek; nulla adatgyűjtés.
*   **Egyedi motor (MobileGS):** C++17 natív motor a 3D Gauss-splattinghoz és térbeli térképezéshez.
*   **Teljes ARCore Pipeline:** Élő kamerakép a `BackgroundRenderer` segítségével, színképkocka-relokalizáció és ARCore Mélységi API — melyek mind valós adatokat táplálnak a SLAM motorba.
*   **AzNavRail Felület:** Hüvelykujjal vezérelhető navigáció az egykezes terepi használathoz.
*   **Egységes GL Render Útvonal:** Az `ArRenderer` egyetlen `GLSurfaceView`-ban kezeli a kamerahátteret (`BackgroundRenderer`) és a SLAM voxel splatteket (`slamManager.draw()`).
*   **Többlencsés támogatás:** Automatikusan használja a kétkamerás sztereó mélységet a támogatott eszközökön; visszatér az optikai áramláshoz.
*   **Teleologikus Korrekció:** Automatikus térkép-valóság igazítás az OpenCV ujjlenyomat-azonosításával.

## Architektúra
Szigorúan szétválasztott többmodulos architektúra:
*   `:app` — Navigáció, `ArViewport` kompozábilis elem, kamerabirtoklás összehangolása.
*   `:feature:ar` — ARCore munkamenet, `ArRenderer`, `BackgroundRenderer`, szenzorfúzió, SLAM adatok betáplálása.
*   `:feature:editor` — Képmanipuláció, rétegkezelés.
*   `:feature:dashboard` — Projektkönyvtár, beállítások.
*   `:core:nativebridge` — `SlamManager` JNI híd, `MobileGS` voxel motor, OpenGL ES renderelés.
*   `:core:data` / `:core:domain` / `:core:common` — Tiszta Architektúra (Clean Architecture) adatréteg.

## Dokumentáció
- [Architektúra Áttekintése](docs/ARCHITECTURE.md)
- [Natív Motor Részletei](docs/NATIVE_ENGINE.md)
- [SLAM Konfiguráció és Finomhangolás](docs/SLAM_SETUP.md)
- [3D Pipeline Specifikáció](docs/PIPELINE_3D.md)
- [Tesztelési Stratégia](docs/testing.md)
- [Képernyők és Módok Referenciája](docs/screens.md)

---
*A dokumentációt 2026-03-17-én frissítették a weboldal újratervezése és a Sablon Mód integrációs szakasza során.*
