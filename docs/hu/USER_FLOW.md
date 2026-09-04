# RENDSZERKAPCSOLATOK ÉS FELHASZNÁLÓI FOLYAMAT

## 1. AZ UNIVERZÁLIS 2D SÍK (A Tartalom Magja)
**Definíció:** A `UniversalPlane` egy Globális Egyedi Állapot (Singleton State). Ez szolgál a felhasználó műalkotásának megváltoztathatatlan "Igazságaként".
**Perzisztencia Szabály:** Az itteni változások atomiak és azonnaliak MINDEN módban.

### A. Struktúra és Adatkapcsolatok
* **Egyetlen tervréteg, nem verem:** az `EditorUiState.design` egyetlen nullázható `Layer`, nem `List<Layer>`. Ez a app szándékos korlátozása: egy befejezett képet pozícionálsz a falhoz, több kép egyetlen kompozícióvá szerkesztése a társ tervezőalkalmazás dolga, nem ezé. (Korábban `List<Layer>` + `activeLayerId` volt; ezt eltávolították, mert minden felhasználó a gyakorlatban egyetlen elemet tárolt benne.)
* **A Réteg (`Layer`):**
    * **`Bitmap`**: A nyers képpontadatok (futásidejű, nem szerializált).
    * **`offset` / `scale` / `rotationX,Y,Z`**: A transzformációs paraméterek (Compose `Offset` + skalár forgatás/méretezés mezők), nem egy `Matrix` objektum.
    * **Szín- és fényerő-paraméterek**: `opacity`, `brightness`, `contrast`, `saturation`, `colorBalanceR/G/B` (Float értékek).
    * **`blendMode`**: `androidx.compose.ui.graphics.BlendMode` (Compose keverési mód, pl. `Screen`, `Multiply`) — nem Android `PorterDuff.Mode`.
    * **Effekt-jelzők**: `isInverted`, `isSketch` (körvonal-kinyerés), `isSubjectIsolated` (MLKit alany-elkülönítés) — külön logikai mezők, nincs `Mesh warpMesh` és nincs GPU-gyorsított Liquify funkció; ilyen mező vagy funkció nem létezik a kódban.

### B. Implementációs Logika
* **AR Módban:** A `UniversalPlane` egy 3D Négyszögre (Quad) kerül renderelésre.
    * *Kapcsolat:* `Quad.matrix = AnchorPose * Plane.transform`.
* **Rétegzés Módban:** A `UniversalPlane` egy 2D Vászonra (Canvas) kerül renderelésre.
    * *Kapcsolat:* `Canvas.matrix = ScreenSpace * Plane.transform`.
* **Makett Módban:** A `UniversalPlane` a `MockupBackground` tetejére kerül renderelésre.

---

## 2. AR VILÁG PERZISZTENCIA (A Valóság Horgonya)
**Definíció:** A rendszer, amely a terv pozícióját a fizikai valósághoz rögzíti, még követésvesztés után is.
**Komponensek:** `MobileGS` (natív C++17 relokalizáló motor), `Fingerprint` (a fal ujjlenyomata).

**Fontos korrekció:** `MobileGS` **nem** térképező motor — nincs perzisztens voxel- vagy splat-réteg, nincs jelenetrekonstrukció, és nincs `draw()` metódus. A `MobileGS.cpp` saját kódkommentje is ezt mondja azokon a hívási pontokon, amelyek korábban egy ilyen réteget tápláltak (`setMappingPaused`, `getSplatCount` — mindkettő "no gaussian-splat mapper in this engine" üzenetet naplóz, és üres/inaktív értékkel tér vissza). Nincs `VoxelMap`, `ConfidenceMap` vagy `PersistentVoxelMemory` a jelenlegi kódban.

### A. A Tényleges Mechanizmus
1.  **A Fal Ujjlenyomata (Fingerprint):**
    * **Adat:** ORB (`cv::ORB::create(1500)`) vagy SuperPoint leírók, párosítva a felhasználó által a falra rajzolt jelek háromszögelt 3D pozícióival.
    * **Rögzítés:** A célpont megerősítésekor (`restoreWallFingerprintMetric`) egyszer jön létre.
2.  **Relokalizáció ("Snap-Back"):**
    * **Folyamat:** Egy háttérszál (`relocThreadFunc`) folyamatosan összeveti az élő kamerakép leíróit a tárolt ujjlenyomattal (Lowe-arány teszt), majd a 2D↔3D megfeleltetéseket `cv::solvePnPRansac`-kal oldja meg.
    * **Eredmény:** Követésvesztés vagy zsebre tétel után ez igazítja vissza a globális horgony-transzformációt — nincs szükség térképhez, csak az egy ujjlenyomathoz.
3.  **Opcionális, alapból KI kapcsolt kiegészítők** (csak a Beállítások > diagnosztikai overlay-ből érhetők el, nem a művész-felületről):
    * **Teleológiai korroboráció:** a tervkompozíció leíróit veti össze az élő fallal egy bizalmi jelzés előállításához.
    * **Önnövelés (self-grow):** a validált új jeleket hozzáadja az ujjlenyomathoz, hogy a snap-back túlélje az eredeti referencia lefestését (max. 5000 pont).

### B. Implementációs Irányelv
* **Mentés:** A `Project.save()` a projektfájlban a `Fingerprint`-et szerializálja (`GraffitiProject.fingerprint`), nem egy voxeltérképet.
* **Betöltés:** Az AR módba belépéskor a mentett `Fingerprint` betöltődik a `MobileGS`-be, amely megkísérli a relokalizációt a tárolt ujjlenyomat alapján.

---

## 3. CÉLPONT LÉTREHOZÁSA (A Rács Rituálé)
**Definíció:** A munkafolyamat a kezdeti `Horgony` (Koordináta 0,0,0) létrehozására.
**Kontextus:** `TargetCreationOverlay.kt`.

### A. A Munkafolyamat Logikája
1.  **Rögzítési Fázis:**
    * **Bemenet:** Kamera X folyam.
    * **Felhasználói Akció:** Koppintás a "Zár" gombra.
    * **Adat:** Rögzíti a `Bitmap tempTarget`-et.
    * **Kapcsolat:** Az `ArView` SZÜNETELTETVE van (a kamera logikája átadva a `TargetCreationOverlay`-nek).
2.  **Rektifikációs Fázis (Kiegyenesítés):**
    * **Kontextus:** `UnwarpScreen`.
    * **Felhasználói Akció:** Húzd a 4 sarkot a sík meghatározásához.
    * **Logika:** `OpenCV.getPerspectiveTransform(srcPoints, dstPoints)`.
    * **Kimenet:** `Bitmap flatTarget` (A fal rektifikált, lapos textúrája).
3.  **Funkció Kinyerési Fázis:**
    * **Folyamat:** Passzold a `flatTarget`-et az `OrbFeatureDetector`-nak.
    * **Érvényesítés:** `HA (FunkcióSzám < 50) -> Elutasítás "Túl alacsony textúra"`.
    * **Eredmény:** A `Fingerprint` létrejött.
4.  **Befecskendezési Fázis:**
    * **Akció:** `MobileGS.setAnchor(Fingerprint)`.
    * **Eredmény:** A motor mostantól ezt a képpozíciót (0,0,0)-ként kezeli a Világ Térben.

---

## 4. AZ AZNAVRAIL (Az Idegrendszer)
**Definíció:** A fő vezérlő. Kezeli az állapotátmeneteket és tájékoztatja a felhasználót a kontextusáról.
**Vizuális Szabály:** Minden Nézet (Kamera folyamok, Makett Vászon) logikailag **HÁTTÉRKÉNT** van kezelve. A Sín ezek *fölött* helyezkedik el.

### A. Sín Elem Architektúra — javított hierarchia
**Fontos korrekció:** a korábbi táblázat egy "RÁCS" és egy "TERVEZÉS" sín-csoportot írt le, valamint egy `SURVEY` elemet, amely `MobileGS` "szkennelés-vizualizátort" indítana — ilyen elem, csoport vagy vizualizátor **nem létezik** a kódban (`grep -rn "SURVEY"` nulla találatot ad). A tényleges sín három felső szintű harmonika-hosztból áll — **Modes** (AR, Overlay, Mockup, Trace), **Adjust** (Adjust, Balance, Invert, Outline, Isolate — egész-terv kapcsolók, nem általános rétegeszközök) és **Project** (new/save/load/export/settings) —, valamint két egyszerű felső szintű elemből: **Open** (réteg hozzáadása, mellékhatásként Design módba vált — nincs külön "Design" harmonika-hoszt) és **Help**.

| Sín Csoport | Elem ID | Akció / Logika |
| :--- | :--- | :--- |
| **MODES** | `AR` | Nézetváltás AR nézetre. A cél (Target) létrehozása a Modes ▸ AR alhoszt alatti almenüből érhető el, lásd a 3. szakaszt. Almenüben: Lámpa, Zár, Magic, Co-op (Host/Join/Leave). |
| | `OVERLAY` | Nézetváltás a rétegzés nézetre (élő kamera, nincs relokalizáció). Almenüben: Lámpa, Zár. |
| | `MOCKUP` | Nézetváltás a makett nézetre (statikus `backgroundBitmap` háttér). Almenüben: Fal ▸ Fotó/Fájl/Törlés, Zár. |
| | `TRACE` | Nézetváltás a világítótábla nézetre. Almenüben: Fagyasztás, Zár. |
| **ADJUST** | `Adjust` | A tervkép fényerő/kontraszt/szaturáció panelének nyitása/zárása. |
| | `Balance` | A színegyensúly-panel nyitása/zárása. |
| | `Invert` | A `Layer.isInverted` kapcsoló váltása. |
| | `Outline` | A `Layer.isSketch` (körvonal-kinyerés, OpenCV Canny) kapcsoló váltása. |
| | `Isolate` | A `Layer.isSubjectIsolated` (MLKit alany-elkülönítés) kapcsoló váltása. |
| **PROJECT** | `New` / `Save` / `Load` / `Export` / `Settings` | Új projekt, mentés, betöltés, exportálás, beállítások. A projektfájl a `Fingerprint`-et szerializálja, nem egy voxeltérképet (lásd 2. szakasz). |
| *(önálló elem)* | `Open` | Kép (vagy szövegréteg) kiválasztása; mellékhatásként átvált Design módba. Nincs "Design" harmonika-hoszt. |
| *(önálló elem)* | `Help` | Az AzNavRail beépített súgó-overlayjét nyitja meg. |

---

## 5. MAKETT MÓD KIVÉTEL (Részletesen)
**Kontextus:** Az egyetlen eltérés az Univerzális Síktól.

### A. A Logika
* **A Probléma:** A Makett módhoz egy statikus referenciaképre van szükség (egy fotó egy vonatról, egy falról stb.), amely "vászonként" működik, de nem része magának a műalkotásnak.
* **A Megoldás:** A `MockupBackground`.
* **Kapcsolat:**
    * A `UniversalPlane` a `Z-Index: 1`-en helyezkedik el.
    * A `MockupBackground` a `Z-Index: 0`-n helyezkedik el.
* **Interakciós Szabály:**
    * Amikor a `Rail.WALL` aktív: A gesztusok a `MockupBackground`-ra hatnak (Méretezés/Pásztázás a vonatfotón).
    * Amikor a `Rail.WALL` INAKTÍV: A gesztusok a `UniversalPlane`-re hatnak (Méretezés/Pásztázás a graffitin).

---

## 6. IMPLEMENTÁCIÓS IRÁNYELVEK (Hogyan csináld)

### Kamerakijelzők Azonosítása Háttérként
Annak biztosítása érdekében, hogy az `AzNavRail` és az UI rátétek megfelelően renderelődjenek a kamera folyamok felett, a `MainScreen.kt`-ben egy `Box` elrendezést kell használnod meghatározott z-rendezéssel.

```kotlin
// LOGIKAI MINTA a MainScreen.kt-hez
Box(modifier = Modifier.fillMaxSize()) {
    // 1. A HÁTTÉRRÉTEG (Nézetek)
    // Ennek KELL lennie a Box első gyermekének.
    when (viewState.activeMode) {
        AppMode.AR -> ArView(renderer = ...) // Kamera Folyam
        AppMode.OVERLAY -> OverlayScreen(camera = ...) // Kamera Folyam
        AppMode.MOCKUP -> MockupScreen(background = ...) // Statikus Kép
        AppMode.TRACE -> TraceScreen() // Fehér Háttér
    }

    // 2. AZ INTERAKCIÓS RÉTEG (Univerzális 2D Sík kezelése)
    // Ez kezeli a műalkotás gesztusait.
    if (viewState.activeMode != AppMode.TRACE) {
        GestureHandler(
            target = UniversalPlane,
            onTransform = { matrix -> MainViewModel.updatePlane(matrix) }
        )
    }

    // 3. AZ UI RÉTEG (AzNavRail)
    // Ez LEGFELÜL helyezkedik el.
    Row(modifier = Modifier.fillMaxSize()) {
        AzNavRail(
            items = viewState.railItems,
            onItemClick = { item -> MainViewModel.handleRailAction(item) }
        )

        // Szerkesztő Panelek (a sín mellett jelennek meg)
        if (viewState.isEditorOpen) {
            EditorPanel(state = viewState.editorState)
        }
    }
}
```

Sín Elemek Összekapcsolása a Logikával
A MainViewModel.kt-ben szigorú leképezést kell megvalósítanod:

```kotlin
// LOGIKAI MINTA a MainViewModel.kt-hez
fun handleRailAction(item: RailRelocItem) {
    when (item) {
        RailRelocItem.AR -> {
            // 1. Aktuális Sík állapot perzisztálása
            // 2. MobileGS inicializálása
            _uiState.update { it.copy(activeMode = AppMode.AR) }
        }
        RailRelocItem.ISOLATE -> {
            // 1. Aktív Réteg lekérése
            val layer = _uiState.value.universalPlane.activeLayer
            // 2. Coroutine indítása
            viewModelScope.launch(Dispatchers.Default) {
                val isolated = ImageUtils.removeBackground(layer.bitmap)
                // 3. Sík frissítése (Szálbiztos)
                updateLayerBitmap(layer.id, isolated)
            }
        }
        // ... kezeld az összes esetet
    }
}
```

---
*A dokumentációt 2026-03-17-én frissítették a weboldal újratervezése és a Sablon Mód integrációs szakasza során.*
