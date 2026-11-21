# TVPlayAir — Estado vivo de continuidad

Última actualización: 2026-11-21. Rama de trabajo: `feat/direct-airplay-playback`.

> Este documento es la **fuente operativa de verdad** para decidir el siguiente cambio. Separar siempre **observado en código**, **implementado**, **probado** y **pendiente**. Nunca convertir una hipótesis de rendimiento en una promesa.

## Norte de producto

Recepción AirPlay en Android TV con presentación automática y minimalista: música con carátula y controles; vídeo directo inmersivo; duplicación de pantalla sin interferencias. Al cambiar de canción, app o modalidad, conservar la sesión de recepción y mostrar **solo** el medio actual, sin carátulas ni fotogramas antiguos. Buscar transiciones rápidas, pero medir antes de declarar latencia mínima. No implica implementar CarPlay ni controlar el ciclo de vida de las apps iOS.

## Decisiones que mantenemos

1. **Receptor desacoplado de la UI**: `SessionService` corre en `:session`; la Activity no es autoridad del protocolo. Pendiente: propiedad o restauración robusta de los motores audiovisuales.
2. **Reutilización condicionada**: conservar Media3 ExoPlayer entre vídeos y limpiar media items; no mantener varios decodificadores 4K sin medición de memoria y capacidades.
3. **Una autoridad de reproducción**: reutilizar `AirPlayPlaybackEvents` y su `playbackId`; extender el control de identidad a Binder, UI, comandos y metadatos. Evitar un segundo árbitro contradictorio.
4. **Separar datos de transporte**: `onNowPlaying(null)` no equivale a `onAudioStreamStopped`. Solo señales de transporte pueden detener RAOP.
5. **Calidad adaptativa**: permitir selección adaptativa de pistas en Media3, sin forzar bitrate máximo.
6. **Primera imagen válida**: no declarar transición visual completa hasta recibir primer fotograma del medio actual; disponer de timeout y estado recuperable.

## Evidencia y estado

| Aspecto | Evidencia de código | Estado |
|---|---|---|
| Sesiones vídeo | `AirPlayPlaybackEvents.SessionKey(peer, appleSessionId)` y `playbackId`; control de sesión en `/play`, `/stop`, `/rate`, `/scrub` | Existente; alcance incompleto en callbacks IPC |
| RAOP y metadatos | `MainActivity.onNowPlaying` y `SessionService.onNowPlaying` no deben detener el audio por metadata nula | Corregido en rama; CI pendiente |
| Motor Media3 | `WebVideoPlayer.stopAndClear` conserva instancia y limpia media items | Implementado; coste y frames residuales sin medir |
| ABR | Se quitó `setForceHighestSupportedBitrate(true)` | Implementado; buffering sin medir |
| Órdenes de vídeo | `MainActivity` filtra comandos si no hay reproducción directa activa | Implementado; falta ligar comando a playbackId extremo a extremo |
| Callbacks Binder | Callback y ServiceConnection por epoch; eventos despachados solo si el vínculo sigue vigente | Implementado (`324716c7`); CI y pruebas pendientes |
| Vida de motores | `MainActivity.onDestroy` libera Media3 y coordinador | Riesgo observado; pendiente diseño y prueba de restauración |
| Primer frame | `WebVideoPlayer.onRenderedFirstFrame` registra log; no confirma transición al coordinador | Pendiente |
| Interoperabilidad real iOS | Depende de protocolos y política de cada app, DRM y estado de iOS | No certificada |

## Ruta de ejecución — en este orden

### A. Integridad de sesión (bloqueante)

- [ ] Inspeccionar eventos RTSP/RAOP y HTTP y definir qué identificadores son realmente confiables. La IP sola no basta.
- [x] Añadir cierre terminal y epoch en `SessionConnection` para impedir reconexión tras `close()` y notificaciones `onSharedMemoryReady` encoladas de epochs anteriores (commit `5128f525`).
- [x] Aislar callbacks Binder y ServiceConnection por epoch de vínculo; descartar entregas de vínculos anteriores (`324716c7`).
- [ ] Incorporar pruebas JVM/Robolectric para callbacks encolados y reconexión; comprobar compilación y CI.
- [ ] Vincular órdenes directas a `playbackId` y descartar `stop/seek/rate` obsoletos antes de tocar la UI.
- [ ] Definir señales explícitas de inicio/parada de audio y limpiar estado solo cuando corresponda.

### B. Continuidad audiovisual

- [x] Conservar referencia del vídeo directo activo en `SessionService` y reenviarla al registrar una Activity nueva (`7becbe52`); recuperación por nueva preparación, no preservación de decoder.
- [ ] Completar restauración de posición, pausa y controles; invalidar la referencia al finalizar reproducción natural y asegurar no reactivar vídeo antiguo. Contrastar con superficies Android y FGS.
- [ ] Añadir handshake de primer frame y timeout al cambiar a vídeo; no enseñar frames anteriores.
- [x] Impedir que `prepareAndPlay` reactive vídeo tras `stop` durante `SWITCHING_TO_DIRECT` (`31b6b4c2`, `793d6e31`); añadida prueba de regresión (`94c7de7b`). Ejecución pendiente.
- [ ] Verificar recuperación audio→vídeo→audio y espejo→vídeo→espejo, sin audio doble ni estado stale.
- [ ] Controlar metadatos y carátulas con identidad del medio; cancelar resultados asíncronos obsoletos.

### C. Rendimiento y certificación

- [ ] Ejecutar build, lint y JVM sobre HEAD de la rama y registrar run/commit.
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
