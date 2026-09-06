# GraffitiXR

GraffitiXR es una aplicación Android para artistas callejeros. Existen muchas aplicaciones que superponen una imagen a la vista de la cámara para calcarla virtualmente, pero cuando pinto un mural a partir de un boceto guardado en mi teléfono, usar un trípode rompe bastante el ritmo de trabajo. Estamos todo el tiempo moviéndonos. Yo me guardo el teléfono en el bolsillo. Incluso las aplicaciones que usan RA para mantener la imagen fija y en su sitio no logran lidiar con la oscuridad absoluta del bolsillo.

Así que estoy haciendo algo mejor reutilizando (lo que los entendidos llaman) el método de la cuadrícula. Siempre pensé: "¿Por qué esos garabatos concretos no pueden guardarse, como un ancla persistente, para que la superposición quede siempre justo en el sitio correcto?"

Y eso es justo lo que hacen ahora esos garabatos.

Tuve que inventar un **relocalizador por huella digital** hecho a medida que funciona en Android sin ayuda de la nube — porque el grafiti, como todo el mundo sabe, es ilegal. Cuando te ancla a una pared, la app captura una **huella digital** de tus marcas mediante descriptores de OpenCV — descriptores más un puñado de puntos 3D triangulados. Incluso tras perder el seguimiento o apagar la pantalla, el motor compara la cámara en vivo con esa huella y resuelve la pose (PnP) para **volver a encajar** y realinear tu mural con la pared en milisegundos — sin nube y sin escanear antes toda la habitación.

Y lo complementé con lo que llamo un **SLAM teleológico** — como sabemos cómo se supone que debe verse el resultado, uso OpenCV para observar tu progreso, de modo que cuanto más avanzado estés, con más firmeza se pega la superposición a la pared. Sin esto, acabarías tapando esas marcas con la propia pintura, haciendo que la app pierda precisión a medida que avanzas. Ahí es exactamente donde otras aplicaciones de este tipo fallan de verdad.

Estas dos funciones — el reencaje automático y la huella autoextensible — de momento solo están disponibles como interruptor opcional (Ajustes > superposición de diagnóstico, desactivado por defecto), mientras las valido con hardware real. Así que no esperes todavía que ninguna de las dos venga activada de fábrica.

Solo por diversión, incluí la función de superposición de imagen sin RA para calcar, igual que en esas otras apps, por si te va ese rollo. O si vas más en serio, está el **modo Maqueta**. Haz una foto de la pared y tendrás algunas herramientas rápidas para una maqueta express. Y si no tienes nada que demostrar y solo quieres copiar algo a la perfección en papel, el **modo Trace** convierte tu teléfono en una mesa de luz: mantiene la pantalla encendida a máximo brillo, fija tu imagen en su sitio y bloquea todos los toques hasta que termines.

Y luego hay un buen conjunto de herramientas de diseño pertinentes para preparar la única imagen que estás colocando — tono, balance de color, extracción de contornos, aislamiento de sujeto. Componer varias imágenes en una sola es tarea de la app de diseño complementaria, no de esta. Podría seguir, pero creo que ya me he explayado bastante.

## Características principales
*   **Sin conexión (offline-first):** Sin dependencias de la nube para nada de lo que hace la app — el seguimiento, el renderizado y el trabajo de diseño son todos locales. Lo único que sale alguna vez del dispositivo es un informe de fallos, y eso es opcional y está desactivado por defecto (Ajustes > Informes de fallos); consulta [`docs/en/PRIVACY_POLICY.md`](../en/PRIVACY_POLICY.md).
*   **Relocalización por huella digital:** un pipeline nativo de OpenCV en C++17 (descriptores ORB/SuperPoint + PnP/RANSAC) captura una huella digital de las marcas que dibujas en la pared y reencaja la superposición tras perder el seguimiento — totalmente sin conexión, sin escaneo previo de la habitación.
*   **Listo para el bolsillo (experimental, desactivado por defecto):** la corrección de deriva y una huella autoextensible (para que el reencaje sobreviva aunque se pinte encima de la referencia original) existen y pueden activarse desde Ajustes > superposición de diagnóstico, pero ninguna de las dos se ha validado todavía con hardware real — consulta [SLAM Teleológico](../TELEOLOGICAL_SLAM.md).
*   **Consciente del doble objetivo:** selecciona automáticamente la profundidad estéreo por hardware en los dispositivos que la ofrecen; el resto de dispositivos hace seguimiento mediante la pose monocular de ARCore, sin una estimación de profundidad independiente.
*   **Modo Cooperativo:** compartición de sesión cifrada y emparejada por código QR, para que un colaborador pueda seguir en vivo el lienzo del anfitrión en RA. Por ahora, solo de anfitrión a invitado — las ediciones propias de un invitado todavía no se sincronizan de vuelta.
*   **Interfaz AzNavRail:** navegación con el pulgar, pensada para usarse con una sola mano por artistas que sostienen un aerosol.

## Modos
*   **Mural AR:** El instrumento de precisión principal para anclar conceptos digitales a superficies físicas, con seguimiento mediante el relocalizador por huella digital descrito arriba.
*   **Modo Maqueta:** Herramientas rápidas para visualizar capas y modos de fusión sobre fotos estáticas de paredes.
*   **Trace (Mesa de luz):** Superficie a brillo máximo para calcar sobre papel, con bloqueo táctil, retracción automática del riel de navegación y salida mediante los botones físicos de volumen (Subir, Bajar, Subir, Bajar).
*   **Superposición:** Calcado de imagen sin RA — tu imagen de referencia se superpone a la cámara en vivo (CameraX) con opacidad ajustable. En el pequeño número de dispositivos sin ARCore, este modo puede en su lugar seguir una forma que marques en la pared usando el mismo pipeline de OpenCV, solo en modo plano.
*   **Diseño:** Herramientas de colocación y legibilidad para la única imagen de diseño que se está calcando — opacidad/brillo/contraste/saturación/balance de color, inversión, extracción de contornos y aislamiento de sujeto. Hay exactamente una imagen de diseño; componer varias imágenes en una sola es tarea de la app de diseño complementaria, no de esta app.

## Licencia
GraffitiXR es de **código fuente disponible, no de código abierto (source-available, not open source).** La app, los módulos `core:*` y el motor de RA / SLAM / teleológico están licenciados bajo **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](../../LICENSE)); la superficie de API de extensión declarada y los importadores de recursos son **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). La **app compilada es de uso libre para cualquiera, incluidos los encargos remunerados** — la cláusula no comercial afecta a la reutilización del *código fuente*, no a los muralistas que hacen trabajos pagados. Consulta [`docs/LICENSING.md`](../LICENSING.md) para el desglose oficial, ruta por ruta. Las bibliotecas de terceros incluidas (OpenCV, ML Kit, …) conservan sus propias licencias originales.

## Arquitectura
Arquitectura Clean multi-módulo estrictamente desacoplada:
*   `:app` — Navegación, orquestación de la cámara e inyección de dependencias con Hilt.
*   `:feature:ar` — Gestión de la sesión de ARCore, `ArRenderer` y procesamiento de datos SLAM.
*   `:feature:editor` — Manipulación y ajustes de la única imagen de diseño.
*   `:feature:dashboard` — Biblioteca de proyectos, incorporación (onboarding) y ajustes.
*   `:core:nativebridge` — Motor nativo en C++ (`MobileGS`), puente JNI e hilos de relocalización. OpenCV en sí es una dependencia de Maven Central (`org.opencv:opencv`), no un módulo integrado en el repositorio.
*   `:android_collaboration_module` — Red punto a punto para el Modo Cooperativo (anfitrión → invitado; ver Características principales arriba).
*   `:core:data` / `:core:domain` / `:core:common` — Capa de datos unificada y abstracción para wearables.
*   `:core:design` — Sistema de diseño Compose compartido (controles y superposiciones reutilizables).

## Documentación
- [Resumen de arquitectura](../ARCHITECTURE.md)
- [Detalles del motor nativo](../NATIVE_ENGINE.md)
- [Configuración y relocalización SLAM](../SLAM_SETUP.md)
- [SLAM Teleológico](../TELEOLOGICAL_SLAM.md)
- [Guía de rendimiento](../performance.md)
- [Estrategia de pruebas](../testing.md)
- [Formatos de datos](../data_formats.md)
- [Cómo contribuir](../contributing.md)
- [Publicación y entrega en Google Play](../RELEASE.md)
- [Referencia de pantallas (en inglés)](../en/screens.md)

---
*Documentación actualizada el 2026-09-04: esta página era una traducción muy desactualizada y con funciones inventadas o eliminadas — reescrita íntegramente a partir del README raíz corregido. Se eliminaron las referencias a "Persistent Voxel Memory", a la generación de plantillas/stencils multicapa, a la herramienta de licuar (liquify) y al soporte completo de capas — ninguna existe en el código actual; la app trabaja con una única imagen de diseño, y componer varias imágenes es tarea de la app de diseño complementaria. Se corrigió la descripción de `:core:data` (usa DataStore, no una base de datos Room) y se sustituyeron los enlaces con marcador de posición (`github.com/tu-repo/...`) por la URL real del repositorio, `github.com/HereLiesAz/GraffitiXR`. Se añadieron las secciones de Relocalización por huella digital, Modo Cooperativo, modo Trace y Licencia. Se corrigieron las rutas de los enlaces de documentación para que resuelvan correctamente desde `docs/es/`.*
