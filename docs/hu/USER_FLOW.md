# RENDSZERKAPCSOLATOK ÉS FELHASZNÁLÓI FOLYAMAT

## 1. AZ UNIVERZÁLIS 2D SÍK (A Tartalom Magja)
**Definíció:** A `UniversalPlane` egy Globális Egyedi Állapot (Singleton State). Ez szolgál a felhasználó műalkotásának megváltoztathatatlan "Igazságaként".
**Perzisztencia Szabály:** Az itteni változások atomiak és azonnaliak MINDEN módban.

### A. Struktúra és Adatkapcsolatok
* **A Verem:** `List<Layer>`. A sorrend a Z-index (0 a legalsó).
* **A Réteg:**
    * **`Bitmap source`**: A nyers képpontadatok.
    * **`Matrix transform`**: Tárolja a következőket: `Translation(x,y)` (Eltolás), `Scale(sx,sy)` (Méretezés), `Rotation(degrees)` (Forgatás).
        * *Megkötés:* A transzformációk mindig a réteg középpontjához viszonyítva vannak tárolva.
    * **`ColorAdjustment adjustments`**: `HSBC` értékek (0.0-1.0).
    * **`BlendMode blend`**: `PorterDuff.Mode` (pl. SCREEN, MULTIPLY).
    * **`EffectState effects`**: Logikai értékek (Booleans) az `isIsolated` (MLKit) és az `isOutlined` (OpenCV) számára.

### B. Implementációs Logika
* **AR Módban:** A `UniversalPlane` egy 3D Négyszögre (Quad) kerül renderelésre.
    * *Kapcsolat:* `Quad.matrix = AnchorPose * Plane.transform`.
* **Rétegzés Módban:** A `UniversalPlane` egy 2D Vászonra (Canvas) kerül renderelésre.
    * *Kapcsolat:* `Canvas.matrix = ScreenSpace * Plane.transform`.
* **Makett Módban:** A `UniversalPlane` a `MockupBackground` tetejére kerül renderelésre.

---

## 2. AR VILÁG PERZISZTENCIA (A Valóság Horgonya)
**Definíció:** A rendszer, amely a `UniversalPlane`-t a fizikai valósághoz rögzíti.
**Komponensek:** `MobileGS` (Motor), `SlamMap` (Térbeli Adatok), `Fingerprint` (Relokalizációs Kulcs).

### A. A Függőségi Lánc
1.  **A Motor (`MobileGS`):**
    * **Bemenet:** Kameraképkocka (YUV) + IMU Adatok.
    * **Folyamat:** Ritka Pontfelhőt (Point Cloud) + Kamera Pózt generál.
    * **Kimenet:** `ConfidenceMap` (Voxel Rács).
2.  **A Térkép (`SlamMap`):**
    * **Adat:** A `ConfidenceMap` bináris szerializációja.
    * **Kapcsolat:** Ez a fizikai fal "Mentési Fájlja".
3.  **A Kulcs (`Fingerprint`):**
    * **Adat:** Egy sor ORB Funkció Leíró (Feature Descriptor), amelyeket a munkamenet elindításához használt specifikus célképből nyertek ki.
    * **Funkció:** Amikor az alkalmazás újraindul, a `MobileGS` ezeket az ORB funkciókat keresi.
    * **Logika:** `HA (JelenlegiFunkciók egyeznek a Kulccsal) -> Relokalizáció Indítása -> HorgonyPóz Visszaállítása`.

### B. Implementációs Irányelv
* **Mentés:** Amikor a `Project.save()` meghívásra kerül, KÖTELEZŐ szerializálni mind a `SlamMap`-et (a `MobileGS.saveBytes()`-on keresztül), mind a `Fingerprint`-et (az `OpenCV.serialize()`-on keresztül).
* **Betöltés:** Az `ArView` belépésekor:
    1.  Töltsd be a `Fingerprint`-et a memóriába.
    2.  Tápláld a `Fingerprint`-et a `MobileGS`-be.
    3.  A `MobileGS` belép a `RELOCALIZATION_MODE`-ba.
    4.  Egyezés esetén a `MobileGS` igazítja a koordinátarendszert és átvált a `TRACKING_MODE`-ba.

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

### A. Sín Elem Architektúra
Minden ikon a sínen egy adott `RailRelocItem` enum állapotnak felel meg.

| Sín Csoport | Elem ID | Akció / Logika | Implementációs Link |
| :--- | :--- | :--- | :--- |
| **MÓDOK** | `AR` | **Nézetváltás:** `ArView`.<br>**Állapot:** `activeMode = AR`.<br>**Háttér:** Élő Kamera + SLAM. | `MainScreen.kt` -> `NavHost` |
| | `OVERLAY` | **Nézetváltás:** `OverlayScreen`.<br>**Állapot:** `activeMode = OVERLAY`.<br>**Háttér:** Élő Kamera (Nincs SLAM). | `MainScreen.kt` |
| | `MOCKUP` | **Nézetváltás:** `MockupScreen`.<br>**Állapot:** `activeMode = MOCKUP`.<br>**Háttér:** `MockupBackground` Bitmap. | `MainScreen.kt` |
| | `TRACE` | **Nézetváltás:** `TraceScreen`.<br>**Állapot:** `activeMode = TRACE`.<br>**Háttér:** Fehér Világítótábla. | `MainScreen.kt` |
| **RÁCS** | `CREATE` | **Kiváltó:** `TargetCreationOverlay`.<br>**Logika:** Lásd a 3. szakaszt. | `MainViewModel.onTargetCreate()` |
| | `SURVEY` | **Kiváltó:** `MappingScreen`.<br>**Logika:** Engedélyezd a `MobileGS` szkennelés vizualizálót. | `MainViewModel.onSurveyor()` |
| | `REFINE` | **Eszköz:** Maszk Ecset Váltása.<br>**Kontextus:** Csak `TargetCreation`.<br>**Logika:** `isMasking = !isMasking`. | `TargetCreationOverlay.kt` |
| **TERVEZÉS** | `ADD` | **Szándék:** `ActivityResult(PickVisualMedia)`.<br>**Logika:** Eredmény hozzáadása a `UniversalPlane`-hez. | `MainViewModel.addLayer()` |
| | `LAYERS` | **UI:** Újrarendezhető Lista Megjelenítése.<br>**Kapcsolat:** A `UniversalPlane.layers` közvetlen tükröződése. | `EditorUi.kt` |
| | `WALL` | **Kiváltó:** `ActivityResult(PickVisualMedia)`.<br>**Kontextus:** CSAK Makett Mód.<br>**Logika:** Beállítja a `MockupBackground`-ot. | `MainViewModel.setMockupWall()` |
| | `ISOLATE` | **Folyamat:** `MLKit.Segmenter`.<br>**Cél:** Aktív Réteg.<br>**Eredmény:** Alfa Maszk Alkalmazása. | `ImageUtils.removeBackground()` |
| | `OUTLINE` | **Folyamat:** `OpenCV.Canny`.<br>**Cél:** Aktív Réteg.<br>**Eredmény:** Él-detektált Bitmap. | `ImageUtils.generateOutline()` |
| **PROJEKT**| `SAVE` | **Folyamat:** Szerializáld a `UniversalPlane`-t + `SlamMap`-et -> Zip.<br>**IO:** Blokkoló Írás (Coroutine IO). | `ProjectManager.save()` |
| | `LOAD` | **Folyamat:** Kicsomagolás -> Hidratáld a `UniversalPlane`-t -> Töltsd be a `SlamMap`-et a Motorba. | `ProjectManager.load()` |

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
