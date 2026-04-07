# GraffitiXR

GraffitiXR er en Android-app for gatekunstnere. Det er mange apper som overlapper et bilde på kameravisningen din, slik at du praktisk talt kan spore det, men når jeg maler et veggmaleri basert på en skisse som jeg har lagret på telefonen min, kan bruken av et stativ virkelig ebbe ut. Vi er over alt. Jeg, jeg la telefonen i lommen. Selv appene som bruker AR for å holde bildet stødig og på ett sted kan ikke takle det avgrunnsdype mørket i lommen.

Så jeg gjør noe bedre ved å gjenbruke (det de som er kjente kaller) rutenettmetoden. Jeg tenkte alltid: "Hvorfor kan ikke disse spesifikke doodlene lagres, som et vedvarende anker, slik at overlegget alltid er på rett sted?"

Så, nå, det er hva disse doodlene gjør.

Jeg måtte finne opp en tilpasset Gaussian Slatting-motor som fungerer på Android uten hjelp fra skyen – fordi graffiti er ulovlig.

Og jeg fulgte det opp med det jeg kaller en Teleologisk Slam – siden vi vet hvordan resultatet skal se ut, bruker jeg OpenCV for å se etter fremgangen din, noe som betyr at jo lenger du er, jo tettere fester overlegget seg til veggen. Uten dette ville du dekket disse merkene med selve maleriet, noe som gjør appen mindre nøyaktig mens du går. Det er akkurat der andre apper som dette virkelig mislykkes.

Bare for skjorter og briller inkluderte jeg ikke-AR, bildeoverleggsfunksjonalitet for bildesporing, akkurat som du får med de andre appene, i tilfelle du gråter slik. Eller hvis du gråter, er det Mockup-modus. Ta et bilde av veggen, så fikk jeg noen raske verktøy for en rask mockup. Og hvis du ikke har noe å bevise, vil du bare ha noe perfekt kopiert til papiret, sporingsmodus lar deg bruke telefonen som en lysboks, holde skjermen på med lysstyrken skrudd opp, låse bildet på plass og blokkere alle berøringer til du er ferdig.

Og så er det en anstendig serie med relevante designverktøy, med støtte for flerlags grafisk oppretting. Jeg kunne fortsette, men jeg føler at jeg allerede har gjort det.

**GraffitiXR** er en offline Android-app for gateartister. Den bruker AR til å projisere bilder på vegger ved hjelp av et tillitsbasert voxel-kartleggingssystem.

## Nøkkelfunksjoner
* **Offline-First:** Ingen skyavhengigheter; null data samlet inn.
* **Custom Engine (MobileGS):** C++17 innebygd motor for 3D Gaussian Slatting og romlig kartlegging.
* **Full ARCore Pipeline:** Live kamerafeed via `BackgroundRenderer`, fargerammeflytting og ARCore Depth API – alle mater ekte data til SLAM-motoren.
* **AzNavRail UI:** Tommeldrevet navigasjon for enhåndsbruk i felten.
* **Single GL Render Path:** `ArRenderer` håndterer både kamerabakgrunn (`BackgroundRenderer`) og SLAM voxel-markeringer (`slamManager.draw()`) i en enkelt `GLSurfaceView`.
* **Støtte for flere linser:** Bruker automatisk stereodybde med to kameraer på støttede enheter; faller tilbake til optisk flyt.
* **Teleologisk korreksjon:** Automatisk kart-til-verden-justering ved hjelp av OpenCV-fingeravtrykk.

## Arkitektur
Strengt frakoblet multimodularkitektur:
* `:app` — Navigasjon, 'ArViewport' komponerbar, orkestrering av kameraeierskap.
* `:feature:ar` — ARCore-økt, `ArRenderer`, `BackgroundRenderer`, sensorfusjon, SLAM-datamating.
* `:feature:editor` — Bildemanipulering, lagadministrasjon.
* `:funksjon:dashboard` — Prosjektbibliotek, innstillinger.
* `:core:nativebridge` — `SlamManager` JNI-bro, `MobileGS` voxelmotor, OpenGL ES-gjengivelse.
* `:core:data` / `:core:domain` / `:core:common` — Clean Architecture-datalag.

## Dokumentasjon
- [Arkitekturoversikt](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM-konfigurasjon og -innstilling](docs/SLAM_SETUP.md)
- [3D Pipeline Specification](docs/PIPELINE_3D.md)
- [Teststrategi](docs/testing.md)
- [Skjerm- og modusreferanse](docs/screens.md)



---
*Dokumentasjonen ble oppdatert 2026-03-17 under redesign av nettstedet og integreringsfasen for sjablongmodus.*
