# TVPlayAir Sender para iPhone y iPad

Envía una URL HTTP(S) al receptor Android TV con Google Cast. El botón de conexión y los controles de reproducción proceden del SDK oficial. El emisor habilita Cast Connect para Android TV. Esta app usa el selector de Google Cast.

## Configuración de desarrollo

1. Registra el receptor de TVPlayAir en la [consola de Google Cast](https://cast.google.com/publish/) y asocia la app Android TV y el dispositivo de desarrollo.
2. Activa la opción Gradle `-Ptvplayair.castConnect=true` al preparar el receptor Android TV.
3. En macOS con Xcode y XcodeGen, ejecuta `xcodegen generate` desde esta carpeta. Abre el proyecto generado y configura el equipo de firma y `CAST_RECEIVER_APP_ID` con el ID registrado de ocho caracteres. La app usa ese ID también para el descubrimiento Bonjour.
4. Desde la app, abre el botón Cast, elige TVPlayAir y envía una URL que el televisor pueda abrir. Usa los controles de Google Cast para pausar, reanudar o buscar.

Usa Google Cast SDK 4.8.6 mediante Swift Package Manager. El código fuente y el manifiesto están en el repositorio. La compilación con Xcode y el envío real a un receptor registrado están pendientes. [Configuración iOS de Google Cast](https://developers.google.com/cast/docs/ios_sender/)
