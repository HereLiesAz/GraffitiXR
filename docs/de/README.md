# GraffitiXR

GraffitiXR ist eine Android-App für Streetart-Künstler. Es gibt viele Apps, die ein Bild über die Kameraansicht legen, damit man es virtuell nachzeichnen kann. Aber wenn ich ein Wandbild male, das auf einer Skizze basiert, die ich auf meinem Handy gespeichert habe, kann die Verwendung eines Stativs den Arbeitsfluss wirklich stören. Wir sind überall unterwegs. Ich stecke mein Handy in die Tasche. Selbst die Apps, die AR verwenden, um das Bild ruhig und an einem Ort zu halten, kommen mit der abgrundtiefen Dunkelheit der Hosentasche nicht zurecht.

Also mache ich etwas Besseres, indem ich (was Kenner nennen) die Rastermethode umfunktioniere. Ich dachte immer: "Warum können diese speziellen Kritzeleien nicht als dauerhafter Anker gespeichert werden, damit das Overlay immer genau an der richtigen Stelle ist?"

Und genau das tun diese Kritzeleien jetzt.

Ich musste eine spezielle Gaussian Splatting-Engine erfinden, die auf Android ohne Cloud-Unterstützung funktioniert – denn Graffiti ist ja bekanntlich illegal.

Und ich habe es mit dem ergänzt, was ich einen "Teleologischen Slam" nenne: Da wir wissen, wie das Ergebnis aussehen soll, verwende ich OpenCV, um nach Ihrem Fortschritt zu suchen. Das bedeutet, je weiter Sie sind, desto fester haftet das Overlay an der Wand. Ohne dies würden Sie die Markierungen mit der Malerei selbst überdecken, wodurch die App ungenauer wird. Das ist genau der Punkt, an dem andere Apps dieser Art wirklich scheitern.

Nur zum Spaß habe ich die Nicht-AR-Bildüberlagerungsfunktion zum Abpausen von Bildern integriert, genau wie bei den anderen Apps, falls Sie so arbeiten. Oder wenn Sie völlig verrückt sind, gibt es den Mockup-Modus. Machen Sie ein Foto von der Wand, dann habe ich ein paar schnelle Werkzeuge für ein schnelles Mockup. Und wenn Sie nichts beweisen müssen und einfach nur etwas perfekt auf Papier kopieren wollen, ermöglicht der Leuchtkasten-Modus, Ihr Telefon als Leuchtkasten zu verwenden. Dabei bleibt der Bildschirm bei hoher Helligkeit eingeschaltet, Ihr Bild wird fixiert und alle Berührungen werden blockiert, bis Sie fertig sind.

Außerdem gibt es eine ordentliche Suite an relevanten Designwerkzeugen, mit Unterstützung für die grafische Erstellung über mehrere Ebenen. Ich könnte noch weiter machen, aber ich glaube, das reicht erst einmal.

**GraffitiXR** ist eine Offline-Android-App für Streetart-Künstler. Sie nutzt AR, um Bilder mithilfe eines konfidenzbasierten Voxel-Mapping-Systems auf Wände zu projizieren.

## Hauptmerkmale
*   **Offline-First:** Keine Cloud-Abhängigkeiten; es werden keine Daten gesammelt.
*   **Custom Engine (MobileGS):** Native C++17-Engine für 3D Gaussian Splatting und Spatial Mapping.
*   **Vollständige ARCore-Pipeline:** Live-Kamera-Feed über `BackgroundRenderer`, Color-Frame-Relokalisierung und ARCore Depth API — alles füttert die SLAM-Engine mit echten Daten.
*   **AzNavRail UI:** Daumengesteuerte Navigation für die einhändige Bedienung vor Ort.
*   **Single GL Render Path:** `ArRenderer` verarbeitet sowohl den Kamerahintergrund (`BackgroundRenderer`) als auch die SLAM-Voxel-Splats (`slamManager.draw()`) in einer einzigen `GLSurfaceView`.
*   **Multi-Lens-Unterstützung:** Verwendet auf unterstützten Geräten automatisch Dual-Kamera-Stereo-Tiefe; fällt auf optischen Fluss zurück.
*   **Teleologische Korrektur:** Automatische Ausrichtung der Karte an der Welt mithilfe von OpenCV-Fingerprinting.

## Architektur
Streng entkoppelte Multi-Modul-Architektur:
*   `:app` — Navigation, `ArViewport` Composable, Steuerung der Kamera-Verantwortlichkeit.
*   `:feature:ar` — ARCore-Sitzung, `ArRenderer`, `BackgroundRenderer`, Sensorfusion, Einspeisung von SLAM-Daten.
*   `:feature:editor` — Bildbearbeitung, Ebenenverwaltung.
*   `:feature:dashboard` — Projektbibliothek, Einstellungen.
*   `:core:nativebridge` — `SlamManager` JNI-Bridge, `MobileGS` Voxel-Engine, OpenGL ES Rendering.
*   `:core:data` / `:core:domain` / `:core:common` — Clean Architecture Datenschicht.

## Dokumentation
- [Architekturübersicht](docs/ARCHITECTURE.md)
- [Details zur nativen Engine](docs/NATIVE_ENGINE.md)
- [SLAM Konfiguration & Tuning](docs/SLAM_SETUP.md)
- [3D-Pipeline-Spezifikation](docs/PIPELINE_3D.md)
- [Teststrategie](docs/testing.md)
- [Bildschirm- & Modus-Referenz](docs/screens.md)

---
*Dokumentation aktualisiert am 2026-03-17 während der Website-Neugestaltungs- und Schablonenmodus-Integrationsphase.*