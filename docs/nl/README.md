# GraffitiXR

GraffitiXR is een Android-app voor straatartiesten. Er zijn tal van apps die een afbeelding over je cameraweergave heen leggen, zodat je deze virtueel kunt volgen, maar als ik een muurschildering maak op basis van een schets die ik op mijn telefoon heb opgeslagen, kan het gebruik van een statief de flow echt verstoren. We zijn overal en nergens. Ik stop mijn telefoon in mijn zak. Zelfs de apps die AR gebruiken om het beeld stabiel en op één plek te houden, kunnen niet omgaan met de verschrikkelijke duisternis van de broekzak.

Dus ik maak iets beters door de rastermethode opnieuw te gebruiken (wat de kenners noemen). Ik dacht altijd: "Waarom kunnen deze specifieke doodles niet worden opgeslagen, als een blijvend anker, zodat de overlay altijd precies op de juiste plek staat?"

Dus dat is wat die krabbels doen.

Ik moest een aangepaste **fingerprinted relocalizer** uitvinden die op Android werkt zonder de hulp van de cloud — omdat graffiti, weet je, illegaal is. Zodra je op een muur vergrendelt, legt de app een OpenCV-feature-**vingerafdruk** van je markeringen vast — descriptors plus een handvol getrianguleerde 3D-punten. Zelfs na verlies van tracking of het uitschakelen van het scherm vergelijkt de engine het live camerabeeld met die vingerafdruk en lost de pose op (PnP) om **terug te klikken** en je muurschildering weer op de muur uit te lijnen, binnen milliseconden — geen cloud, en geen voorafgaande scan van de hele ruimte.

En ik heb dat opgevolgd met wat ik een **Teleological SLAM** noem — omdat we weten hoe het resultaat eruit hoort te zien, gebruik ik OpenCV om je voortgang te herkennen, wat betekent dat hoe verder je bent, hoe strakker de overlay aan de muur blijft plakken. Zonder dit zou je die markeringen met de schildering zelf bedekken, waardoor de app gaandeweg minder nauwkeurig wordt. Dat is precies waar andere apps zoals deze echt falen.

Beide functies — terug-klikken en de zelf-uitbreidende vingerafdruk — zijn momenteel een opt-in schakelaar (Instellingen > diagnostische overlay, standaard uit) terwijl ik ze op echte hardware valideer, dus verwacht ze nog niet standaard aan.

Voor de lol heb ik ook de niet-AR, beeld-overlay-functionaliteit toegevoegd voor het traceren van afbeeldingen, net zoals je bij die andere apps krijgt, voor als je daar zin in hebt. Of als je wat meer wilt, is er **Mockup-modus**. Maak een foto van de muur en dan heb ik wat snelle tools voor een snelle mockup. En als je niets te bewijzen hebt en gewoon iets perfect op papier gekopieerd wilt hebben, laat **Trace-modus** je je telefoon als lichtbak gebruiken: het scherm blijft aan met de helderheid omhoog, je afbeelding wordt vergrendeld en alle aanrakingen worden geblokkeerd totdat je klaar bent.

En dan is er nog een prima reeks relevante ontwerptools voor het voorbereiden van de ene afbeelding die je plaatst — toon, kleurbalans, contour-extractie, onderwerpisolatie. Meerdere afbeeldingen tot één geheel samenstellen is de taak van de bijbehorende ontwerp-app, niet van deze. Ik zou nog wel even door kunnen gaan, maar ik heb het gevoel dat ik dat al gedaan heb.

## Belangrijkste kenmerken
*   **Offline-First:** Geen cloudafhankelijkheden voor wat de app ook doet — tracking, rendering en ontwerpwerk zijn allemaal lokaal. Het enige dat ooit het apparaat verlaat, is een crashrapport, en dat is opt-in en standaard uit (Instellingen > Crashrapporten); zie [`docs/en/PRIVACY_POLICY.md`](../en/PRIVACY_POLICY.md).
*   **Fingerprint Relocalization:** een native C++17 OpenCV-pipeline (ORB/SuperPoint-descriptors + PnP/RANSAC) legt een vingerafdruk vast van de markeringen die je op de muur tekent en klikt de overlay terug na verlies van tracking — volledig offline, geen voorafgaande scan van de ruimte.
*   **Klaar voor de broekzak (experimenteel, standaard uit):** driftcorrectie en een zelf-uitbreidende vingerafdruk (zodat terug-klikken de originele referentie kan overleven, ook als die overgeschilderd wordt) bestaan en kunnen worden ingeschakeld via Instellingen > diagnostische overlay, maar geen van beide is nog gevalideerd op echte hardware — zie [Teleological SLAM](../TELEOLOGICAL_SLAM.md).
*   **Dual-Lens Aware:** selecteert automatisch hardware-stereodiepte op apparaten die dit bieden; andere apparaten volgen via de monoculaire pose van ARCore, zonder aparte dieptemeting.
*   **Co-op-modus:** versleuteld, met QR-code gekoppeld sessies delen, zodat een medewerker het live canvas van de host in AR kan volgen. Momenteel alleen host → gast — de eigen bewerkingen van een gast worden nog niet teruggesynchroniseerd.
*   **AzNavRail UI:** duimgestuurde, eenhandige navigatie, ontworpen voor artiesten met een spuitbus in de hand.

## Modi
*   **AR Muurschildering:** Het kerninstrument voor het nauwkeurig verankeren van digitale ontwerpen aan fysieke oppervlakken, getrackt via de hierboven beschreven fingerprint relocalizer.
*   **Mockup-modus:** Snelle tools om lagen en overvloeimodi te visualiseren op statische foto's van de muur.
*   **Trace (lichtbak):** Volledig verlicht oppervlak om op papier over te trekken, met aanraakvergrendeling, automatisch intrekkende rail en fysieke volumeknop-exit (Omhoog, Omlaag, Omhoog, Omlaag).
*   **Overlay:** Niet-AR-beeldtracering — je referentieafbeelding wordt over de live camera (CameraX) gelegd met instelbare dekking. Op het kleine aantal apparaten zonder ARCore kan deze modus in plaats daarvan een vorm volgen die je op de muur markeert, met dezelfde OpenCV-pipeline, alleen vlak (planair).
*   **Design:** Plaatsings- en leesbaarheidstools voor de ene ontwerpafbeelding die wordt getraceerd — dekking/helderheid/contrast/verzadiging/kleurbalans, inverteren, contour-extractie en onderwerpisolatie. Er is precies één ontwerpafbeelding; meerdere afbeeldingen tot één geheel samenstellen is de taak van de bijbehorende ontwerp-app, niet van deze app.

## Licentiëring
GraffitiXR is **source-available, geen open source.** De app, de `core:*`-modules en de AR-/SLAM-/teleologische engine zijn gelicentieerd onder **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](../../LICENSE)); het gedeclareerde extensie-API-oppervlak en de asset-importers zijn **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). De **gecompileerde app is gratis te gebruiken voor iedereen, inclusief betaalde opdrachten** — de niet-commerciële voorwaarde geldt voor hergebruik van de *broncode*, niet voor muurschilders die betaald werk doen. Zie [`docs/LICENSING.md`](../LICENSING.md) voor de gezaghebbende, pad-voor-pad-indeling en voorrangsregels. Gebundelde externe onderdelen (OpenCV, ML Kit, …) behouden hun eigen upstream-licenties.

## Architectuur
Strikt ontkoppelde architectuur met meerdere modules:
*   `:app` — Navigatie, camera-orkestratie en Hilt dependency injection.
*   `:feature:ar` — ARCore-sessiebeheer, `ArRenderer` en SLAM-gegevensverwerking.
*   `:feature:editor` — Beeldmanipulatie en aanpassingen voor één afbeelding.
*   `:feature:dashboard` — Projectbibliotheek, onboarding en instellingen.
*   `:core:nativebridge` — Native C++-engine (`MobileGS`), JNI-bridge en relocalisatie-threads. OpenCV zelf is een Maven Central-dependency (`org.opencv:opencv`), geen gebundelde module.
*   `:android_collaboration_module` — peer-to-peer-netwerken voor Co-op-modus (host → gast; zie Belangrijkste kenmerken hierboven).
*   `:core:data` / `:core:domain` / `:core:common` — Uniforme datalaag en wearable-abstractie.
*   `:core:design` — Gedeeld Compose-ontwerpsysteem (herbruikbare controls en overlays).

## Documentatie
- [Architectuuroverzicht](../ARCHITECTURE.md)
- [Native engine-details](../NATIVE_ENGINE.md)
- [SLAM-configuratie en herlokalisatie](../SLAM_SETUP.md)
- [Teleological SLAM](../TELEOLOGICAL_SLAM.md)
- [Prestatiegids](../performance.md)
- [Teststrategie](../testing.md)
- [Gegevensformaten](../data_formats.md)
- [Bijdragen](../contributing.md)
- [Release & Google Play-distributie](../RELEASE.md)

---
*Documentatie bijgewerkt op 2026-09-04: afgestemd op de gecorrigeerde Engelse hoofd-README — de vermeende "Persistent Voxel Memory"-engine, de "Single GL Render Path" met `slamManager.draw()`, en het optische-stroom-fallback voor meerdere lenzen bestaan niet in de code en zijn vervangen door de werkelijke fingerprinted relocalizer, Dual-Lens Aware-werking, Co-op-modus, Trace-modus en licentiëringsparagraaf. Kapotte links naar `docs/PIPELINE_3D.md` (bestaat niet) en `docs/screens.md` (verkeerd pad) zijn verwijderd of hersteld. Vorige update: 2026-03-17, tijdens het herontwerp van de website en de integratiefase van de stencilmodus (stencilmodus is inmiddels uit de app verwijderd).*
