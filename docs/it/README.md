# GraffitiXR

GraffitiXR è un'app Android per street artist. Ci sono molte app che sovrappongono un'immagine alla vista della fotocamera per poterne virtualmente ricalcare i contorni, ma quando sto dipingendo un murale basandomi su uno schizzo che ho salvato sul telefono, usare un treppiede può davvero interrompere il flusso di lavoro. Siamo sempre in movimento. Io, ad esempio, mi metto il telefono in tasca. Anche le app che usano l'AR per mantenere l'immagine ferma e nello stesso posto non riescono a gestire l'oscurità abissale della tasca.

Così sto creando qualcosa di migliore riadattando (quello che gli esperti chiamano) il metodo della griglia. Pensavo sempre: "Perché questi specifici scarabocchi non possono essere salvati, come un'ancora persistente, in modo che la sovrapposizione sia sempre esattamente nel posto giusto?"

Quindi, ora, questo è quello che fanno quegli scarabocchi.

Ho dovuto inventare un **rilocalizzatore basato su impronta digitale** (fingerprinted relocalizer) su misura, che funziona su Android senza l'aiuto del cloud — perché i graffiti, si sa, sono illegali. Quando blocchi il tracciamento su un muro, l'app cattura un'**impronta digitale** delle tue marcature tramite descrittori OpenCV — descrittori più una manciata di punti 3D triangolati. Anche dopo una perdita di tracciamento o lo spegnimento dello schermo, il motore confronta la fotocamera in tempo reale con quell'impronta e risolve la posa (PnP) per **riagganciarsi** e riallineare il murale al muro in millisecondi — senza cloud e senza pre-scansione dell'intera stanza.

E l'ho fatto seguire da quello che chiamo uno **SLAM Teleologico** — poiché sappiamo come dovrebbe apparire il risultato, uso OpenCV per cercare i tuoi progressi, il che significa che più vai avanti, più strettamente la sovrapposizione si attacca al muro. Senza di questo, copriresti quei segni con il dipinto stesso, rendendo l'app meno accurata man mano che procedi. È esattamente in questo punto che altre app simili falliscono veramente.

Entrambe queste funzioni — il riaggancio automatico e l'impronta autoestensibile — al momento sono disponibili solo come interruttore opzionale (Impostazioni > overlay diagnostico, disattivato di default), mentre le convalido su hardware reale. Quindi non aspettarti ancora nessuna delle due attiva di serie.

Solo per divertimento, ho incluso la funzionalità di sovrapposizione dell'immagine non AR per il ricalco delle immagini, proprio come si ha con quelle altre app, nel caso tu sia fatto così. O se sei più serio, c'è la **modalità Mockup**. Scatta una foto del muro, poi ho alcuni strumenti veloci per un rapido mockup. E se non hai nulla da dimostrare, vuoi solo qualcosa copiato perfettamente su carta, la **modalità Ricalco** ti consente di usare il tuo telefono come un tavolo luminoso, mantenendo lo schermo acceso con la luminosità alzata, bloccando la tua immagine in posizione e bloccando tutti i tocchi finché non hai finito.

E poi c'è una discreta suite di strumenti di progettazione pertinenti per preparare l'unica immagine che stai posizionando — tono, bilanciamento colore, estrazione dei contorni, isolamento del soggetto. Comporre più immagini in una sola è compito dell'app di design complementare, non di questa. Potrei continuare, ma mi sembra di averlo già fatto.

## Funzionalità Chiave
*   **Offline-First:** Nessuna dipendenza dal cloud per qualsiasi cosa faccia l'app — tracciamento, rendering e lavoro di design sono tutti locali. L'unica cosa che può lasciare il dispositivo è un report di crash, ed è opzionale e disattivato di default (Impostazioni > Report di crash); vedi [`docs/en/PRIVACY_POLICY.md`](../en/PRIVACY_POLICY.md).
*   **Rilocalizzazione tramite Impronta Digitale:** una pipeline nativa OpenCV in C++17 (descrittori ORB/SuperPoint + PnP/RANSAC) cattura un'impronta digitale delle marcature disegnate sul muro e riaggancia la sovrapposizione dopo una perdita di tracciamento — completamente offline, senza pre-scansione della stanza.
*   **Pronto per la tasca (sperimentale, disattivato di default):** la correzione della deriva e un'impronta autoestensibile (in modo che il riaggancio sopravviva anche se il riferimento originale viene ridipinto sopra) esistono e possono essere attivate da Impostazioni > overlay diagnostico, ma nessuna delle due è ancora stata convalidata su hardware reale — vedi [SLAM Teleologico](../TELEOLOGICAL_SLAM.md).
*   **Consapevole del Doppio Obiettivo:** seleziona automaticamente la profondità stereo hardware sui dispositivi che la offrono; gli altri dispositivi eseguono il tracciamento tramite la posa monoculare di ARCore, senza una stima di profondità separata.
*   **Modalità Co-op:** condivisione di sessione cifrata e abbinata via QR, così un collaboratore può seguire in tempo reale la tela dell'host in AR. Attualmente solo host → guest — le modifiche del guest non vengono ancora sincronizzate indietro.
*   **UI AzNavRail:** navigazione guidata dal pollice, a una mano, pensata per artisti che tengono in mano una bomboletta spray.

## Modalità
*   **Murale AR:** Lo strumento di precisione principale per ancorare concetti digitali a superfici fisiche, tracciato tramite il rilocalizzatore a impronta digitale descritto sopra.
*   **Modalità Mockup:** Strumenti rapidi per visualizzare livelli e modalità di fusione sopra foto statiche del muro.
*   **Ricalco (Lightbox):** Superficie a piena luminosità per ricalcare su carta, con blocco tocco, ritrazione automatica della rail e uscita tramite i pulsanti fisici del volume (Su, Giù, Su, Giù).
*   **Overlay:** Ricalco immagine non AR — la tua immagine di riferimento sovrapposta alla fotocamera in tempo reale (CameraX) con opacità regolabile. Sul piccolo numero di dispositivi privi di ARCore, questa modalità può invece tracciare una forma marcata sul muro usando la stessa pipeline OpenCV, solo in modalità planare.
*   **Design:** Strumenti di posizionamento e leggibilità per l'unica immagine di design che si sta ricalcando — opacità/luminosità/contrasto/saturazione/bilanciamento colore, inversione, estrazione dei contorni e isolamento del soggetto. Esiste esattamente un'immagine di design; comporre più immagini in una sola è compito dell'app di design complementare, non di questa app.

## Licenza
GraffitiXR è a **sorgente disponibile, non open source (source-available, not open source).** L'app, i moduli `core:*` e il motore AR / SLAM / teleologico sono concessi in licenza sotto **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](../../LICENSE)); la superficie API di estensione dichiarata e gli importatori di asset sono **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). L'**app compilata è di uso libero per chiunque, incluse le commissioni retribuite** — la clausola non commerciale vincola il riutilizzo del *codice sorgente*, non i muralisti che svolgono lavori a pagamento. Vedi [`docs/LICENSING.md`](../LICENSING.md) per la struttura ufficiale, percorso per percorso, e le priorità. Le librerie di terze parti incluse (OpenCV, ML Kit, …) mantengono le proprie licenze originali.

## Architettura
Architettura multi-modulo Clean Architecture strettamente disaccoppiata:
*   `:app` — Navigazione, orchestrazione della fotocamera e dependency injection con Hilt.
*   `:feature:ar` — Gestione della sessione ARCore, `ArRenderer` ed elaborazione dei dati SLAM.
*   `:feature:editor` — Manipolazione e regolazioni dell'unica immagine di design.
*   `:feature:dashboard` — Libreria dei progetti, onboarding e impostazioni.
*   `:core:nativebridge` — Motore nativo C++ (`MobileGS`), bridge JNI e thread di rilocalizzazione. OpenCV stesso è una dipendenza Maven Central (`org.opencv:opencv`), non un modulo incluso nel repository.
*   `:android_collaboration_module` — rete peer-to-peer per la Modalità Co-op (host → guest; vedi Funzionalità Chiave sopra).
*   `:core:data` / `:core:domain` / `:core:common` — Livello dati unificato e astrazione per i wearable.
*   `:core:design` — Sistema di design Compose condiviso (controlli e overlay riutilizzabili).

## Documentazione
- [Panoramica Architettura](../ARCHITECTURE.md)
- [Dettagli Motore Nativo](../NATIVE_ENGINE.md)
- [Configurazione e Tuning SLAM](../SLAM_SETUP.md)
- [SLAM Teleologico](../TELEOLOGICAL_SLAM.md)
- [Guida alle Prestazioni](../performance.md)
- [Strategia di Test](../testing.md)
- [Formati Dati](../data_formats.md)
- [Contribuire](../contributing.md)
- [Release e Distribuzione Google Play](../RELEASE.md)
- [Riferimento Schermate (in inglese)](../en/screens.md)

---
*Documentazione aggiornata il 2026-09-04: questa pagina era rimasta a una versione precedente e non corretta della traduzione — riscritta integralmente a partire dal README radice corretto. Rimossi i riferimenti a "Persistent Voxel Memory", al "Singolo Percorso di Rendering GL" con `slamManager.draw()`, e al fallback a flusso ottico per il supporto multi-obiettivo — nessuno di questi esiste nel codice attuale. Sostituiti con il vero rilocalizzatore a impronta digitale, il comportamento Dual-Lens Aware reale, e aggiunte le sezioni Modalità Co-op, modalità Ricalco e Licenza. Corretto il link a `docs/PIPELINE_3D.md` (file inesistente, rimosso) e a `docs/screens.md` (percorso errato, ora `../en/screens.md`). Aggiornamento precedente: 2026-03-17, durante la fase di riprogettazione del sito web e integrazione della Modalità Stencil (la Modalità Stencil è stata da allora rimossa dall'app).*
