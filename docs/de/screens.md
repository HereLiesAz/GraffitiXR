# Anwendungsbildschirme

## 1. Der AR-Viewport (Hauptbildschirm)

Es gibt praktisch nur einen Bildschirm. Die Hintergrund-Rendering-Ebene ändert sich je nach aktivem Modus.

### AR-Modus (`EditorMode.AR`)
| Ebene | Oberfläche | Inhalt |
|---|---|---|
| Unten | `GLSurfaceView` (`ArRenderer`) | ARCore Live-Kamera-Feed über `BackgroundRenderer`; SLAM Voxel Splats über `slamManager.draw()` |
| Oben | Compose `Canvas` | 2D-Editorebenen (Bitmaps, Transformationen) |
| HUD | Compose `Text` chip | Live-Tracking-Status (grün=VERFOLGUNG, grau=SUCHEN) basierend auf `arUiState.isScanning` |

ARCore kontrolliert in diesem Modus die Kamera. CameraX Preview ist **nicht** aktiv.

### Overlay-Modus (`EditorMode.OVERLAY`)
| Ebene | Oberfläche | Inhalt |
|---|---|---|
| Unten | `PreviewView` (CameraX) | Live-Kamera über CameraX Preview |
| Oben | Compose `Canvas` | 2D-Editorebenen |

Die ARCore-Sitzung ist angehalten. CameraX kontrolliert die Kamera.

### Mockup-Modus (`EditorMode.MOCKUP`)
Keine Kamera. Der Hintergrund ist ein vom Benutzer ausgewähltes statisches Bild (`backgroundBitmap`). Compose `Canvas` rendert die Ebenen darüber.

### Trace-Modus (Pause) (`EditorMode.TRACE`)
Keine Kamera. Vollbild-Ebenenanzeige mit gesperrter Touch-Eingabe. Die Entsperrungsgeste aktiviert die Touch-Eingabe wieder.

### Stencil-Modus (Schablone) (`EditorMode.STENCIL`)
Keine Kamera. Generierung mehrschichtiger Schablonen und Druck-Pipeline. Bietet eine Vorschau der Ebenen und exportiert gekachelte PDFs.

---

## 2. Editor-Modi (Leistenelemente)

Die "Bildschirme" sind logische Zustände, die über die `AzNavRail` navigiert werden:

| Modus | Zweck |
|---|---|
| AR | Live-Scan + Projektion des Bildes auf eine reale Oberfläche |
| Overlay | Projektion des Bildes über die Live-Kamera (kein SLAM) |
| Mockup | Komposition auf einem statischen Referenzfoto |
| Trace | Leuchtkasten — Bild mit voller Helligkeit für physisches Abpausen |
| Stencil | Generieren und Drucken mehrschichtiger Schablonen |

---

## 3. Sekundäre Bildschirme

### Projektbibliothek
Vollbild-Bottom-Sheet über dem Haupt-Viewport. Listet gespeicherte `.gxr`-Projekte auf; unterstützt Laden, Löschen und neues Projekt.

### Einstellungen (Flyout)
*   Händigkeit (Andocken der Leiste links/rechts).
*   Versionsinfo.
*   Update-Prüfung.

---

## 4. Berechtigungsablauf

Kamera- und Standortberechtigungen werden zusammen über den `permissionLauncher` in der `MainActivity` angefordert. Der Status `hasCameraPermission` steuert das gesamte kameraabhängige Rendering im `ArViewport`. Ohne Kameraberechtigung zeigen sowohl der AR- als auch der Overlay-Modus keinen Hintergrund.

---
*Dokumentation aktualisiert am 2026-03-17 während der Website-Neugestaltungs- und Schablonenmodus-Integrationsphase.*