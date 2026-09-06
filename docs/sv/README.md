# GraffitiXR

GraffitiXR är en Android-app för gatukonstnärer. Det finns massor av appar som lägger en bild ovanpå din kameravy så att du kan spåra den virtuellt, men när jag målar en väggmålning utifrån en skiss jag har sparat på telefonen kan ett stativ verkligen bromsa flödet. Vi rör oss hela tiden. Jag själv stoppar telefonen i fickan. Även apparna som använder AR för att hålla bilden stilla på ett ställe klarar inte fickans totala mörker.

Så jag gör något bättre genom att återanvända (vad de invigda kallar) rutnätsmetoden. Jag tänkte alltid: "Varför kan inte just de här kladdarna sparas, som ett bestående ankare, så att överlägget alltid ligger precis rätt?"

Så nu är det vad de där kladdarna gör.

Jag var tvungen att uppfinna en egen **fingeravtrycksbaserad omlokaliserare** som fungerar på Android utan hjälp av molnet — för graffiti är, som ni vet, olagligt. När du låser mot en vägg tar appen ett OpenCV-baserat funktionsavtryck (**fingerprint**) av dina märken — deskriptorer plus en handfull triangulerade 3D-punkter. Även efter förlorad spårning eller en avstängd skärm matchar motorn den levande kamerabilden mot detta avtryck och löser positionen (PnP) för att **snäppa tillbaka** och rikta in väggmålningen mot väggen igen på millisekunder — utan moln och utan att förhandsskanna hela rummet.

Och jag följde upp det med det jag kallar **teleologisk SLAM** — eftersom vi vet hur resultatet ska se ut använder jag OpenCV för att hålla koll på dina framsteg, vilket betyder att ju längre du kommit, desto tätare fäster överlägget mot väggen. Utan det skulle du täcka över de där märkena med själva målningen, vilket skulle göra appen mindre exakt ju längre du kom. Det är precis där andra liknande appar verkligen misslyckas.

Både "snäpp-tillbaka" och det självutvidgande avtrycket levereras just nu som en valbar inställning (Inställningar > diagnostikoverlay, avstängd som standard) medan jag validerar dem på riktig hårdvara, så förvänta dig inte att båda fungerar direkt ur lådan ännu.

Bara för skojs skull har jag också med den icke-AR-baserade bildöverläggsfunktionen för bildspårning, precis som du får i de andra apparna, ifall du föredrar det. Och om du inte behöver bevisa något finns **Mockup-läget**. Ta en bild på väggen, så finns det några snabba verktyg för en snabb mockup. Och om du bara vill kopiera något perfekt till papper, låter **Trace-läget** dig använda telefonen som en ljuslåda: skärmen är på med hög ljusstyrka, bilden är låst på plats och alla beröringar blockeras tills du är klar.

Och sen finns det en anständig svit av relevanta designverktyg för att förbereda just den enda bilden du placerar — ton, färgbalans, konturextraktion, motivisolering. Att sätta samman flera bilder till en är kompanjonappens jobb för design, inte den här appens. Jag skulle kunna fortsätta, men jag känner att jag redan har gjort det.

## Nyckelfunktioner
*   **Offline-First:** Inga molnberoenden för något appen gör — spårning, rendering och designarbete sker helt lokalt. Det enda som någonsin lämnar enheten är en kraschrapport, och den är valbar och avstängd som standard (Inställningar > Kraschrapporter); se [`docs/sv/PRIVACY_POLICY.md`](PRIVACY_POLICY.md).
*   **Fingeravtrycksbaserad omlokalisering:** en C++17-baserad, inbyggd OpenCV-pipeline (ORB/SuperPoint-deskriptorer + PnP/RANSAC) tar ett avtryck av märkena du ritar på väggen och snäpper tillbaka överlägget efter förlorad spårning — helt offline, utan att förhandsskanna rummet.
*   **Fickredo (experimentellt, avstängt som standard):** driftkorrigering och ett självutvidgande avtryck (så att "snäpp-tillbaka" kan överleva att originalreferensen målas över) finns och kan slås på från Inställningar > diagnostikoverlay, men ingen av delarna har validerats på riktig hårdvara ännu — se [Teleologisk SLAM](../TELEOLOGICAL_SLAM.md).
*   **Medveten om dubbla linser:** väljer automatiskt hårdvarubaserat stereodjup på enheter som stöder det; övriga enheter spårar via ARCores monokulära positionsbestämning utan separat djupuppskattning.
*   **Co-op-läge:** krypterad, QR-parad sessionsdelning så att en medarbetare kan se värdens levande canvas i AR. Just nu bara värd → gäst — en gästs egna ändringar synkas inte tillbaka ännu.
*   **AzNavRail-gränssnitt:** tumdriven, enhandsnavigering utformad för konstnärer med en sprayburk i handen.

## Lägen
*   **AR-väggmålning (AR Mural):** kärninstrumentet för att förankra digitala koncept i fysiska ytor, spårat via fingeravtrycksbaserad omlokalisering enligt ovan.
*   **Mockup-läge:** snabba verktyg för att visualisera lager och blandningslägen ovanpå stillbilder av väggen.
*   **Trace (ljuslåda):** helt upplyst yta för att kopiera till papper, med beröringslås, automatisk indragning av rail och avslut med volymknapparna (upp, ner, upp, ner).
*   **Overlay:** icke-AR-bildspårning — din referensbild läggs över den levande kamerabilden (CameraX) med justerbar opacitet. På det fåtal enheter som saknar ARCore kan detta läge i stället spåra en form du markerar på väggen med samma OpenCV-pipeline, endast planärt.
*   **Design:** placerings- och läsbarhetsverktyg för den enda designbilden som spåras — opacitet/ljusstyrka/kontrast/mättnad/färgbalans, invertering, konturextraktion och motivisolering. Det finns exakt en designbild; att sätta samman flera bilder till en är kompanjonappens jobb för design, inte den här appens.

## Licensiering
GraffitiXR är **källkodstillgänglig (source-available), inte öppen källkod.** Appen, `core:*`-modulerna samt AR-/SLAM-/teleologimotorn är licensierade under **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](../../LICENSE)); den deklarerade API-ytan för tillägg och tillgångsimportörer är **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). **Den kompilerade appen är fri att använda för vem som helst, inklusive betalda uppdrag** — det icke-kommersiella villkoret gäller återanvändning av *källkoden*, inte gatukonstnärer som utför betalt arbete. Se [`docs/LICENSING.md`](../LICENSING.md) för den auktoritativa, sökväg-för-sökväg-strukturen och prioritetsordningen. Medföljande tredjepartsbibliotek (OpenCV, ML Kit, …) behåller sina egna ursprungliga licenser.

## Arkitektur
Strikt frikopplad, modulär Clean Architecture:
*   `:app` — Navigation, kameraorkestrering och Hilt-beroendeinjektion.
*   `:feature:ar` — ARCore-sessionshantering, `ArRenderer` och SLAM-databearbetning.
*   `:feature:editor` — Hantering och justering av den enskilda designbilden.
*   `:feature:dashboard` — Projektbibliotek, introduktion och inställningar.
*   `:core:nativebridge` — Inbyggd C++-motor (`MobileGS`), JNI-brygga och omlokaliseringstrådar. OpenCV i sig är ett Maven Central-beroende (`org.opencv:opencv`), ingen inbäddad modul.
*   `:android_collaboration_module` — peer-to-peer-nätverk för Co-op-läge (värd → gäst; se Nyckelfunktioner ovan).
*   `:core:data` / `:core:domain` / `:core:common` — Enhetligt datalager och abstraktion för wearables.
*   `:core:design` — Delat Compose-designsystem (återanvändbara kontroller och overlay).

## Dokumentation
- [Arkitekturöversikt](../ARCHITECTURE.md)
- [Native Engine Details](../NATIVE_ENGINE.md)
- [SLAM-konfiguration och omlokalisering](../SLAM_SETUP.md)
- [Teleologisk SLAM](../TELEOLOGICAL_SLAM.md)
- [Prestandaguide](../performance.md)
- [Teststrategi](../testing.md)
- [Dataformat](../data_formats.md)
- [Att bidra](../contributing.md)
- [Release och leverans till Google Play](../RELEASE.md)
- [Skärm- och lägesreferens](../en/screens.md)

---
*Dokumentationen uppdaterades 2026-09-04: anpassad till det korrigerade rot-README.md — den felaktiga beskrivningen av motorn "Persistent Voxel Memory" ersatt med den verkliga fingeravtrycksbaserade omlokaliseraren (OpenCV, ORB/SuperPoint + PnP/RANSAC i C++17); tagit bort påståendet om en enda GL-renderväg med `slamManager.draw()` och voxlar, som inte existerar; tagit bort "faller tillbaka till optiskt flöde" ur beskrivningen av stöd för dubbla linser; redigeraren beskrivs nu som att den arbetar med en enda bild (att sätta samman flera är kompanjonappens jobb); avsnitten "Lägen", "Licensiering" och en uppdaterad "Arkitektur" har lagts till; trasiga och felaktiga länkar i dokumentationsavsnittet har rättats (den obefintliga docs/PIPELINE_3D.md togs bort, docs/screens.md pekar nu på rätt sökväg, och länkarna skrivs relativt med ../). Tidigare uppdatering: 2026-03-17.*
