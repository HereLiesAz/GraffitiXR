# GraffitiXR

GraffitiXR è un'app Android per street artist. Ci sono molte app che sovrappongono un'immagine alla vista della fotocamera per poterne virtualmente ricalcare i contorni, ma quando sto dipingendo un murale basandomi su uno schizzo che ho salvato sul telefono, usare un treppiede può davvero interrompere il flusso di lavoro. Siamo sempre in movimento. Io, ad esempio, mi metto il telefono in tasca. Anche le app che usano l'AR per mantenere l'immagine ferma e nello stesso posto non riescono a gestire l'oscurità abissale della tasca.

Così sto creando qualcosa di migliore riadattando (quello che gli esperti chiamano) il metodo della griglia. Pensavo sempre: "Perché questi specifici scarabocchi non possono essere salvati, come un'ancora persistente, in modo che la sovrapposizione sia sempre esattamente nel posto giusto?"

Quindi, ora, questo è quello che fanno quegli scarabocchi.

Ho dovuto inventare un motore personalizzato di Gaussian Splatting che funziona su Android senza l'aiuto del cloud -- perché i graffiti sono, sapete, illegali.

E l'ho seguito con quello che chiamo uno SLAM Teleologico -- poiché sappiamo come dovrebbe apparire il risultato, uso OpenCV per cercare i tuoi progressi, il che significa che più vai avanti, più strettamente la sovrapposizione si attacca al muro. Senza di questo, copriresti quei segni con il dipinto stesso, rendendo l'app meno accurata man mano che procedi. È esattamente in questo punto che altre app simili falliscono veramente.

Solo per divertimento, ho incluso la funzionalità di sovrapposizione dell'immagine non AR per il ricalco delle immagini, proprio come si ha con quelle altre app, nel caso tu sia fatto così. O se sei pazzo da legare, c'è la modalità Mockup. Scatta una foto del muro, poi ho alcuni strumenti veloci per un rapido mockup. E se non hai nulla da dimostrare, vuoi solo qualcosa copiato perfettamente su carta, la modalità Ricalco ti consente di usare il tuo telefono come un tavolo luminoso, mantenendo lo schermo acceso con la luminosità alzata, bloccando la tua immagine in posizione e bloccando tutti i tocchi finché non hai finito.

E poi, c'è una discreta suite di strumenti di progettazione pertinenti, con supporto per la creazione grafica a più livelli. Potrei continuare, ma mi sembra di averlo già fatto.

**GraffitiXR** è un'app Android offline per artisti di strada. Utilizza l'AR per proiettare immagini sui muri utilizzando un sistema di mappatura voxel basato sulla confidenza.

## Funzionalità Chiave
*   **Offline-First:** Nessuna dipendenza dal cloud; nessun dato raccolto.
*   **Motore Personalizzato (MobileGS):** Motore nativo C++17 per 3D Gaussian Splatting e mappatura spaziale.
*   **Pipeline ARCore Completa:** Feed telecamera live tramite `BackgroundRenderer`, rilocalizzazione dei frame a colori e ARCore Depth API — tutto ciò fornisce dati reali al motore SLAM.
*   **AzNavRail UI:** Navigazione guidata dal pollice per l'uso con una sola mano sul campo.
*   **Singolo Percorso di Rendering GL:** `ArRenderer` gestisce sia lo sfondo della fotocamera (`BackgroundRenderer`) sia i voxel splat SLAM (`slamManager.draw()`) in una singola `GLSurfaceView`.
*   **Supporto Multi-Obiettivo:** Utilizza automaticamente la profondità stereo a doppia fotocamera sui dispositivi supportati; passa al flusso ottico se non supportato.
*   **Correzione Teleologica:** Allineamento automatico mappa-mondo utilizzando l'impronta digitale OpenCV.

## Architettura
Architettura multi-modulo strettamente disaccoppiata:
*   `:app` — Navigazione, composable `ArViewport`, orchestrazione della proprietà della fotocamera.
*   `:feature:ar` — Sessione ARCore, `ArRenderer`, `BackgroundRenderer`, fusione dei sensori, alimentazione dati SLAM.
*   `:feature:editor` — Manipolazione delle immagini, gestione dei livelli.
*   `:feature:dashboard` — Libreria dei progetti, impostazioni.
*   `:core:nativebridge` — Bridge JNI `SlamManager`, motore voxel `MobileGS`, rendering OpenGL ES.
*   `:core:data` / `:core:domain` / `:core:common` — Livello Dati Clean Architecture.

## Documentazione
- [Panoramica Architettura](docs/ARCHITECTURE.md)
- [Dettagli Motore Nativo](docs/NATIVE_ENGINE.md)
- [Configurazione e Tuning SLAM](docs/SLAM_SETUP.md)
- [Specifica Pipeline 3D](docs/PIPELINE_3D.md)
- [Strategia di Test](docs/testing.md)
- [Riferimento Schermate & Modalità](docs/screens.md)



---
*Documentazione aggiornata il 2026-03-17 durante la fase di riprogettazione del sito web e integrazione della Modalità Stencil.*
