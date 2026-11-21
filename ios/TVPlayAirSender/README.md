# TVPlayAir Sender para iPhone y iPad

Envía una URL HTTP(S) al receptor Android TV con Google Cast. El botón de conexión y los controles de reproducción proceden del SDK oficial. El emisor habilita Cast Connect para Android TV. Esta app usa el selector de Google Cast.

## Configuración de desarrollo

1. Registra el receptor de TVPlayAir en la [consola de Google Cast](https://cast.google.com/publish/) y asocia la app Android TV y el dispositivo de desarrollo.
2. Activa la opción Gradle `-Ptvplayair.castConnect=true` al preparar el receptor Android TV.
3. En macOS con Xcode y XcodeGen, ejecuta `xcodegen generate` desde esta carpeta. Abre el proyecto generado y configura el equipo de firma y `CAST_RECEIVER_APP_ID` con el ID registrado de ocho caracteres. La app usa ese ID también para el descubrimiento Bonjour.
4. Desde la app, abre el botón Cast, elige TVPlayAir y envía una URL que el televisor pueda abrir. Usa los controles de Google Cast para pausar, reanudar o buscar.

Usa Google Cast SDK 4.8.6 mediante Swift Package Manager. El código fuente y el manifiesto están en el repositorio. La [CI de iOS](https://github.com/ReinierTutoriales/TVPlayAir/actions/workflows/ios-sender-ci.yml) compila el emisor y lo abre en un simulador. El envío real requiere un iPhone y un Android TV configurados para el mismo receptor registrado. [Configuración iOS de Google Cast](https://developers.google.com/cast/docs/ios_sender/)

## Prueba física de continuidad

Instala en el televisor la compilación del mismo commit habilitada con `-Ptvplayair.castConnect=true`. Usa el ID registrado como ajuste local de compilación de Xcode, sin editar `project.yml`. Con ambos equipos en la misma red, envía un video A, pulsa Inicio con «No conservar actividades» habilitado y confirma que Android destruyó la Activity mientras el servicio de reproducción siguió activo. Envía un video B en la misma sesión Cast sin abrir TVPlayAir. Al volver, comprueba que aparece B en su posición y estado de pausa actuales. Finalmente, detén la reproducción desde el iPhone y restaura el ajuste de desarrollador. Registra el commit, modelos, versiones y resultado de cada paso.
