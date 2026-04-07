# GraffitiXR

GraffitiXR es una herramienta de realidad aumentada de nivel profesional para pintores de murales y artistas callejeros. Combina el seguimiento ARCore con características específicas para murales para ayudarte a proyectar, escalar y ejecutar obras de arte en cualquier pared física.

## Características Principales

*   **Proyección AR:** Proyecta diseños a escala 1:1 en paredes usando mapeo espacial ARCore y Gaussian Splatting para seguimiento de texturas.
*   **Modo Superposición:** Capa de cámara en vivo de la vieja escuela para encuadres y verificaciones rápidas sin seguimiento espacial complejo.
*   **Modo Maqueta:** Haz maquetas de tus diseños en fotos de paredes estáticas para previsualizar cómo se verán sin necesitar pintar una sola gota.
*   **Modo Mesa de Luz:** Convierte tu teléfono en una caja de luz para calcar diseños físicamente en papel. Bloquea la pantalla para evitar gestos accidentales.
*   **Gestión de Capas:** Soporte completo de capas (Importar imágenes, Dibujar, Texto) con modos de mezcla (Normal, Multiplicar, Trama, Superponer, etc.).
*   **Plantillas (Stencils):** Generador de plantillas multicapa que divide la obra en hojas de formato carta para impresión y corte en el mundo real.
*   **Ajustes de Imagen:** Ajusta la opacidad, el brillo, el contraste, la saturación y el balance de color (RGB) de cualquier capa. Herramienta de inversión de color y de "Boceto de Contorno" para extraer solo las líneas de un boceto.
*   **Herramientas de Dibujo:** Herramientas de pincel, borrador, difuminado, licuar, sobreexponer y subexponer.
*   **Aislamiento de Sujeto IA:** Elimina fondos de las imágenes automáticamente (Aislamiento de sujeto).

## Instalación

1.  Descarga el último APK desde la pestaña [Releases](https://github.com/tu-repo/graffitixr/releases).
2.  Instálalo en tu dispositivo Android (Requiere soporte de ARCore).

## Estructura del Proyecto

La aplicación utiliza la arquitectura recomendada por Android (Arquitectura limpia, Patrón MVI) y Jetpack Compose.

*   **`:app`**: Módulo principal de la aplicación. Configura la inyección de dependencias (Hilt) y el `AzHostActivityLayout`.
*   **`:core`**: Contiene módulos comunes.
    *   `:core:common`: Modelos compartidos, extensiones y lógica de UI (MVI, UI State).
    *   `:core:data`: Manejo de datos, repositorios, Base de datos Room, preferencias.
    *   `:core:domain`: Casos de uso e interfaces de repositorio.
    *   `:core:design`: Componentes de diseño, tipografía (`BlackoutFontFamily`), temas.
    *   `:core:nativebridge`: Contiene el código JNI/C++ nativo para SLAM, Gaussian Splatting y procesamiento de imágenes.
*   **`:feature`**: Módulos de funcionalidad independientes.
    *   `:feature:dashboard`: La pantalla de "Biblioteca" para listar y gestionar proyectos.
    *   `:feature:editor`: Contiene la lógica del editor, `MainScreen`, el sistema de capas, los rieles de navegación y herramientas (Pinceles, Filtros, Ajustes).
    *   `:feature:ar`: Lógica específica de realidad aumentada, interacción con ARCore (`ArView`, `ArRenderer`, `ArViewModel`).
*   **`docs/`**: Documentación más detallada, especificaciones técnicas y tutoriales.

## Creado Con

*   **[Kotlin](https://kotlinlang.org/)** y **[Jetpack Compose](https://developer.android.com/jetpack/compose)**.
*   **[ARCore](https://developers.google.com/ar)** para el seguimiento espacial.
*   **[Hilt](https://dagger.dev/hilt/)** para inyección de dependencias.
*   **[AzNavRail](https://github.com/tu-repo/aznavrail)** para el sistema de menú principal y sub-paneles en pantalla completa.
*   **[OpenCV](https://opencv.org/)** para el análisis monocular y procesamiento de imágenes nativo.
*   **[OpenGL ES 3.0](https://www.khronos.org/opengles/)** para renderizado nativo en AR.
*   **[Room](https://developer.android.com/training/data-storage/room)** para base de datos.
