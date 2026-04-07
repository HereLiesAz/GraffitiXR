# GraffitiXR

GraffitiXR is een Android-app voor straatartiesten. Er zijn tal van apps die een afbeelding over je cameraweergave heen leggen, zodat je deze virtueel kunt volgen, maar als ik een muurschildering maak op basis van een schets die ik op mijn telefoon heb opgeslagen, kan het gebruik van een statief de stroom echt wegnemen. We zijn overal verdomme. Ik, ik stop mijn telefoon in mijn zak. Zelfs de apps die AR gebruiken om het beeld stabiel en op één plek te houden, kunnen niet omgaan met de verschrikkelijke duisternis van de broekzak.

Dus ik maak iets beters door de rastermethode opnieuw te gebruiken (wat de kenners noemen). Ik dacht altijd: "Waarom kunnen deze specifieke doodles niet worden opgeslagen, als een blijvend anker, zodat de overlay altijd precies op de juiste plek staat?"

Dus dat is wat die krabbels doen.

Ik moest een aangepaste Gaussiaanse Splatting-engine uitvinden die op Android werkt zonder de hulp van de cloud - omdat graffiti, weet je, illegaal is.

En ik volgde het op met wat ik een Teleological Slam noem - omdat we weten hoe het resultaat eruit zou moeten zien, gebruik ik OpenCV om je voortgang te bekijken, wat betekent dat hoe verder je komt, hoe strakker de overlay aan de muur blijft plakken. Zonder dit zou je die markeringen met het schilderij zelf bedekken, waardoor de app gaandeweg minder nauwkeurig wordt. Dat is precies waar andere apps zoals deze echt falen.

Alleen voor shirts en brillen heb ik de niet-AR-functie voor beeldoverlay toegevoegd voor het traceren van afbeeldingen, net zoals je krijgt met die andere apps, voor het geval je zo schreeuwt. Of als je wilt, is er de Mockup-modus. Maak een foto van de muur en dan heb ik wat snel gereedschap voor een snelle mockup. En als u niets te bewijzen heeft, maar gewoon wilt dat iets perfect op papier wordt gekopieerd, kunt u met de Trace-modus uw telefoon als lightbox gebruiken, waarbij u uw scherm aanhoudt met de helderheid omhoog, uw afbeelding op zijn plaats vergrendelt en alle aanrakingen blokkeert totdat u klaar bent.

En dan is er nog een behoorlijke reeks relevante ontwerptools, met ondersteuning voor grafische creatie met meerdere lagen. Ik zou nog wel even door kunnen gaan, maar ik heb het gevoel dat ik dat al gedaan heb.

**GraffitiXR** is een offline Android-app voor straatartiesten. Het maakt gebruik van AR om afbeeldingen op muren te projecteren met behulp van een op vertrouwen gebaseerd voxel-mapping-systeem.

## Belangrijkste kenmerken
*   **Offline-First:** Geen cloudafhankelijkheden; nul gegevens verzameld.
*   **Aangepaste engine (MobileGS):** Native C++17-engine voor 3D Gaussiaanse splatting en ruimtelijke mapping.
*   **Volledige ARCore Pipeline:** Live camerafeed via `BackgroundRenderer`, herlokalisatie van kleurframes en ARCore Depth API - die allemaal echte gegevens naar de SLAM-engine sturen.
*   **AzNavRail UI:** Duimgestuurde navigatie voor gebruik met één hand in het veld.
*   **Enkel GL Render-pad:** `ArRenderer` verwerkt zowel de camera-achtergrond (`BackgroundRenderer`) als SLAM-voxelmarkeringen (`slamManager.draw()`) in een enkele `GLSurfaceView`.
*   **Ondersteuning voor meerdere lenzen:** Maakt automatisch gebruik van dubbele camera-stereodiepte op ondersteunde apparaten; valt terug naar optische stroom.
*   **Teleologische correctie:** Automatische uitlijning van kaart naar wereld met behulp van OpenCV-vingerafdrukken.

## Architectuur
Strikt ontkoppelde architectuur met meerdere modules:
*   `:app` — Navigatie, `ArViewport` componeerbaar, orkestratie van camera-eigendom.
*   `:feature:ar` — ARCore-sessie, `ArRenderer`, `BackgroundRenderer`, sensorfusie, SLAM-gegevensinvoer.
*   `:feature:editor` — Beeldmanipulatie, laagbeheer.
*   `:feature:dashboard` — Projectbibliotheek, instellingen.
*   `:core:nativebridge` — `SlamManager` JNI-bridge, `MobileGS` voxel-engine, OpenGL ES-rendering.
*   `:core:data` / `:core:domain` / `:core:common` — Clean Architecture-gegevenslaag.

## Documentatie
- [Architectuuroverzicht](docs/ARCHITECTURE.md)
- [Native engine-details](docs/NATIVE_ENGINE.md)
- [SLAM-configuratie en afstemming](docs/SLAM_SETUP.md)
- [3D-pijplijnspecificatie](docs/PIPELINE_3D.md)
- [Teststrategie](docs/testing.md)
- [Scherm- en modusreferentie](docs/screens.md)



---
*Documentatie bijgewerkt op 17-03-2026 tijdens het herontwerp van de website en de integratiefase van de stencilmodus.*
