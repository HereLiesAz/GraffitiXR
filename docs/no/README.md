# GraffitiXR

GraffitiXR er en Android-app for gatekunstnere. Det finnes mange apper som legger et bilde over kameravisningen din, slik at du kan spore det virtuelt, men når jeg maler et veggmaleri basert på en skisse jeg har lagret på telefonen min, kan bruken av et stativ virkelig ødelegge flyten. Vi er overalt. Selv, jeg putter telefonen i lomma. Selv appene som bruker AR for å holde bildet stødig og på ett sted, klarer ikke det bunnløse mørket i lomma.

Så jeg lager noe bedre ved å gjenbruke (det de innvidde kaller) rutenettmetoden. Jeg tenkte alltid: "Hvorfor kan ikke disse spesifikke krotene lagres, som et vedvarende anker, slik at overlegget alltid havner nøyaktig på rett sted?"

Så, nå, det er hva disse krotene gjør.

Jeg måtte finne opp en tilpasset **fingerprinted relocalizer** som fungerer på Android uten hjelp fra skyen — fordi graffiti er, som du vet, ulovlig. Når du låser deg til en vegg, fanger appen et OpenCV-feature-**fingeravtrykk** av merkene dine — deskriptorer pluss en håndfull trianguerte 3D-punkter. Selv etter tap av sporing eller at skjermen har vært av, sammenligner motoren live kamerabildet med det fingeravtrykket og løser posisjonen (PnP) for å **klikke tilbake** og rette opp veggmaleriet ditt mot veggen igjen, på millisekunder — ingen sky, og ingen forhåndsskanning av hele rommet.

Og jeg fulgte det opp med det jeg kaller **Teleological SLAM** — siden vi vet hvordan resultatet skal se ut, bruker jeg OpenCV til å se etter fremgangen din, noe som betyr at jo lenger du har kommet, desto tettere fester overlegget seg til veggen. Uten dette ville du dekket disse merkene med selve maleriet, noe som gjør appen mindre nøyaktig etter hvert som du går. Det er akkurat der andre lignende apper virkelig svikter.

Begge disse — tilbake-klikk og det selv-utvidende fingeravtrykket — leveres foreløpig som en opt-in-bryter (Innstillinger > diagnostisk overlegg, av som standard) mens jeg validerer dem på ekte maskinvare, så ikke forvent at noen av dem er på som standard ennå.

Bare for moro skyld inkluderte jeg også ikke-AR, bildeoverleggsfunksjonen for bildesporing, akkurat som du får i de andre appene, i tilfelle det er mer din stil. Eller hvis du vil ha det litt raskere, er det **Mockup-modus**. Ta et bilde av veggen, så har jeg noen raske verktøy for en rask mockup. Og hvis du ikke har noe å bevise og bare vil ha noe kopiert perfekt over på papir, lar **Trace-modus** deg bruke telefonen som en lysboks — skjermen holdes på med lysstyrken skrudd opp, bildet ditt låses på plass, og alle berøringer blokkeres til du er ferdig.

Og så er det en solid samling relevante designverktøy for å klargjøre det ene bildet du plasserer — toner, fargebalanse, konturuttrekking, motivisolasjon. Å sette sammen flere bilder til ett er jobben til søsterappen for design, ikke denne appens. Jeg kunne fortsatt, men jeg føler at jeg allerede har det.

## Nøkkelfunksjoner
*   **Offline-First:** Ingen skyavhengigheter for noe appen gjør — sporing, rendering og designarbeid er alt lokalt. Det eneste som noensinne forlater enheten, er en krasjrapport, og den er opt-in og av som standard (Innstillinger > Krasjrapporter); se [`docs/en/PRIVACY_POLICY.md`](../en/PRIVACY_POLICY.md).
*   **Fingerprint Relocalization:** en native C++17 OpenCV-pipeline (ORB/SuperPoint-deskriptorer + PnP/RANSAC) tar et fingeravtrykk av merkene du tegner på veggen, og klikker overlegget tilbake på plass etter tap av sporing — helt offline, ingen forhåndsskanning av rommet.
*   **Klar for lomma (eksperimentelt, av som standard):** driftkorreksjon og et selv-utvidende fingeravtrykk (slik at tilbake-klikk kan overleve at den opprinnelige referansen males over) finnes og kan slås på fra Innstillinger > diagnostisk overlegg, men ingen av delene er validert på ekte maskinvare ennå — se [Teleological SLAM](../TELEOLOGICAL_SLAM.md).
*   **Dual-Lens Aware:** velger automatisk maskinvare-stereodybde på enheter som støtter det; andre enheter sporer via ARCores monokulære posisjonsestimat, uten separat dybdemåling.
*   **Co-op-modus:** kryptert, QR-parret sesjonsdeling, slik at en medarbeider kan følge vertens live lerret i AR. For øyeblikket bare vert → gjest — en gjests egne redigeringer synkroniseres ikke tilbake ennå.
*   **AzNavRail UI:** tommeldrevet, enhånds navigasjon designet for artister som holder en spraybokse.

## Moduser
*   **AR-veggmaleri:** Kjerneinstrumentet for presis forankring av digitale konsepter til fysiske overflater, sporet via fingeravtrykk-relokaliseringen beskrevet ovenfor.
*   **Mockup-modus:** Raske verktøy for å visualisere lag og blandingsmoduser oppå statiske veggfotoer.
*   **Trace (lysboks):** Fullt opplyst flate for å kopiere over på papir, med berøringslås, automatisk innkjørende skinne og fysisk volumknapp-utgang (Opp, Ned, Opp, Ned).
*   **Overlay:** Ikke-AR bildesporing — referansebildet ditt legges over live kamera (CameraX) med justerbar gjennomsiktighet. På det lille antallet enheter uten ARCore kan denne modusen i stedet spore en form du markerer på veggen, ved hjelp av samme OpenCV-pipeline, kun planar.
*   **Design:** Plasserings- og lesbarhetsverktøy for det ene designbildet som spores — gjennomsiktighet/lysstyrke/kontrast/metning/fargebalanse, inverter, konturuttrekking og motivisolasjon. Det finnes nøyaktig ett designbilde; å sette sammen flere bilder til ett er jobben til søsterappen for design, ikke denne appens.

## Lisensiering
GraffitiXR er **kildetilgjengelig, ikke åpen kildekode.** Appen, `core:*`-modulene og AR-/SLAM-/teleologisk-motoren er lisensiert under **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](../../LICENSE)); det erklærte utvidelses-API-overflaten og asset-importørene er **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). Den **kompilerte appen er gratis for alle å bruke, inkludert betalte oppdrag** — den ikke-kommersielle klausulen gjelder gjenbruk av *kildekoden*, ikke gatekunstnere som utfører betalt arbeid. Se [`docs/LICENSING.md`](../LICENSING.md) for den autoritative, sti-for-sti-oppdelingen og presedens. Medfølgende tredjepartskomponenter (OpenCV, ML Kit, …) beholder sine egne opphavslisenser.

## Arkitektur
Strengt frakoblet multimodularkitektur:
*   `:app` — Navigasjon, kameraorkestrering og Hilt-avhengighetsinjeksjon.
*   `:feature:ar` — ARCore-sesjonshåndtering, `ArRenderer` og SLAM-databehandling.
*   `:feature:editor` — Bildemanipulering og justeringer for ett bilde.
*   `:feature:dashboard` — Prosjektbibliotek, onboarding og innstillinger.
*   `:core:nativebridge` — Native C++-motor (`MobileGS`), JNI-bro og relokaliseringstråder. OpenCV selv er en Maven Central-avhengighet (`org.opencv:opencv`), ikke en medfølgende modul.
*   `:android_collaboration_module` — node-til-node-nettverk for Co-op-modus (vert → gjest; se Nøkkelfunksjoner ovenfor).
*   `:core:data` / `:core:domain` / `:core:common` — Enhetlig datalag og wearable-abstraksjon.
*   `:core:design` — Delt Compose-designsystem (gjenbrukbare kontroller og overlegg).

## Dokumentasjon
- [Arkitekturoversikt](../ARCHITECTURE.md)
- [Native Engine-detaljer](../NATIVE_ENGINE.md)
- [SLAM-oppsett og relokalisering](../SLAM_SETUP.md)
- [Teleological SLAM](../TELEOLOGICAL_SLAM.md)
- [Ytelsesguide](../performance.md)
- [Teststrategi](../testing.md)
- [Dataformater](../data_formats.md)
- [Bidra](../contributing.md)
- [Utgivelse og Google Play-distribusjon](../RELEASE.md)

---
*Dokumentasjonen ble oppdatert 2026-09-04: brakt i samsvar med den korrigerte engelske hoved-README-en — den påståtte "Persistent Voxel Memory"-motoren, "Single GL Render Path" med `slamManager.draw()`, og fallback til optisk flyt for flerlinsestøtte finnes ikke i koden og er erstattet med den faktiske fingeravtrykk-relokaliseringen, Dual-Lens Aware-oppførselen, Co-op-modus, Trace-modus og en lisensieringsseksjon. Ødelagte lenker til `docs/PIPELINE_3D.md` (finnes ikke) og `docs/screens.md` (feil sti) er fjernet eller rettet. Forrige oppdatering: 2026-03-17, under redesign av nettstedet og integreringsfasen for sjablongmodus (sjablongmodus er siden fjernet fra appen).*
