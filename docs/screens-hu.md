# Alkalmazás Képernyők

## 1. Az AR Nézet (Főképernyő)

Gyakorlatilag egyetlen képernyő létezik. A háttér-renderelési réteg az aktív módtól függően változik.

### AR Mód (`EditorMode.AR`)
| Réteg | Felület | Tartalom |
|---|---|---|
| Alsó | `GLSurfaceView` (`ArRenderer`) | ARCore élő kamerakép a `BackgroundRenderer`-en keresztül; SLAM voxel splattek a `slamManager.draw()`-on keresztül |
| Felső | Compose `Canvas` | 2D szerkesztőrétegek (bittérképek, transzformációk) |
| HUD | Compose `Text` chip | Élő követési állapot (zöld=KÖVETÉS, szürke=KERESÉS) az `arUiState.isScanning` alapján |

Ebben a módban az ARCore birtokolja a kamerát. A CameraX Előnézet **nem** aktív.

### Rétegzés Mód (`EditorMode.OVERLAY`)
| Réteg | Felület | Tartalom |
|---|---|---|
| Alsó | `PreviewView` (CameraX) | Élő kamera a CameraX Előnézeten keresztül |
| Felső | Compose `Canvas` | 2D szerkesztőrétegek |

Az ARCore munkamenet szüneteltetve van. A CameraX birtokolja a kamerát.

### Makett Mód (`EditorMode.MOCKUP`)
Nincs kamera. A háttér egy felhasználó által kiválasztott statikus kép (`backgroundBitmap`). A Compose `Canvas` jeleníti meg a rétegeket ezen felül.

### Rajzolás Mód (`EditorMode.TRACE`)
Nincs kamera. Teljes képernyős rétegmegjelenítés lezárt érintésbemenettel. A feloldó gesztus újra engedélyezi az érintést.

### Sablon Mód (`EditorMode.STENCIL`)
Nincs kamera. Többrétegű sablongenerálási és nyomtatási folyamat. Előnézetet ad a rétegekről és exportálja a csempézett PDF-eket.

---

## 2. Szerkesztő Módok (Sín Elemek)

A "képernyők" logikai állapotok, amelyeken az `AzNavRail` segítségével lehet navigálni:

| Mód | Cél |
|---|---|
| AR | Élő szkennelés + kép vetítése valós felületre |
| Rétegzés | Kép vetítése az élő kamera fölé (nincs SLAM) |
| Makett | Kompozíció készítése egy statikus referenciafotón |
| Rajzolás | Világítótábla – a kép maximális fényerőn jelenik meg a fizikai átrajzoláshoz |
| Sablon | Többrétegű sablonok generálása és nyomtatása |

---

## 3. Másodlagos Képernyők

### Projektkönyvtár
Teljes képernyős alsó lap (bottom sheet) a fő nézet felett. Felsorolja a mentett `.gxr` projekteket; támogatja a betöltést, a törlést és az új projekt létrehozását.

### Beállítások (Kinyíló menü)
*   Kezesség (bal/jobb sín dokkolás).
*   Verzióinformáció.
*   Frissítések keresése.

---

## 4. Engedélyezési Folyamat

A kamera- és helymeghatározási engedélyek együtt kerülnek bekérésre a `MainActivity`-ben található `permissionLauncher` segítségével. A `hasCameraPermission` állapot szabályozza az összes kamerától függő renderelést az `ArViewport`-ban. Kamera engedély nélkül sem az AR, sem a Rétegzés mód nem mutat hátteret.

---
*A dokumentációt 2026-03-17-én frissítették a weboldal újratervezése és a Sablon Mód integrációs szakasza során.*
