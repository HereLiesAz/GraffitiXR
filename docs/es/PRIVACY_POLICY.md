# Política de Privacidad de GraffitiXR

**Última actualización:** 2026-09-04

HereLiesAZ creó la app GraffitiXR como una app de código fuente disponible (source-available) (consulta el archivo LICENSE del repositorio y docs/LICENSING.md para conocer los términos). Este SERVICIO lo proporciona HereLiesAZ sin coste alguno y está destinado a usarse tal cual.

Esta página se utiliza para informar a los visitantes sobre nuestras políticas relativas a la recopilación, el uso y la divulgación de Información Personal si alguien decide utilizar nuestro Servicio.

Si eliges utilizar nuestro Servicio, aceptas la recopilación y el uso de información en relación con esta política. La Información Personal que recopilamos se utiliza para prestar y mejorar el Servicio. No utilizaremos ni compartiremos tu información con nadie, excepto según se describe en esta Política de Privacidad.

## Recopilación y uso de información

Para ofrecerte una mejor experiencia al usar nuestro Servicio, es posible que te pidamos que nos proporciones cierta información de identificación personal, incluida, entre otra, la siguiente:

*   **Datos de la cámara:** La app requiere acceso a la cámara de tu dispositivo para mostrar la vista de Realidad Aumentada (RA), el modo Superposición, y para capturar imágenes de referencia de tus proyectos. Los datos de la cámara se procesan localmente en tu dispositivo y no se transmiten a nuestros servidores, a menos que elijas explícitamente compartir un archivo de proyecto.
*   **Almacenamiento / Fotos:** Necesitamos acceso al almacenamiento de tu dispositivo (lectura/escritura de almacenamiento externo o biblioteca de fotos) para cargar imágenes de superposición, guardar tus proyectos y exportar las imágenes capturadas.
*   **Datos de ubicación (opcional):** Si concedes permisos de ubicación, la app puede recopilar datos GPS (latitud, longitud, altitud) para geoetiquetar tus proyectos. Estos datos se guardan localmente dentro de los archivos de tu proyecto.

## Informes de fallos (opt-in)

Por defecto, nada relacionado con un fallo (crash) sale de tu dispositivo. Si la app falla o se recupera de un error interno, se escribe un informe en un archivo local y temporal de tu dispositivo, para que la app pueda mostrarte un aviso de "la última sesión falló" la próxima vez que la abras — ese archivo nunca sale del dispositivo por sí solo.

Enviarnos ese informe está desactivado a menos que lo actives tú mismo, en **Ajustes > Informes de fallos**. Solo si has activado esta opción, la app sube el informe la próxima vez que se inicia, como un **issue público** en el rastreador de incidencias de GitHub de este proyecto (github.com/HereLiesAz/GraffitiXR). El informe contiene únicamente:

*   si el fallo fue fatal (la app se cerró) o recuperado (capturado, y la app siguió funcionando);
*   la fecha y hora del fallo;
*   el fabricante y modelo de tu dispositivo, y tu versión de Android;
*   el nombre de versión de la app;
*   la traza de pila (stack trace) de la excepción; y
*   hasta las últimas 1000 líneas de la salida logcat propia de la app (limitada al proceso de esta app, no registros de todo el sistema).

Debido a que el informe se archiva como un issue público de GitHub, su contenido (incluida la información del dispositivo y los registros anteriores) es visible para cualquiera que pueda ver el rastreador de incidencias de este proyecto. Activa los informes de fallos solo si te sientes cómodo con eso. Puedes desactivar el ajuste en cualquier momento, y no afectará a los fallos que ya hayan ocurrido.

## Proveedores de servicios

Podemos emplear empresas y personas de terceros por los siguientes motivos:

*   Para facilitar nuestro Servicio;
*   Para prestar el Servicio en nuestro nombre;
*   Para realizar servicios relacionados con el Servicio; o
*   Para ayudarnos a analizar cómo se utiliza nuestro Servicio.

Utilizamos **Google ML Kit** para la segmentación de sujetos (eliminación de fondo). Este procesamiento ocurre localmente en tu dispositivo.

## Comprobaciones de actualizaciones

La pantalla de Ajustes tiene un botón "Buscar actualizaciones". No se comprueba nada automáticamente — solo cuando lo pulsas. Al pulsarlo se hace una solicitud a la **API de GitHub** (api.github.com) para consultar la última versión publicada de este proyecto. GitHub es un tercero fuera de nuestro control, y tu dirección IP es visible para GitHub durante esa única solicitud, igual que en cualquier solicitud web que hagas a github.com. No se envía ninguna otra información como parte de esta solicitud.

## Seguridad

Valoramos tu confianza al proporcionarnos tu Información Personal, por lo que nos esforzamos por utilizar medios comercialmente aceptables para protegerla. Pero recuerda que ningún método de transmisión por Internet, ni de almacenamiento electrónico, es 100 % seguro y fiable, y no podemos garantizar su seguridad absoluta.

## Enlaces a otros sitios

Este Servicio puede contener enlaces a otros sitios. Si haces clic en un enlace de terceros, se te dirigirá a ese sitio. Ten en cuenta que estos sitios externos no son operados por nosotros. Por lo tanto, te recomendamos encarecidamente que revises la Política de Privacidad de esos sitios web. No tenemos control ni asumimos responsabilidad alguna por el contenido, las políticas de privacidad o las prácticas de sitios o servicios de terceros.

## Privacidad de los menores

Estos Servicios no están dirigidos a menores de 13 años. No recopilamos a sabiendas información de identificación personal de menores de 13 años. En caso de descubrir que un menor de 13 años nos ha proporcionado información personal, la eliminaremos inmediatamente de nuestros servidores. Si eres madre, padre o tutor y sabes que tu hijo o hija nos ha proporcionado información personal, ponte en contacto con nosotros para que podamos tomar las medidas necesarias.

## Cambios a esta Política de Privacidad

Podemos actualizar nuestra Política de Privacidad de vez en cuando. Por ello, te recomendamos revisar esta página periódicamente por si hay cambios. Te notificaremos cualquier cambio publicando la nueva Política de Privacidad en esta página.

Esta política es efectiva desde el 2024-01-01

## Contáctanos

Si tienes preguntas o sugerencias sobre nuestra Política de Privacidad, no dudes en contactarnos en https://github.com/HereLiesAz/GraffitiXR/issues.


---
*Documentación actualizada el 2026-09-04: esta página era un documento distinto y mucho más breve que la política de privacidad real de la app, sin la estructura ni los apartados legales del documento en inglés — reescrita íntegramente a partir de `docs/en/PRIVACY_POLICY.md`. Se añadió la sección de Informes de fallos, dejando claro que el envío es opcional (opt-in) y está desactivado por defecto (Ajustes > Informes de fallos), y no automático ni sin consentimiento. Se añadió la sección de Comprobaciones de actualizaciones, aclarando que la llamada a la API de GitHub (api.github.com) solo ocurre cuando el usuario pulsa "Buscar actualizaciones" en Ajustes. Se corrigió la descripción de la licencia (código fuente disponible, no de código abierto).*
