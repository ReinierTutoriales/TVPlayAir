# TVPlayAir — Continuidad multimedia y experiencia TV (arquitectura objetivo)

Estado: especificación de diseño y auditoría; **no equivale a implementación ni certificación**. Referencia inicial: `feat/direct-airplay-playback`, commit `5906b0b`. Fecha: 2026-11-21.

## Misión y criterios

TVPlayAir es un receptor multimedia AirPlay para Android TV que toma como referencia la continuidad, presentación contextual y controles de CarPlay, **no** una implementación del framework CarPlay ni un clon de su interfaz. La sesión del iPhone dirige la experiencia; el televisor muestra automáticamente vídeo a pantalla completa, música con carátula y controles, o duplicación sin overlays. El mando y los controles compatibles del emisor deben operar sobre la sesión vigente.

Objetivos: transiciones rápidas y medibles; ausencia de carátulas/fotogramas/metadatos antiguos; continuidad ante cambios repetidos de app y medio; sincronización A/V; máximo aprovechamiento de capacidades de códec, HDR y audio del emisor, Android y hardware. No prometer latencia cero, compatibilidad universal, 4K/HDR ni audio sin pérdidas para fuentes que no los proporcionan.

## Arquitectura objetivo

1. **Transporte/protocolo:** recepción AirPlay/RAOP/RTSP/HTTP, mDNS, negociación de capacidades, identidad del emisor y comandos; eventos inmutables normalizados; sin dependencia de Compose.
2. **Árbitro de sesiones (autoridad única):** un reducer serializado procesa eventos con `sessionId`, `generation`, `mediaId`, `sequence` y `receivedAt`; ordena las transiciones y rechaza callbacks tardíos. IP de origen por sí sola no identifica de forma segura una sesión.
3. **Motor audiovisual:** Media3/ExoPlayer para vídeo directo; MediaCodec para espejo; audio Oboe/AudioTrack según ruta y capacidades; AudioFocus y relojes de presentación; preparación y liberación de superficies con ownership explícito.
4. **Estado de presentación:** snapshot único e inmutable `Idle | Preparing | Audio | Video | Mirror | Recovering | Error`, con metadatos y capacidades ligados a la generación activa; sin campos independientes que permitan combinaciones contradictorias.
5. **UI TV:** Compose con negro AMOLED, carátula grande y datos legibles en audio; vídeo inmersivo con HUD temporal; espejo sin HUD; navegación D-pad, foco visible, accesibilidad, animaciones breves y cancelables. Nunca bloquear el hilo principal con decodificación, red o arte.

### Reglas invariantes

- Un único medio tiene autoridad visual en cada instante; los eventos de una generación anterior nunca alteran el nuevo snapshot.
- Cambiar de vídeo a audio invalida primero la carátula, el progreso, la telemetría y los controles del vídeo anterior; solo muestra datos del nuevo medio.
- Preparar un vídeo nuevo no debe mostrar el último frame del vídeo anterior. Mantener una presentación transitoria neutra hasta primer frame válido; el timeout debe recuperar y notificar fallo, no quedar en negro indefinidamente.
- Cada imagen de carátula se identifica por `sessionId + mediaId + artworkId`; las descargas y decodificaciones atrasadas se cancelan o descartan. Metadatos parciales se fusionan solo dentro del mismo medio.
- El control remoto se envía al destino asociado a la sesión actual; controles no soportados se deshabilitan y nunca se dirigen a un emisor anterior.
- El cambio de aplicación no presupone que el iPhone emita un evento identificable: usar los eventos realmente disponibles, reconciliación y timeouts. DRM, políticas de las apps y protocolos propietarios limitan compatibilidad.
- La duplicación, vídeo directo y audio deben preservar el ciclo de vida del servicio y sobrevivir a la recreación de Activity cuando Android lo permita; no eludir restricciones de inicio en segundo plano.

## Máquina de estados y transición

`Idle -> Preparing(kind, generation) -> Audio|Video|Mirror`; cualquiera puede pasar a `Preparing` para un nuevo medio; errores transitorios pasan a `Recovering` con backoff acotado; desconexión definitiva pasa a `Idle`. Eventos `Stop/Flush/Artwork/Telemetry` antiguos se ignoran. Un nuevo `Play` incrementa generación antes de iniciar operaciones asíncronas.

El árbitro distingue `requested`, `buffering`, `ready`, `firstFrame`, `playing`, `paused`, `ended`, `failed`. La UI nunca infiere reproducción efectiva exclusivamente de `playWhenReady`. Una transición se considera completada al recibir el evento apropiado del motor, no al pedir el cambio.

## Auditoría observada (5906b0b)

- `MainActivity.handleDirectVideoKey` activa acciones con D-pad y muestra HUD; las repeticiones reinician el timeout de cuatro segundos. Probar DOWN/UP, long-press y accesibilidad.
- `WebVideoPlayer.togglePlayback()` decide mediante `playWhenReady`; revisar buffering, ended y sesiones reemplazadas; enrutar comandos a un coordinador único.
- `seekVideoBy` solo limita el mínimo; respetar seekability, duración conocida, streams live y disponibilidad de comandos Media3.
- `Handler` del HUD se limpia en `onStop`; añadir cierre explícito en destrucción y pruebas de callbacks tardíos.
- La prueba nueva de `TvOverlayStateTest` valida visibilidad lógica, **no** cambios de app, superficies, eventos tardíos, IPC, A/V ni latencia.
- La especificación v1.0 declara capacidades 4K/HEVC/HDR, DACP, HID y tolerancia a fallos: cada afirmación requiere pruebas por capacidad y dispositivo, no solo revisión estática.
- La documentación antigua dice `targetSdk 35` y `NDK r27+`; verificar configuración real y requisitos vigentes antes de actualizar. Evitar prometer passthrough HDR, cero copias o ausencia de crashes sin medición.

## Tecnología y fuentes oficiales

- Apple, WWDC26 «Rev up your CarPlay app»: https://developer.apple.com/videos/play/wwdc2026/212/ — `CPPlaybackConfiguration`, actualizaciones de progreso, MiniPlayer y presentación audio/vídeo son **patrones de diseño para apps CarPlay**, no API pública que habilite un receptor AirPlay Android.
- Apple CarPlay: https://developer.apple.com/carplay/ — CarPlay video apps requieren contexto y permisos compatibles; no extrapolar entitlements al receptor.
- Apple CarPlay Developer Guide (2026-10-13): https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf — referencia para UX, no protocolo AirPlay abierto.
- Apple Media Device Discovery Extension: https://developer.apple.com/documentation/xcode/configuring-media-device-discovery — **deprecated en iOS 27**; no construir integración nueva sobre esa extensión. Investigar reemplazo oficial «Routing and streaming media to remote devices» solo para una app iOS complementaria, no como solución automática para todos los emisores.
- Google Media3 releases: https://developer.android.com/jetpack/androidx/releases/media3 — estable 1.11.1, alfa 1.12.0-alpha02 según documentación consultada 2026-11-21. Revisar cambios y compatibilidad antes de migrar.
- Media3 1.11: https://developer.android.com/blog/posts/media3-1-11-whats-new — UI Compose con slots, foco D-pad, MiniController y PlayerPool. **PlayerPool no se debe adoptar sin demostrar que múltiples reproductores mejoran este caso de una sola sesión activa.**
- Arquitectura Media3: https://developer.android.com/media/media3 — evaluar `MediaSession`/`MediaSessionService` para controles Android y continuidad de servicio, sin confundirlo con sesiones AirPlay.
- Actualizaciones AndroidX: https://developer.android.com/latest-updates

Inventario comprobado en `gradle/libs.versions.toml`: AGP 9.5.2; Kotlin 2.4.21; Compose BOM 2026.06.01; Activity Compose 1.13.0; TV Material 1.1.0; Oboe 1.11.0; Media3 1.11.1; BouncyCastle 1.87. Media3 coincide con estable oficial consultada. Para el resto, **estado de actualización pendiente de contraste por artefacto**. Preferir estables; pre-releases solo tras matriz de compatibilidad, pruebas y beneficio verificable.

## Plan de implementación y validación

**P0 — Corrección funcional:** inventariar caminos de sesión y lifecycle; introducir reducer y generation guard; impedir artwork/frame/telemetry stale; corregir arbitraje audio-vídeo-espejo, seek y callbacks. Tests deterministas con eventos reordenados, conexiones solapadas y fallos de IPC.

**P1 — Continuidad audiovisual:** medir transición audio->vídeo->audio, vídeo->vídeo, app A->B, espejo->directo, reconexión y cambio de pista; instrumentar first-frame, audio-first-sample, drift A/V, buffer underruns, dropped frames, reconexiones y latencia por percentiles p50/p95/p99.

**P2 — Presentación TV:** animaciones cancelables, controles accesibles, foco D-pad, barra contextual, carátula con placeholders seguros; benchmark Compose y memoria en perfiles Android TV representativos.

**P3 — Compatibilidad:** matriz por iOS/emisor/aplicación/códec/DRM, APIs Android, SDR/HDR y hardware; CI build + lint + JVM + instrumentadas + pruebas de estrés con emisor simulado. Las pruebas físicas end-to-end siguen siendo necesarias para certificar interoperabilidad y latencia real.

### Criterios de aceptación

- 100 iteraciones automatizadas de cambios de medio sin callback obsoleto que cambie UI o controles.
- Cero visualización de carátula, metadatos o último frame de una generación distinta en las pruebas de transición.
- El sistema nunca queda indefinidamente en `Preparing` o `Recovering`; existe timeout y estado de fallo.
- Progreso y comandos solo para la sesión activa; vídeo protegido por restricciones DRM debe fallar de forma explícita.
- Publicar métricas p50/p95/p99 y tasas de fallos antes de fijar objetivos numéricos de latencia; no inventar SLA.
- No declarar «completo» sin CI, APK reproducible y resultados de pruebas de integración pertinentes.

## Gobernanza

Esta guía complementa `docs/ENGINEERING_SPEC_v1.0.md`. Cuando contradiga promesas absolutas de calidad, compatibilidad o comportamiento, prevalecen las limitaciones verificadas y las pruebas. Cada cambio relevante debe incluir pruebas de regresión, documentación de las decisiones y una evaluación de versiones/seguridad. No actualizar dependencias por número sin revisar API, toolchain y regresiones.
