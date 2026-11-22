# TVPlayAir — Base de estabilización

## Alcance y contratos

El destino es Android TV. La experiencia v1.0 abarca AirPlay de audio y vídeo directo, duplicación de pantalla y el paso entre ambos. Cada caso debe identificar el emisor, la sesión, el medio y el estado del destino. La prueba de recorrido reproduce 100 veces la transición completa y no requiere dispositivos físicos.

| Área | Base de implementación | Qué revisar en una regresión |
|---|---|---|
| Android TV, vídeo directo | Media3 ExoPlayer, superficie nativa y gestión automática de foco. La carga FCUP está ligada al ID del medio. | Distinguir solicitud aceptada, manifiesto disponible, preparación, primer frame y primer sonido. Verificar que respuestas, STOP y archivos HLS de una sesión anterior no afecten a la nueva. |
| Android TV, audio | Oboe y AAudio con el modo y tamaño de búfer realmente concedidos. Una salida de audio activa en el receptor. | Correlacionar generación RAOP, primer PCM, apertura y cierre de Oboe y comienzo de ExoPlayer. La corrección de reloj o las estadísticas acumuladas de AudioFlinger no sustituyen la medición de desfase audiovisual. |
| AirPlay desde iPhone | Observaciones de red y regresiones a partir de capturas depuradas. iOS y la app emisora seleccionan la ruta y deciden cuándo enviar el siguiente medio. | Distinguir `/stop` seguido de `/play`, `/stop` sin `/play` en toda la captura y `/play` recibido sin avance en el destino. Verificar el canal inverso y la selección de salida. |
| Duplicación | MediaCodec sobre la superficie nativa. Tratar el vídeo reflejado y su audio como flujos negociados. Al recuperar el decoder, exigir configuración y una imagen clave antes de continuar. | Registrar tamaño y códec **codificados**, capacidades del decoder elegido, disponibilidad de la superficie, primer paquete, primera imagen clave y primer frame. Una salida visual escalada no reduce la resolución que el decoder tiene que aceptar. |
| Paso entre modos | Conservar un solo decoder en uso. Invalidar metadatos y flujos del medio anterior antes de mostrar el siguiente. | Verificar audio → duplicación → vídeo → audio, dos vídeos consecutivos con el iPhone bloqueado y vuelta a duplicación. Separar audio simultáneo dentro del TV de salida simultánea en TV y altavoz del iPhone. |

## Qué mostró la captura y qué sigue abierto

- **Run #191, Android TV.** Tras el cambio directo a otro ítem, el emisor envió `/stop` seguido de `/play`. El vídeo se preparó y los manifiestos observados respondieron. La captura posterior del paso de duplicación a vídeo muestra que Oboe se cerró antes de que ExoPlayer entrara en preparación. El log del receptor por sí solo no identifica cómo sonó el iPhone.
- **Audio previo durante duplicación.** `ec94447` añade la retirada de RAOP anterior, conserva audio asociado a la misma sesión y procesa las descripciones de audio y vídeo de un `SETUP` combinado. La prueba en un destino real sigue pendiente.
- **Capacidad del decoder.** El código intenta un decoder por MIME cuando la búsqueda por formato falla. Tratar este camino como riesgo de anuncio y selección para retrato de alta resolución. Antes de cambiar perfiles o anunciar límites, cruzar la configuración codificada y las capacidades que publica Android. Registrar si falla al configurar, al recibir la imagen clave o al presentar.
- **Soporte del emisor.** No deducir de un log ADB la salida del altavoz, el soporte de reproducción en segundo plano ni la política de una app de iOS. Observaciones distintas requieren trazas distintas.

## Una sola pasada de verificación por candidato

1. Fijar un único SHA de APK, el modelo y SoC del destino, versión de Android, versión de iOS y app emisora. Marcar cada acción con su hora y registrar ambas salidas de audio.
2. Ejecutar de una vez `./gradlew :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. La CI ejecuta también las pruebas nativas de host y comprueba Cast. El recorrido `MediaContinuityBaselineTest` forma parte de la suite JVM.
3. En una sesión de captura continua, ejecutar los recorridos de la tabla. Alternar escenarios de prueba en el mismo orden con la misma versión. Para duplicación usar horizontal y vertical, incluyendo el perfil de alta resolución que falló. Para audio grabar un estímulo común que permita identificar eco y sincronía en ambas salidas.
4. Correlacionar por sesión, medio y generación. Los tiempos de solicitud a primer frame y a primer PCM se informan por separado. Normalizar la captura para quitar URLs firmadas, credenciales y material criptográfico antes de agregar una regresión.
5. Agrupar en el mismo candidato las correcciones demostradas que comparten contrato. Repetir la suite automática completa al final. Si queda un caso abierto, registrar el primer evento ausente o incorrecto y la evidencia necesaria para resolverlo.

La JVM comprueba los contratos entre componentes mediante dobles de motor. Android CI comprueba compilación, pruebas y empaquetado. La validación física de salida de audio, decodificación y sincronía se registra en [HARDWARE_VALIDATION_v1.0.md](HARDWARE_VALIDATION_v1.0.md).

## Referencias oficiales

- AndroidX Media3 `1.11.1` es la estable publicada en la tabla consultada. `1.12.0-alpha02` queda como candidata cuando exista una mejora aplicable y verificable. [Notas de versiones](https://developer.android.com/jetpack/androidx/releases/media3)
- [Foco de audio de Android](https://developer.android.com/media/optimize/audio-focus) · [Oboe](https://developer.android.com/games/sdk/oboe/low-latency-audio) · [AAudio y MMAP](https://source.android.com/docs/core/audio/aaudio)
- [Capacidades de MediaCodec](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities) · [Métricas de Media3](https://developer.android.com/media/media3/exoplayer/analytics) · [AIDL](https://developer.android.com/develop/background-work/services/aidl)
- [Selección de ruta AirPlay en iOS](https://developer.apple.com/documentation/avfoundation/supporting-airplay-in-your-app) · [Uso de AirPlay y duplicación](https://support.apple.com/en-us/102661)
