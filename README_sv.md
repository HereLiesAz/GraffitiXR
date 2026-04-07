# GraffitiXR

GraffitiXR är en Android-app för gatukonstnärer. Det finns massor av appar som överlagrar en bild på din kameravy så att du virtuellt kan spåra den, men när jag målar en väggmålning baserat på en skiss som jag har sparat på min telefon, kan användningen av ett stativ verkligen ebba ut. Vi är överallt. Jag, jag stoppade min telefon i fickan. Inte ens apparna som använder AR för att hålla bilden stadig och på ett ställe klarar av fickans avgrundsmörker.
Så jag gör något bättre genom att återanvända (vad de som är insatta kallar) rutnätsmetoden. Jag tänkte alltid, "Varför kan dessa specifika doodles inte sparas, som ett ihållande ankare, så att överlägget alltid är helt enkelt på rätt plats?"
Så nu, det är vad de där doodlesna gör.
Jag var tvungen att uppfinna en anpassad Gaussian Splatting-motor som fungerar på Android utan hjälp av molnet - för graffiti är, du vet, olagligt.
Och jag följde upp det med vad jag kallar en Teleological Slam--eftersom vi vet hur resultatet ska se ut, använder jag OpenCV för att leta efter dina framsteg, vilket innebär att ju längre fram du är, desto tätare fäster överlägget på väggen. Utan detta skulle du täcka dessa märken med själva målningen, vilket gör appen mindre exakt när du går. Det är precis där andra appar som denna verkligen misslyckas.
Bara för skjortor och skyddsglasögon inkluderade jag icke-AR, bildöverlagringsfunktion för bildspårning, precis som du får med de andra apparna, om du skulle gråta så. Eller om du gråter, det finns Mockup-läge. Ta en bild på väggen, så fick jag några snabba verktyg för en snabb mockup. Och om du inte har något att bevisa vill du bara att något kopieras på papper perfekt, spårningsläget låter dig använda din telefon som en ljuslåda, hålla skärmen på med ljusstyrkan upphöjd, låsa bilden på plats och blockera alla beröringar tills du är klar.
Och så finns det en anständig svit av relevanta designverktyg, med stöd för flerlagers grafisk skapande. Jag skulle kunna fortsätta, men jag känner att jag redan har gjort det.
**GraffitiXR** är en offline Android-app för gatuartister. Den använder AR för att projicera bilder på väggar med ett konfidensbaserat voxel-mappningssystem.
## Nyckelfunktioner
* **Offline-First:** Inga molnberoenden; noll data insamlad.
* **Custom Engine (MobileGS):** C++17 inbyggd motor för 3D Gaussian Slatting och rumslig kartläggning.
* **Fullständig ARCore Pipeline:** Live kameraflöde via `BackgroundRenderer`, färgramsförflyttning och ARCore Depth API – allt matar verklig data till SLAM-motorn.
* **AzNavRail UI:** Tumdriven navigering för enhandsbruk i fält.
* **Single GL Render Path:** `ArRenderer` hanterar både kamerabakgrund (`BackgroundRenderer`) och SLAM voxel splats (`slamManager.draw()`) i en enda `GLSurfaceView`.
* **Stöd för flera linser:** Använder automatiskt stereodjup med dubbla kameror på enheter som stöds; faller tillbaka till optiskt flöde.
* **Teleologisk korrigering:** Automatisk karta-till-världsjustering med OpenCV-fingeravtryck.
## Arkitektur
Strikt frikopplad multimodularkitektur:
* `:app` — Navigation, 'ArViewport' komponerbar, orkestrering av kameraägande.
* `:feature:ar` — ARCore-session, `ArRenderer`, `BackgroundRenderer`, sensorfusion, SLAM-datamatning.
* `:feature:editor` — Bildmanipulation, lagerhantering.
* `:feature:dashboard` — Projektbibliotek, inställningar.
* `:core:nativebridge` — `SlamManager` JNI-brygga, `MobileGS` voxelmotor, OpenGL ES-rendering.
* `:core:data` / `:core:domain` / `:core:common` — Datalager för ren arkitektur.
## Dokumentation
- [Arkitekturöversikt](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM-konfiguration och inställning](docs/SLAM_SETUP.md)
- [3D Pipeline Specification](docs/PIPELINE_3D.md)
- [Teststrategi](docs/testing.md)
- [Skärm- och lägesreferens](docs/screens.md)


---
*Dokumentationen uppdaterades 2026-03-17 under webbsidans omdesign och integrationsfasen för stencilläge.*