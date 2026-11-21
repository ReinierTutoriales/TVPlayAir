# TVPlayAir — Estado vivo de continuidad

Última actualización: 2026-11-21. Rama de trabajo: `feat/direct-airplay-playback`.

> Este documento es la **fuente operativa de verdad** para decidir el siguiente cambio. Separar siempre **observado en código**, **implementado**, **probado** y **pendiente**. Nunca convertir una hipótesis de rendimiento en una promesa.

## Norte de producto

Recepción AirPlay en Android TV con presentación automática y minimalista: música con carátula y controles; vídeo directo inmersivo; duplicación de pantalla sin interferencias. Al cambiar de canción, app o modalidad, conservar la sesión de recepción y mostrar **solo** el medio actual, sin carátulas ni fotogramas antiguos. Buscar transiciones rápidas, pero medir antes de declarar latencia mínima. No implica implementar CarPlay ni controlar el ciclo de vida de las apps iOS.

## Decisiones que mantenemos

1. **Receptor desacoplado de la UI**: `SessionService` corre en `:session`; la Activity no es autoridad del protocolo. El video directo conserva el reproductor durante cambios de configuración. Falta verificar la continuidad al cerrar la pantalla.
2. **Reutilización condicionada**: conservar Media3 ExoPlayer entre vídeos y limpiar media items; no mantener varios decodificadores 4K sin medición de memoria y capacidades.
3. **Una autoridad de reproducción**: reutilizar `AirPlayPlaybackEvents` y su `playbackId`; extender el control de identidad a Binder, UI, comandos y metadatos. Evitar un segundo árbitro contradictorio.
4. **Separar datos de transporte**: `onNowPlaying(null)` no equivale a `onAudioStreamStopped`. Solo señales de transporte pueden detener RAOP.
5. **Calidad adaptativa**: permitir selección adaptativa de pistas en Media3, sin forzar bitrate máximo.
6. **Primera imagen válida**: no declarar transición visual completa hasta recibir primer fotograma del medio actual; disponer de timeout y estado recuperable.

## Evidencia y estado

| Aspecto | Evidencia de código | Estado |
|---|---|---|
| Sesiones vídeo | `AirPlayPlaybackEvents.SessionKey(peer, appleSessionId)` y `playbackId`; control de sesión en `/play`, `/stop`, `/rate`, `/scrub` | Identidad propagada por Binder para el video directo |
| RAOP y metadatos | `MainActivity.onNowPlaying` y `SessionService.onNowPlaying` no deben detener el audio por metadata nula | Implementado; transiciones de audio por validar en TV |
| Motor Media3 | `WebVideoPlayer.stopAndClear` conserva instancia y limpia media items | Implementado; coste y frames residuales sin medir |
| ABR | Se quitó `setForceHighestSupportedBitrate(true)` | Implementado; buffering sin medir |
| Órdenes de vídeo | `MainActivity` filtra comandos si no hay reproducción directa activa | Implementado; las órdenes llevan playbackId hasta la Activity |
| Callbacks Binder | Callback y ServiceConnection por epoch; eventos despachados solo si el vínculo sigue vigente | Implementado (`324716c7`); compila en CI; faltan pruebas instrumentadas de reconexión |
| Vida de motores | DirectVideoViewModel conserva ExoPlayer y MediaSession durante cambios de configuración. Se verifica el Binder y el ID del medio al volver a conectar la superficie | Implementado en e3357d6; falta verificar la recreación en Android TV |
| Primer fotograma de video directo | Media3 identifica el medio y la superficie del evento. El coordinador solo revela la reproducción vigente y restaura la ruta anterior si supera 15 s | Regresiones JVM ejecutadas. Composición y latencia física pendientes |
| Interoperabilidad real iOS | Depende de protocolos y política de cada app, DRM y estado de iOS | No certificada |

## Ruta de ejecución — en este orden

### A. Integridad de sesión (bloqueante)

- [ ] Inspeccionar eventos RTSP/RAOP y HTTP y definir qué identificadores son realmente confiables. La IP sola no basta.
- [x] Añadir cierre terminal y epoch en `SessionConnection` para impedir reconexión tras `close()` y notificaciones `onSharedMemoryReady` encoladas de epochs anteriores (commit `5128f525`).
- [x] Aislar callbacks Binder y ServiceConnection por epoch de vínculo; descartar entregas de vínculos anteriores (`324716c7`).
- [ ] Incorporar pruebas JVM/Robolectric para callbacks encolados y reconexión; comprobar compilación y CI.
- [x] Vincular órdenes directas a `playbackId` y descartar `stop/seek/rate` obsoletos antes de tocar la UI.
- [ ] Definir señales explícitas de inicio/parada de audio y limpiar estado solo cuando corresponda.

### B. Continuidad audiovisual

- [x] Conservar referencia del vídeo directo activo en `SessionService` y reenviarla al registrar una Activity nueva (`7becbe52`); se vuelve a conectar la superficie si se conserva ExoPlayer y se prepara desde el punto guardado si se perdió.
- [x] Restaurar posición exacta en VOD y pausa explícita del mando o del iPhone al registrar otra Activity. El servicio invalida el video al terminar y rechaza actualizaciones de una Activity reemplazada.
- [ ] Verificar en Android la recreación de Activity, la desaparición temporal de superficie y el comportamiento del servicio en segundo plano.
- [x] Confirmar el primer fotograma del video directo vigente y acotar la preparación a 15 s. La verificación visual del cambio corresponde a la prueba de integración en TV.
- [x] Impedir que `prepareAndPlay` reactive vídeo tras `stop` durante `SWITCHING_TO_DIRECT` (`31b6b4c2`, `793d6e31`); añadida prueba de regresión (`94c7de7b`). Ejecución pendiente.
- [ ] Verificar recuperación audio→vídeo→audio y espejo→vídeo→espejo, sin audio doble ni estado stale.
- [ ] Controlar metadatos y carátulas con identidad del medio; cancelar resultados asíncronos obsoletos.

### C. Rendimiento y certificación

- [x] Verificar compilación Android y pruebas JVM y nativas en la [CI de e3357d6](https://github.com/ReinierTutoriales/TVPlayAir/actions/runs/44937591482).
- [ ] Ejecutar lint independiente.
- [x] Añadidas pruebas JVM de 100 transiciones y telemetría obsoleta en `AirPlayPlaybackEventsTest` (`1ebe8039`); ejecución pendiente.
- [ ] Pruebas de reconexión/cierre Binder, eventos reordenados, superficies y errores.
- [ ] Instrumentar primer frame, primera muestra de audio, buffering, dropped frames y p50/p95/p99.
- [ ] Documentar por separado pruebas simuladas y compatibilidad real iPhone/Android TV.

## Regla para incorporar nuevas ideas

Registrar: **hipótesis → evidencia oficial/código → alternativa más simple → coste y riesgo → prueba de aceptación → decisión**. Si la prueba falla, revertir o revisar la idea; si pasa, actualizar esta tabla y la arquitectura. Mantener cambios concentrados en la misma rama, sin fusionar antes de CI verde.

## Documentos relacionados

- [Arquitectura y limitaciones](TVPLAYAIR_CONTINUITY_ARCHITECTURE_2026.md)
- [Especificación de ingeniería](ENGINEERING_SPEC_v1.0.md)

### Registro de ejecución — 2026-11-21

- `5128f525`: `SessionConnection.close()` se vuelve terminal; `connect()` y backoff no reconectan tras cierre; los avisos de memoria compartida encolados comprueban epoch. **No resuelve todavía callbacks generales de vínculos anteriores.** Compilación y pruebas CI aún sin verificar.

- `324716c7`: cada vinculación crea un callback Binder, DeathRecipient y ServiceConnection propios; todas las entregas comprueban epoch al ejecutarse. El mapeo de memoria se serializa en el hilo principal con detach para evitar carreras entre ambos. **No se ha validado todavía por CI**.

- `ab043231`: corrige propiedad de ParcelFileDescriptor antes de despacho asíncrono; cierra FDs si epoch inválido. Revisar manejo de error de mapeo nativo y verificar compilación.
- `1ebe8039`: cuatro pruebas unitarias del árbitro AirPlay, incluida secuencia de 100 cambios. Son pruebas de estado, **no** de latencia ni de interoperabilidad iOS. No ejecutadas en esta sesión.

- `793d6e31`: el coordinador comprueba el estado tras la preparación y no declara `DIRECT_ACTIVE` si una parada ya lo cambió. Prueba añadida; no hay workflow PR asociado a ese commit según consulta, por tanto no certificada.

- `7becbe52`: el servicio retiene `pendingDirectUrl` como referencia de vídeo activo, incluso con callback UI presente; la reenvía en `registerCallback`. Limitación: una recreación puede reiniciar la preparación desde la posición inicial y no preservar pausa/posición; debe resolverse antes de certificar continuidad.
- `db3d4167`: `MainActivity` acepta `stop` durante `SWITCHING_TO_DIRECT`; falta identidad `playbackId` en órdenes para descartar comandos obsoletos.

## Consolidación de identidad y restauración (2026-11-21)

- `a15fea5c`–`bcf736f4`: AIDL incorpora `finishDirectPlayback(playbackId)`; el servicio invalida exclusivamente la referencia del ID coincidente; Activity avisa en fin natural, stop y fallo de preparación. El servicio registra posición a partir de telemetría del mismo ID (`dfaf229c`).
- **Pendiente crítico**: el callback `onPlaybackFinished` del reproductor no incluye ID de origen; si llega tarde podría afectar una reproducción posterior. Los comandos AirPlay tampoco transportan ID de origen de extremo a extremo. La posición inicial AirPlay usa semántica mixta (fracción/segundos): validar checkpoint para valores ≤1 segundo. Falta restauración de pausa y propiedad persistente del motor.
- **Verificación**: commits publicados; compilación, pruebas de integración, CI y latencias no verificadas. No declarar terminado ni fusionar a main sin estas evidencias.

- `87721c18`: checkpoint solo con telemetría lista y posición >1 s; evita que estado inicial cero borre la posición AirPlay y evita ambigüedad fracción/segundos por debajo de 1 s. Pendiente modelar explícitamente unidad de posición.
- `5810fcff`: reenvío duplicado de la misma reproducción activa no reinicia Media3. Requiere prueba de registro de callback duplicado.

- `b4870978`–`058d7242` (2026-11-21): `/scrub`, `/rate`, `/stop` capturan `playbackId` desde `AirPlayPlaybackEvents.control` y lo propagan por `AirPlayProtocolListener`, `SessionService`, AIDL y `SessionConnection` hasta `MainActivity`. Servicio y Activity descartan comandos para un ID distinto del vigente. **Pendiente**: prueba compilada de todas las firmas AIDL, regresión de cambios rápidos, confirmación CI y sincronización de estado de pausa.

- `76a4a7fc`: servicio guarda `paused` por reproducción, actualiza con `/rate` y reenvía pausa al registrar Activity después de URL. Solo restaura pausas explícitas de protocolo; no es persistencia completa del reproductor ni elimina el reinicio del decodificador.
- `a62312e7`: prueba nueva de correlación de `playbackId` de controles tras transición. **Prueba añadida, no ejecutada**. Pendientes: compilación AIDL, pruebas CI, cambio de Activity y reproducción sin reinicio.

- `4132f315` (2026-11-21): añadidas pruebas de aislamiento de `stop` entre peers y de comandos para nueva reproducción del mismo peer.
- `e5534122`: se conserva la pausa explícita del protocolo; no se interpreta `Media3.isPlaying=false` durante transición READY como pausa del usuario. Checkpoint de posición solo si `isReady && !isBuffering` y posición >1 s. **Pendiente**: estado real `playWhenReady`, prueba de restauración, validación CI y arquitectura de reproductor persistente.


## Confirmación visual del video directo

La superficie de ExoPlayer permanece conectada bajo una capa negra mientras se prepara el siguiente video. `AnalyticsListener.onRenderedFirstFrame` comprueba el identificador del medio, la superficie y el instante de renderizado antes de revelar la imagen. Una fuente solo de audio recibida por esta ruta se confirma cuando Media3 está listo y selecciona una pista de audio. Si falla el arranque o transcurren 15 s, el receptor vuelve al espejo disponible o a espera, devuelve el control de audio y envía el estado `stopped` por el canal inverso para la reproducción afectada.

`direct_startup` en Logcat registra resultado, identificador y milisegundos desde la recepción de la orden en la Activity. Esa medida incluye el despacho del evento al hilo principal y no incluye la red desde el iPhone. Las pruebas JVM cubren 100 reemplazos consecutivos con fotogramas, finales y temporizadores atrasados, además de restauración tras un timeout. Falta medir percentiles y verificar la composición real del frame en Android TV.

Se conserva Media3 1.11.1 estable. La beta 1.12.0-beta01 mejora el uso de memoria de los segmentos del búfer y contiene ajustes de renderizado entre periodos, pero esta transición puede resolverse con la versión estable y una sola instancia del reproductor. [API del primer fotograma](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener#onRenderedFirstFrame(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,java.lang.Object,long)) · [Notas de Media3](https://developer.android.com/jetpack/androidx/releases/media3)


## Restauración de video al recrear la Activity

El proceso de sesión entrega el último punto de VOD en milisegundos y la intención de reproducción en una sola llamada Binder. El punto admite cero y posiciones inferiores a un segundo, independientemente de la fracción inicial de AirPlay. Se registra al avanzar en una línea de tiempo VOD navegable y al ocultar o destruir la pantalla. Si el video es en vivo, Media3 vuelve a elegir la posición inicial. El video preparado en pausa recibe esa intención antes de llamar a `prepare()`.

El mando y el iPhone actualizan una sola intención explícita bajo el mismo bloqueo. Una pausa por interrupción temporal de Media3 no modifica esa intención. El servicio compara el Binder de la Activity y el identificador de reproducción antes de aceptar cambios. Tras perder ExoPlayer, la restauración vuelve a preparar el medio. Si se conserva durante un cambio de configuración, solo se conecta la nueva superficie. [Contrato de Player en Media3](https://developer.android.com/reference/androidx/media3/common/Player)


## Reutilización del reproductor durante cambios de configuración

`DirectVideoViewModel` conserva el ExoPlayer y su MediaSession durante un cambio de configuración que recrea la Activity. La pantalla anterior suelta la superficie y sus callbacks. La nueva vuelve a conectar el mismo reproductor si coinciden el Binder del servicio y el identificador de la reproducción vigente. En este caso no se vuelve a cargar ni preparar el medio. El punto de restauración se mantiene para cuando Android pierde el reproductor. El espejo se queda como ruta de retorno durante la restauración del video directo, sin arrancar un segundo decodificador.

**Evidencia de e3357d6.** Pasaron 59 pruebas JVM locales y la [CI del commit](https://github.com/ReinierTutoriales/TVPlayAir/actions/runs/44937591482). Las pruebas cubren el retorno al espejo sin iniciarlo durante la restauración. Falta verificar en Android TV el cambio de superficie con video en marcha y en pausa, y una parada recibida entre las dos pantallas. El lint local quedó pendiente por la descarga de Gradle.

Este alcance cubre recreaciones conservadas por ViewModel. La reproducción persistente tras cerrar la Activity y la extensión iOS siguen pendientes. La implementación de Cast Connect requiere un ID de receptor registrado y un emisor asociado para validar el flujo de extremo a extremo. La extensión requiere verificar la ruta en iOS 27 con un destino Android real. [ViewModel de Android](https://developer.android.com/topic/libraries/architecture/viewmodel) · [Cast Connect](https://developers.google.com/cast/docs/android_tv_receiver) · [Rutas de medios de Apple](https://developer.apple.com/documentation/avsystemrouting/routing-and-streaming-media-to-remote-devices)


## Cast Connect en Android TV

La opción de compilación `-Ptvplayair.castConnect=true` habilita el punto de entrada de Cast Connect. El receptor utiliza el SDK oficial de Google y conecta su gestor de medios a la MediaSession del ExoPlayer existente. Acepta una URL HTTP(S) por carga, conserva el punto de inicio en milisegundos y respeta autoplay. Las colas y los contenidos que requieren resolución por cuenta, derechos o catálogos necesitan un emisor y una integración específicos.

Cast y AirPlay comparten el coordinador de presentación y el límite de un decodificador de video. Para validar Cast en un dispositivo, el emisor debe usar el ID de receptor registrado y habilitar compatibilidad con Android TV. Cast mantiene la sesión si la Activity queda en segundo plano y el video sigue activo. Si se pierde temporalmente la superficie, se conserva el reproductor y se vuelve a mostrar la imagen al recibir un fotograma de la superficie nueva. La destrucción definitiva de la Activity todavía libera el motor. [Cast Connect](https://developers.google.com/cast/docs/android_tv_receiver/core_features) · [Registro del receptor](https://developers.google.com/cast/docs/registration)
