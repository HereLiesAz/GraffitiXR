# GraffitiXR

GraffitiXR ist eine Android-App für Streetart-Künstler. Es gibt viele Apps, die ein Bild über die Kameraansicht legen, damit man es virtuell nachzeichnen kann. Aber wenn ich ein Wandbild male, das auf einer Skizze basiert, die ich auf meinem Handy gespeichert habe, kann die Verwendung eines Stativs den Arbeitsfluss wirklich stören. Wir sind überall unterwegs. Ich stecke mein Handy in die Tasche. Selbst die Apps, die AR verwenden, um das Bild ruhig und an einem Ort zu halten, kommen mit der abgrundtiefen Dunkelheit der Hosentasche nicht zurecht.

Also mache ich etwas Besseres, indem ich (was Kenner nennen) die Rastermethode umfunktioniere. Ich dachte immer: "Warum können diese speziellen Kritzeleien nicht als dauerhafter Anker gespeichert werden, damit das Overlay immer genau an der richtigen Stelle ist?"

Und genau das tun diese Kritzeleien jetzt.

Ich musste einen eigenen **Fingerprint-Relokalisierer** erfinden, der auf Android ohne Cloud-Unterstützung funktioniert – denn Graffiti ist ja bekanntlich illegal. Sobald Sie sich an einer Wand verankern, erfasst die App einen OpenCV-Merkmals-**Fingerabdruck** Ihrer Markierungen – Deskriptoren plus eine Handvoll triangulierter 3D-Punkte. Selbst nach Tracking-Verlust oder ausgeschaltetem Bildschirm gleicht die Engine die Live-Kamera mit diesem Fingerabdruck ab und löst die Pose (PnP), um **zurückzuspringen** und Ihr Wandbild in Millisekunden wieder exakt an der Wand auszurichten – ohne Cloud und ohne Vorab-Scan des ganzen Raums.

Und ich habe es mit dem ergänzt, was ich ein **teleologisches SLAM** nenne: Da wir wissen, wie das Ergebnis aussehen soll, verwende ich OpenCV, um nach Ihrem Fortschritt zu suchen. Das bedeutet, je weiter Sie sind, desto fester haftet das Overlay an der Wand. Ohne dies würden Sie die Markierungen mit der Malerei selbst überdecken, wodurch die App ungenauer würde. Das ist genau der Punkt, an dem andere Apps dieser Art wirklich scheitern.

Beides – das Zurückspringen (Snap-back) und der sich selbst erweiternde Fingerabdruck – läuft derzeit nur als Opt-in-Schalter (Einstellungen > Diagnose-Overlay, standardmäßig deaktiviert), während ich beides an echter Hardware validiere. Erwarten Sie also noch nicht, dass eine der beiden Funktionen von Haus aus aktiv ist.

Nur zum Spaß habe ich die Nicht-AR-Bildüberlagerungsfunktion zum Abpausen von Bildern integriert, genau wie bei den anderen Apps, falls Sie so arbeiten. Oder wenn Sie völlig verrückt sind, gibt es den **Mockup-Modus**. Machen Sie ein Foto von der Wand, dann gibt es ein paar schnelle Werkzeuge für ein schnelles Mockup. Und wenn Sie nichts beweisen müssen und einfach nur etwas perfekt auf Papier kopieren wollen, verwandelt der **Trace-Modus** Ihr Telefon in einen Leuchtkasten: Der Bildschirm bleibt bei hoher Helligkeit eingeschaltet, Ihr Bild wird fixiert, und alle Berührungen werden blockiert, bis Sie fertig sind.

Außerdem gibt es eine ordentliche Suite an relevanten Designwerkzeugen zur Vorbereitung des einen Bildes, das Sie platzieren – Ton, Farbbalance, Konturextraktion, Motiv-Freistellung. Mehrere Bilder zu einem zusammenzusetzen ist die Aufgabe der begleitenden Design-App, nicht dieser hier. Ich könnte noch weiter machen, aber ich glaube, das reicht erst einmal.

## Hauptmerkmale
*   **Offline-First:** Keine Cloud-Abhängigkeiten für irgendetwas, das die App tut – Tracking, Rendering und Designarbeit laufen alle lokal. Das Einzige, was das Gerät jemals verlässt, ist ein Absturzbericht, und der ist Opt-in und standardmäßig deaktiviert (Einstellungen > Absturzberichte); siehe [`docs/en/PRIVACY_POLICY.md`](../en/PRIVACY_POLICY.md).
*   **Fingerprint-Relokalisierung:** Eine native C++17-OpenCV-Pipeline (ORB-/SuperPoint-Deskriptoren + PnP/RANSAC) erfasst einen Fingerabdruck der Markierungen, die Sie auf die Wand zeichnen, und lässt das Overlay nach Tracking-Verlust wieder einrasten – vollständig offline, ohne Vorab-Scan des Raums.
*   **Taschentauglich (experimentell, standardmäßig deaktiviert):** Driftkorrektur und ein sich selbst erweiternder Fingerabdruck (damit das Zurückspringen auch überlebt, wenn die ursprüngliche Referenz übermalt wird) existieren und lassen sich unter Einstellungen > Diagnose-Overlay aktivieren, sind aber noch nicht an echter Hardware validiert – siehe [Teleological SLAM](../TELEOLOGICAL_SLAM.md).
*   **Dual-Lens-bewusst:** Wählt auf Geräten mit entsprechender Hardware automatisch die Stereo-Tiefe zweier Kameras; andere Geräte tracken über ARCores monokulare Pose ohne separate Tiefenschätzung.
*   **Co-op-Modus:** Verschlüsseltes, per QR-Code gekoppeltes Sitzungs-Sharing, damit ein Mitwirkender die Live-Leinwand des Hosts in AR mitverfolgen kann. Derzeit nur Host → Gast – eigene Änderungen eines Gasts werden noch nicht zurücksynchronisiert.
*   **AzNavRail-UI:** Daumengesteuerte, einhändige Navigation, entworfen für Künstler, die eine Spraydose in der Hand halten.

## Modi
*   **AR-Wandbild:** Das zentrale Präzisionswerkzeug, um digitale Entwürfe an physischen Oberflächen zu verankern, getrackt über den oben beschriebenen Fingerprint-Relokalisierer.
*   **Mockup-Modus:** Schnelle Werkzeuge zum Visualisieren von Ebenen und Mischmodi über statischen Wandfotos.
*   **Trace (Leuchtkasten):** Vollhelle Oberfläche zum Abpausen auf Papier, mit Touch-Sperre, automatischem Einklappen der Navigationsleiste und Beenden über die physischen Lautstärketasten (Auf, Ab, Auf, Ab).
*   **Overlay:** Nicht-AR-Bildüberlagerung – Ihr Referenzbild wird über die Live-Kamera (CameraX) mit einstellbarer Deckkraft gelegt. Auf den wenigen Geräten ohne ARCore kann dieser Modus stattdessen eine auf der Wand markierte Form mit derselben OpenCV-Pipeline tracken, rein planar.
*   **Design:** Platzierungs- und Lesbarkeitswerkzeuge für das eine Design-Bild, das abgepaust wird – Deckkraft/Helligkeit/Kontrast/Sättigung/Farbbalance, Invertieren, Konturextraktion und Motiv-Freistellung. Es gibt genau ein Design-Bild; mehrere Bilder zu einem zusammenzusetzen ist die Aufgabe der begleitenden Design-App, nicht dieser App.

## Lizenzierung
GraffitiXR ist **Source-Available, nicht Open Source.** Die App, die `core:*`-Module sowie die AR-/SLAM-/teleologische Engine sind unter **PolyForm Noncommercial 1.0.0** lizenziert ([`/LICENSE`](../../LICENSE)); die deklarierte Erweiterungs-API-Oberfläche und die Asset-Importer stehen unter **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). Die **kompilierte App ist für jeden kostenlos nutzbar, auch für bezahlte Aufträge** – die Noncommercial-Klausel bindet die Weiterverwendung des *Quellcodes*, nicht Muralisten bei bezahlter Arbeit. Siehe [`docs/LICENSING.md`](../LICENSING.md) für die maßgebliche, pfadgenaue Aufschlüsselung. Gebündelte Drittanbieter-Bibliotheken (OpenCV, ML Kit, …) behalten ihre eigenen Upstream-Lizenzen.

## Architektur
Streng entkoppelte Multi-Modul-Clean-Architecture:
*   `:app` — Navigation, Kamera-Orchestrierung und Hilt-Dependency-Injection.
*   `:feature:ar` — ARCore-Sitzungsverwaltung, `ArRenderer` und SLAM-Datenverarbeitung.
*   `:feature:editor` — Bearbeitung und Anpassung des einen Design-Bilds.
*   `:feature:dashboard` — Projektbibliothek, Onboarding und Einstellungen.
*   `:core:nativebridge` — Native C++-Engine (`MobileGS`), JNI-Bridge und Relokalisierungs-Threads. OpenCV selbst ist eine Maven-Central-Abhängigkeit (`org.opencv:opencv`), kein vendorisiertes Modul.
*   `:android_collaboration_module` — Peer-to-Peer-Netzwerk für den Co-op-Modus (Host → Gast; siehe Hauptmerkmale oben).
*   `:core:data` / `:core:domain` / `:core:common` — Vereinheitlichte Datenschicht und Wearable-Abstraktion.
*   `:core:design` — Gemeinsames Compose-Designsystem (wiederverwendbare Steuerelemente und Overlays).

## Dokumentation
- [Architekturübersicht](../ARCHITECTURE.md)
- [Details zur nativen Engine](../NATIVE_ENGINE.md)
- [SLAM-Einrichtung & Relokalisierung](../SLAM_SETUP.md)
- [Teleologisches SLAM](../TELEOLOGICAL_SLAM.md)
- [Performance-Leitfaden](../performance.md)
- [Teststrategie](../testing.md)
- [Datenformate](../data_formats.md)
- [Mitwirken](../contributing.md)
- [Release & Google-Play-Auslieferung](../RELEASE.md)
- [Bildschirm- & Modus-Referenz](screens.md)

---
*Dokumentation aktualisiert am 2026-09-04: an die korrigierte englische README angeglichen. Der "Persistent Voxel Memory"-Ansatz wurde nie umgesetzt und durch den tatsächlich implementierten Fingerprint-Relokalisierer (C++17/OpenCV, ORB-/SuperPoint-Deskriptoren + PnP/RANSAC) ersetzt; die Behauptungen zu "Single GL Render Path" mit `slamManager.draw()` und zum optischen-Fluss-Fallback bei Multi-Lens wurden entfernt, da es dafür keinen Code gibt. Abschnitte zu Fingerprint-Relokalisierung, Co-op-Modus, Trace-Modus und Lizenzierung ergänzt; defekte Dokumentationslinks (`docs/PIPELINE_3D.md` entfernt, `docs/screens.md` auf den tatsächlichen Pfad korrigiert, relative Pfade von `docs/de/` aus korrigiert). Vorheriges Update: 2026-03-17.*
