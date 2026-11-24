# TVPlay v1.0 — Especificación de Ingeniería

## 1. Objetivo y Alcance

**Objetivo**: Construir un receptor AirPlay independiente, libre y modular para Android TV que entregue calidad nativa 4K/HEVC/HDR, transición limpia entre espejo de pantalla y reproducción directa de video web, audio estable sin cortes y control desde el mando de la TV, respetando las limitaciones reales de hardware (VPU y Binder).

La experiencia objetivo es conectar el iPhone una vez y manejar la sesión desde la TV. En espejo, cambiar de app o girar el iPhone debe mantener la conexión. En video directo, la TV reproduce el contenido compatible en ExoPlayer mientras el iPhone puede pasar a otra app. Abrir los ajustes o cambiar el tema de la TV debe dejar la sesión y la reproducción activas. Estas transiciones se verifican de extremo a extremo con iPhone, TV y mando.

### Modo de proyección del televisor

La pantalla de espera cede el espacio al video, al espejo o a **Ahora suena** cuando llega contenido. En audio, la sesión RTSP propietaria aporta título, artista, álbum y destino DACP. Los metadatos y la carátula se asocian por `rtptime` cuando el emisor lo incluye. Cambiar de canción retira la carátula anterior y una respuesta atrasada de otra conexión no puede reemplazarla. Los controles de pista de la TV usan DACP y requieren que el emisor publique un destino válido.

La TV muestra **Ahora suena** en negro con carátula, título, artista y tres controles para el mando. El video directo abre a pantalla completa. Arriba o abajo muestran el estado y el avance, OK alterna reproducción y pausa, e izquierda o derecha mueven la posición un 1 % cuando el foco está en la barra y la fuente permite buscar. La franja se cierra con Atrás o tras cinco segundos de inactividad durante la reproducción. En espejo se muestra la pantalla del iPhone y el mando abre los controles disponibles de TVPlayAir. Referencias de diseño [CarPlay en iOS 27](https://developer.apple.com/videos/play/wwdc2026/212/?time=382) y [controles de Android TV](https://developer.android.com/training/tv/playback/controls).

Al cambiar de app con video directo, el reproductor reutiliza la superficie y el canal inverso de la app entrante puede abrirse antes de pedir el video. Los controles con ID de sesión y las actualizaciones del reproductor se verifican contra el video activo. Los emisores que omiten ese ID conservan el control desde la misma dirección IP. Un `FLUSH` de una conexión RAOP reemplazada se descarta. Si llega audio mientras ExoPlayer usa la salida, Oboe registra la sesión y puede reanudarla al terminar el video. La latencia de estas transiciones se mide en el televisor.

- **Instalación normal.** El ajuste «Iniciar receptor con el TV» viene activado y controla el arranque automático tras encender el televisor o actualizar la app. El servicio de recepción LAN usa `connectedDevice`. «Abrir al recibir vídeo» viene desactivado y requiere conceder «Mostrar sobre otras aplicaciones». El servicio verifica el acceso al iniciar una sesión visual. Android decide si admite la apertura desde segundo plano. «Notificaciones del receptor» permite conceder el permiso desde Ajustes y abrir TVPlayAir al tocar la notificación. [Servicios para dispositivos conectados](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device) · [Límites de apertura](https://developer.android.com/guide/components/activities/secure-bal)
- **Televisores que ocultan el acceso especial.** Con una compilación que declare `SYSTEM_ALERT_WINDOW`, el acceso se puede conceder desde una conexión ADB autorizada con `adb shell appops set com.tvplayair.receiver SYSTEM_ALERT_WINDOW allow` y retirar con `adb shell appops set com.tvplayair.receiver SYSTEM_ALERT_WINDOW default`. Después de concederlo, activa «Abrir al recibir vídeo» en TVPlayAir.
- **Equipo dedicado.** El componente opcional `com.tvplayair.receiver/.ProjectionHome` está deshabilitado de fábrica. En equipos de desarrollo o administrados puede habilitarse con `adb shell pm enable com.tvplayair.receiver/.ProjectionHome` y elegirse como aplicación Inicio donde el firmware lo permita. Inicio espera en negro y entra directo a la proyección. En espera, OK abre Ajustes. Para volver, selecciona el lanzador anterior y deshabilita el componente con `adb shell pm disable com.tvplayair.receiver/.ProjectionHome`. [Contrato de Android para Inicio](https://developer.android.com/reference/android/content/Intent#CATEGORY_HOME)

La integración con Inicio depende de que el equipo permita seleccionar un lanzador y de que el iPhone inicie AirPlay. Los fabricantes pueden integrar el receptor en su propia imagen del sistema. [Conexión automática en el iPhone](https://support.apple.com/guide/iphone/stream-video-and-audio-from-your-iphone-iphd668e80e6/ios)

| Incluido en v1.0 (Producción) | Excluido de v1.0 (Diferido a v2.0) |
|----------------------------------|-------------------------------------|
| Proceso aislado `:session` + SharedMemory SPSC | Reemplazo del motor C por Rust/Kotlin puro |
| Espejo 4K/HEVC (MediaCodec Low-Latency) | GLSurfaceView / OpenGL ES (PiP y Split-Screen) |
| Video Web directo 4K HDR (Media3 ExoPlayer) | Mezclador multi-bus con ducking (multi-cliente) |
| Audio PCM 48 kHz (MediaCodec/ALAC nativo → Google Oboe) + offset ± ms | Reloj PTP (IEEE 802.1AS) multiroom |
| Control remoto bidireccional DACP (obligatorio) | Puente cableado USB CDC-NCM |
| | Emparejamiento HomeKit persistente (Ed25519) |

## 2. Arquitectura de 3 Capas y Fronteras de Proceso

El sistema separa estrictamente la red/protocolo del renderizado y la UI mediante dos procesos Android independientes:

```
+-------------------------------------------------------------------+
| PROCESO AISLADO (:session) — Resiliencia ante crashes nativos     |
|                                                                   |
|  [MdnsAdvertiser]  -->  [RTSP/HTTP Kotlin + PlayFair JNI]               |
|  (_airplay / _raop)      ├── Video NALs (H.264/HEVC)              |
|                          ├── Audio (MediaCodec/ALAC -> PCM 48 kHz)          |
|                          └── Eventos RTSP/HTTP + DACP             |
+-------------------------------------------------------------------+
         | (Control: AIDL)                     | (Datos: SharedMemory)
         v                                     v
+-------------------------------------------------------------------+
| PROCESO PRINCIPAL (UI / Presentación)                             |
|                                                                   |
|  [Capa de Interacción]          [Motor de Presentación]           |
|  ├── Compose for TV (HUD/Menú)  ├── SurfaceView A: MediaCodec     |
|  ├── DACP (Play/Pause/Seek)     │   (Caché SPS/PPS + Puerta IDR)  |
|  └── Controles D-pad              ├── SurfaceView B: ExoPlayer      |
|                                 │   (4K HDR + User-Agent Safari)  |
|                                 └── Audio: Oboe + AudioFocus      |
+-------------------------------------------------------------------+
```

## 3. Especificación Técnica por Capa

### Capa 1: Motor de Sesión (`android:process=":session"`)

- **Protocolo**: RTSP, HTTP, AirPlay y RAOP en Kotlin con PlayFair, ALAC y transporte en memoria compartida mediante JNI. El enlace Binder permite detectar la pérdida de `:session` y volver a conectar la UI.
- **Descubrimiento mDNS** (`MdnsAdvertiser`): Publica `_airplay._tcp` (puerto 7000) y `_raop._tcp` (puerto 5000) con `features=0x5A7FFFF3,0x1C` con ScreenMultiCodec cuando el perfil admite HEVC, `flags=0x44`, `model=AppleTV3,2`, `overscanned=0` y WifiLock (`WIFI_MODE_FULL_LOW_LATENCY`).
- **Decodificación de Audio en `:session`**: MediaCodec decodifica AAC-ELD y AAC-LC. El decodificador ALAC nativo maneja ALAC. El remuestreador normaliza a PCM estéreo de 16 bits y 48 kHz antes de publicarlo en memoria compartida.
- **Asignación de Memoria**: Creada 100% en C++ con `ASharedMemory_create()` (`<android/sharedmem.h>`), sin reflexión en Kotlin. Los descriptores (fd) se entregan a la UI vía `ParcelFileDescriptor.adoptFd(fd)` por AIDL.

### Capa 2: Motor de Presentación (Proceso Principal)

- **Video (Cero OpenGL en v1.0)**: Dos `SurfaceView` nativos superpuestos gestionados por SurfaceFlinger. Garantiza paso directo al Hardware Composer y preserva metadatos HDR10 / HLG / Dolby Vision en ExoPlayer.
- **Gestión de VPU (Límite de Hardware)**: Los SoCs de Android TV (Amlogic/MediaTek) no soportan doble decodificación 4K HEVC simultánea.
  - Al entrar `POST /play` (video web maximizado), ExoPlayer prepara el stream con User-Agent de Safari iOS y selección adaptativa de pistas.
  - En dispositivos que Android identifica como de poca RAM, el búfer de vídeo directo usa un objetivo de 24 MiB y un rango de 8 a 16 segundos.
  - Se libera MediaCodec del espejo antes de preparar ExoPlayer. `onRenderedFirstFrame` registra cuándo ExoPlayer muestra imagen.
- **Dimensiones de duplicación.** La selección de MediaCodec usa el tamaño del SPS de H.264 o HEVC cuando está disponible. El tamaño del encabezado AirPlay sirve de respaldo.
- **Retorno limpio al Espejo (Caché SPS/PPS + Compuerta IDR)**: Al detener ExoPlayer, se recrea MediaCodec (baja latencia cuando el decodificador la admite), se inyectan los últimos headers SPS/PPS/VPS cacheados y se descartan todos los paquetes entrantes hasta recibir el primer frame IDR limpio (evita corrupción/pantalla verde).
- **Audio**: Google Oboe (C++) lee directamente del SharedRingBuffer de audio con ajuste manual de sincronización (-200 ms a +200 ms). AudioFocus nativo arbitra la transición con ExoPlayer.

### Capa 3: Capa de Interacción (Proceso Principal)

- **DACP (Obligatorio)**: Captura DACP-ID y Active-Remote al conectar. Traduce las teclas multimedia del mando a comandos HTTP `GET /ctrl-int/1/...` hacia el dispositivo Apple. El video directo informa el progreso en `GET /playback-info` y envía cambios de estado a la misma sesión AirPlay por `/reverse`.
- **UI (Jetpack Compose for TV)**: Overlay lateral mínimo (invocado con tecla Menú) para cambiar resolución anunciada, activar/desactivar HEVC, ajustar offset de audio (ms) y mostrar HUD de telemetría (FPS, códec, drops).

## 4. Contratos IPC y Resiliencia

### Plano de Control (AIDL)

- `ITvPlaySession.aidl` (UI → `:session`): `registerCallback`, `startServer`, `stopServer`, `updatePlaybackTelemetry`, `sendDacpCommand`.
- `ITvPlayCallback.aidl` (`:session` → UI): `onSharedMemoryReady(videoPfd, videoSize, audioPfd, audioSize)`, `onVideoConfigChanged`, `onDirectVideoUrl`, `onDirectVideoCommand`, `onClientConnected`, `onClientDisconnected`.

### Plano de Datos (SharedRingBuffer SPSC Lock-Free)

- Tamaños fijos: 16 MB para Video (absorbe keyframes 4K HEVC/H.264) y 512 KB para Audio PCM.
- Alineación: `alignas(64)` individual en `writePos` y `readPos` dentro de `RingHeader` para eliminar false sharing entre núcleos.
- Validación: Campo atómico `magic` (`0x5456504C`) escrito con `memory_order_release` por el productor antes de enviar los fd por AIDL.
- Política de errores:
  - Buffer lleno: El productor descarta el paquete e incrementa `droppedFrames` (nunca bloquea el hilo de red).
  - Paquete > `maxOutSize`: El consumidor avanza `readPos` descartando el paquete para evitar livelock.
- Recuperación ante crash: La UI registra un `IBinder.DeathRecipient` sobre `ITvPlaySession`. Si `:session` muere, la UI ejecuta `detach()` sobre los buffers mapeados, limpia MediaCodec y re-enlaza el servicio automáticamente.

## 5. Estructura del Repositorio y Entorno

- Requisitos de compilación: `minSdk = 29`, `targetSdk = 35`, `compileSdk = 37.1`, Kotlin DSL, NDK 30.0.16248370, CMake 4.1.2, `buildFeatures { compose = true; prefab = true }`, `aidl = true`.
- Librerías NDK obligatorias: `android` (para `ASharedMemory_create`), `log`, `oboe::oboe`.

```
TVPlayAir/app/src/main/
├── aidl/com/tvplayair/receiver/ipc/
│   ├── ITvPlaySession.aidl
│   └── ITvPlayCallback.aidl
├── cpp/
│   ├── CMakeLists.txt
│   ├── shared_ring_buffer.hpp       # SPSC Lock-Free (ASharedMemory_create + mmap)
│   ├── session_jni.cpp              # Puente productor (:session -> RingBuffer)
│   ├── consumer_jni.cpp             # Puente consumidor (RingBuffer -> MediaCodec / Oboe)
│   └── third_party/                 # libuxplay.a, openssl, libplist, llhttp, ffmpeg (solo audio)
└── java/com/tvplayair/receiver/
    ├── service/TvPlaySessionService.kt
    ├── network/MdnsAdvertiser.kt
    ├── engine/
    │   ├── MirrorDecoder.kt         # MediaCodec + Caché SPS/PPS + Compuerta IDR
    │   └── WebVideoPlayer.kt        # Media3 ExoPlayer (4K HDR + Safari UA)
    ├── control/
    │   └── DacpController.kt
    └── ui/
        ├── MainTvActivity.kt        # Doble SurfaceView + KeyEvents + DeathRecipient
        └── TvOverlayMenu.kt         # Compose for TV
```

## 6. Hoja de Ruta y Criterios de Aceptación

| Hito | Entregable Técnico | Criterio de Aceptación (Verificable en Hardware) |
|------|----------------------|-----------------------------------------------|
| **Hito 1: Tubería Base** | Proceso `:session` + SharedRingBuffer + MirrorDecoder (1 SurfaceView) + Oboe. | Conexión estable desde iOS en 4K/HEVC a 60fps durante 30 min continuos sin crash, sin fuga de memoria y con audio PCM sincronizado. Prueba de estrés matando `:session` (`adb shell am crash`) con recuperación automática de la UI. |
| **Hito 2: Salto Web 4K HDR** | Segundo SurfaceView + WebVideoPlayer (ExoPlayer) + liberación de MediaCodec + compuerta IDR. | Maximizar un video web en Safari dispara ExoPlayer en máxima resolución/HDR sin saturar la VPU. Al salir de pantalla completa, el espejo regresa limpio sin pantalla verde ni congelamientos. |
| **Hito 3: Control y UI v1.0** | Inicio dedicado opcional, Ahora suena, DACP y ajustes de Compose TV. | Arrancar en Inicio en un equipo compatible, iniciar audio desde el iPhone, cambiar de app y avanzar con el mando. Confirmar que la carátula anterior desaparece, que una respuesta atrasada no la recupera y que video y espejo ocupan la pantalla sin pasar por Ajustes. |
| **v2.0 (Post-estabilidad)** | GLSurfaceView (PiP/Split con espejo a 1080p), USB CDC-NCM y evaluación de motor Rust. | Solo se inicia tras cerrar los 3 hitos anteriores sin regresiones. |
