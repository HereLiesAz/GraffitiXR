# GraffitiXR

A GraffitiXR egy androidos alkalmazás utcai művészek számára. Rengeteg olyan alkalmazás létezik, amely rátakar egy képet a kameranézetre, így virtuálisan átrajzolhatod azt, de amikor egy falfestményt festek egy a telefonomon elmentett vázlat alapján, egy állvány használata nagyon akadályozhatja a folyamatot. Mindannyian rohangálunk. Én például a zsebembe teszem a telefont. Még az AR-t használó alkalmazások sem, amelyek stabilan és egy helyben tartják a képet, tudnak mit kezdeni a zseb sötétségével.

Így készítek valami jobbat a (szakértők által hívott) rácsos módszer átdolgozásával. Mindig arra gondoltam: "Miért nem lehet ezeket a specifikus firkákat elmenteni egyfajta állandó horgonyként, hogy a rétegzés mindig tökéletesen a megfelelő helyen maradjon?"

Tehát most pontosan ezt csinálják ezek a firkák.

Fel kellett találnom egy egyedi **ujjlenyomat-alapú újralokalizálót** (fingerprinted relocalizer), amely a felhő segítsége nélkül működik Androidon – mert a graffiti, mint tudjuk, illegális. Amikor rögzíted a falat, az alkalmazás egy OpenCV jellemző-**ujjlenyomatot** készít a jeleidről – leírókat (descriptors), plusz néhány háromszögelt 3D pontot. Még követésvesztés vagy képernyő-kikapcsolás után is a motor összeveti az élő kamerát ezzel az ujjlenyomattal, és megoldja a pózt (PnP), hogy ezredmásodpercek alatt **visszapattanjon**, és újra a falhoz igazítsa a falfestményedet – felhő nélkül, és a helyiség előzetes bescannelése nélkül.

Ezt egy úgynevezett **Teleologikus SLAM**-mel követtem: mivel tudjuk, hogyan kellene kinéznie az eredménynek, az OpenCV segítségével figyelem a haladásodat. Ez azt jelenti, hogy minél előrébb tartasz, a rétegzés annál szorosabban tapad a falra. Enélkül magával a festménnyel takarnád le azokat a jeleket, ami miatt az alkalmazás egyre kevésbé lenne pontos a folyamat során. Pontosan itt bukik el a többi hasonló alkalmazás.

Mindkettő – a visszapattanás és az önbővülő ujjlenyomat – jelenleg egy opt-in kapcsolóként érhető el (Beállítások > diagnosztikai overlay, alapértelmezetten kikapcsolva), amíg valós hardveren validálom őket, szóval egyelőre ne számíts egyikre sem alapból.

Csak a móka kedvéért beépítettem a nem AR-alapú képrétegzési funkciót is a képátrajzoláshoz, pont olyat, amit a többi alkalmazásnál is megkapsz, ha éppen így szeretnéd csinálni. Vagy ha teljesen másra vágysz, ott a Makett mód. Kapj el egy fotót a falról, majd használd a gyors eszközeimet egy gyors maketthez. És ha nincs mit bizonyítanod, csak egyszerűen papírra szeretnél másolni valamit tökéletesen, a Rajzolás mód lehetővé teszi, hogy a telefonodat világítótáblaként használd: a képernyő bekapcsolva marad, a fényerő maximumon, a képet a helyére rögzíti, és minden érintést blokkol, amíg be nem fejezed.

Ezen kívül pedig ott van egy tisztességes, a témához illő tervezőeszköz-csomag az egyetlen elhelyezendő kép előkészítéséhez — tónus, színegyensúly, körvonal-kinyerés, téma-elkülönítés. Több kép egybe rendezése a társ-tervezőalkalmazás feladata, nem ezé. Folytathatnám, de úgy érzem, már így is eleget mondtam.

## Főbb jellemzők
*   **Elsősorban Offline:** Nincs felhőfüggőség semmihez, amit az alkalmazás csinál — a követés, a renderelés és a tervezési munka is helyben történik. Az egyetlen dolog, ami valaha elhagyja az eszközt, egy hibajelentés, és az is opt-in, alapértelmezetten kikapcsolt (Beállítások > Hibajelentések); lásd [`docs/hu/PRIVACY_POLICY.md`](PRIVACY_POLICY.md).
*   **Ujjlenyomat-alapú Újralokalizáció:** egy C++17 natív OpenCV pipeline (ORB/SuperPoint leírók + PnP/RANSAC) ujjlenyomatot készít a falra rajzolt jelekről, és követésvesztés után visszapattantja a rétegzést — teljesen offline, helyiség-előszkennelés nélkül.
*   **Zsebre Kész (kísérleti, alapértelmezetten kikapcsolva):** sodródás-korrekció és önbővülő ujjlenyomat (hogy a visszapattanás túlélje, ha az eredeti referenciát lefestik) létezik, és bekapcsolható a Beállítások > diagnosztikai overlay menüből, de egyiket sem validálták még valós hardveren — lásd [Teleologikus SLAM](../TELEOLOGICAL_SLAM.md).
*   **Kétlencsés Tudatosság:** automatikusan kiválasztja a hardveres sztereó mélységet azokon az eszközökön, amelyek ezt biztosítják; más eszközök az ARCore monokuláris pózbecslésével követnek, külön mélységadat nélkül.
*   **Co-op Mód:** titkosított, QR-kóddal párosított munkamenet-megosztás, így egy közreműködő élőben figyelheti a host vásznát AR-ben. Jelenleg csak host → guest irányban működik — a vendég saját szerkesztései még nem szinkronizálódnak vissza.
*   **AzNavRail Felület:** hüvelykujjal vezérelt, egykezes navigáció művészeknek, akik festékszóróval a kezükben dolgoznak.

## Módok
*   **AR Falfestmény:** A digitális koncepciók fizikai felületekhez rögzítésének fő precíziós eszköze, a fenti ujjlenyomat-alapú újralokalizátorral követve.
*   **Makett mód:** Gyors eszközök rétegek és keverési módok megjelenítéséhez statikus falfotókon.
*   **Rajzolás (fénydoboz):** Teljes fényerejű felület papírra másoláshoz, érintés-zárral, a sín automatikus visszahúzásával és fizikai hangerő-gombos kilépéssel (Fel, Le, Fel, Le).
*   **Rétegzés (Overlay):** Nem-AR képkövetés — a referenciaképed ráhelyezve az élő kamerára (CameraX), állítható átlátszósággal. Azon kevés eszközön, ahol nincs ARCore, ez a mód helyette egy, a falra rajzolt alakzatot tud követni ugyanazzal az OpenCV pipeline-nal, csak síkban.
*   **Tervezés:** Elhelyezési és olvashatósági eszközök az egyetlen, átrajzolandó tervezőképhez — átlátszóság/fényerő/kontraszt/telítettség/színegyensúly, invertálás, körvonal-kinyerés és téma-elkülönítés. Pontosan egy tervezőkép van; több kép egybe rendezése a társ-tervezőalkalmazás feladata, nem ezé.

## Licencelés
A GraffitiXR **forráskód-elérhető, nem nyílt forráskódú.** Az alkalmazás, a `core:*` modulok, valamint az AR / SLAM / teleologikus motor a **PolyForm Noncommercial 1.0.0** licenc alatt állnak ([`/LICENSE`](../../LICENSE)); a deklarált bővítmény API felület és az eszközimportálók **MIT** licencesek ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). A **lefordított alkalmazás bárki számára ingyenesen használható, fizetett megbízások esetén is** — a noncommercial feltétel a *forráskód* újrafelhasználására vonatkozik, nem a fizetett munkát végző falfestőkre. Lásd [`docs/LICENSING.md`](../LICENSING.md) a hiteles, útvonal szerinti elrendezésért és elsőbbségi sorrendért. A csomagolt harmadik felek (OpenCV, ML Kit, …) megtartják saját upstream licenceiket.

## Architektúra
Szigorúan szétválasztott, többmodulos Clean Architecture:
*   `:app` — Navigáció, kameraorchesztráció és Hilt függőség-injektálás.
*   `:feature:ar` — ARCore munkamenet-kezelés, `ArRenderer`, és SLAM adatfeldolgozás.
*   `:feature:editor` — Egyetlen kép manipulálása és módosításai.
*   `:feature:dashboard` — Projektkönyvtár, onboarding és beállítások.
*   `:core:nativebridge` — Natív C++ motor (`MobileGS`), JNI híd és újralokalizációs szálak. Maga az OpenCV egy Maven Central függőség (`org.opencv:opencv`), nem egy becsomagolt modul.
*   `:android_collaboration_module` — peer-to-peer hálózat a Co-op Módhoz (host → guest; lásd a Főbb jellemzők fenti szakaszát).
*   `:core:data` / `:core:domain` / `:core:common` — Egységes adatréteg és viselhető eszköz absztrakció.
*   `:core:design` — Megosztott Compose design rendszer (újrafelhasználható vezérlők és overlay-ek).

## Dokumentáció
- [Architektúra Áttekintése](../ARCHITECTURE.md)
- [Natív Motor Részletei](../NATIVE_ENGINE.md)
- [SLAM Konfiguráció és Újralokalizáció](../SLAM_SETUP.md)
- [Teleologikus SLAM](../TELEOLOGICAL_SLAM.md)
- [Teljesítmény Útmutató](../performance.md)
- [Tesztelési Stratégia](../testing.md)
- [Adatformátumok](../data_formats.md)
- [Közreműködés](../contributing.md)
- [Kiadás és Google Play terjesztés](../RELEASE.md)

---
*A dokumentációt 2026-09-04-én frissítettük: kijavítottuk a "Persistent Voxel Memory" motorra és a többrétegű tervezésre vonatkozó téves állításokat a jelenlegi kódbázis szerint — a valódi mechanizmus egy ujjlenyomat-alapú újralokalizáló (fingerprinted relocalizer), és pontosan egy tervezőkép van, nem több réteg. Hozzáadtuk az Ujjlenyomat-alapú Újralokalizáció, a Co-op Mód, a Rajzolás mód és a Licencelés szakaszokat, és kijavítottuk a törött dokumentációs linkeket (a nem létező `docs/PIPELINE_3D.md`-t eltávolítottuk, a `docs/screens.md` és más útvonalak javítva). Korábbi frissítés: 2026-03-17, a weboldal újratervezése és a Sablon Mód integrációs szakasza során.*
