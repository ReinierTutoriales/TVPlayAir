# TVPlay v1.0 — Especificación de Ingeniería

## 1. Objetivo y Alcance

**Objetivo**: Construir un receptor AirPlay independiente, libre y modular para Android TV que entregue calidad nativa 4K/HEVC/HDR, transición limpia entre espejo de pantalla y reproducción directa de video web, audio estable sin cortes y control desde el mando de la TV, respetando las limitaciones reales de hardware (VPU, Binder, fragmentación Bluetooth).

La experiencia objetivo es conectar el iPhone una vez y manejar la sesión desde la TV. En espejo, cambiar de app o girar el iPhone debe mantener la conexión. En video directo, la TV reproduce el contenido compatible en ExoPlayer mientras el iPhone puede pasar a otra app. Abrir los ajustes o cambiar el tema de la TV debe dejar la sesión y la reproducción activas. Estas transiciones se verifican de extremo a extremo con iPhone, TV y mando.

| Incluido en v1.0 (Producción) | Excluido de v1.0 (Diferido a v2.0) |
|----------------------------------|-------------------------------------|
| Proceso aislado `:session` + SharedMemory SPSC | Reemplazo del motor C por Rust/Kotlin puro |
| Espejo 4K/HEVC (MediaCodec Low-Latency) | GLSurfaceView / OpenGL ES (PiP y Split-Screen) |
| Video Web directo 4K HDR (Media3 ExoPlayer) | Mezclador multi-bus con ducking (multi-cliente) |
| Audio PCM 48 kHz (FFmpeg → Google Oboe) + offset ± ms | Reloj PTP (IEEE 802.1AS) multiroom |
| Control remoto bidireccional DACP (obligatorio) | Puente cableado USB CDC-NCM |
| Control Bluetooth HID (opcional / best-effort) | Emparejamiento HomeKit persistente (Ed25519) |

## 2. Arquitectura de 3 Capas y Fronteras de Proceso

El sistema separa estrictamente la red/protocolo del renderizado y la UI mediante dos procesos Android independientes:

```
+-------------------------------------------------------------------+
| PROCESO AISLADO (:session) — Resiliencia ante crashes nativos     |
|                                                                   |
|  [MdnsAdvertiser]  -->  [Núcleo C: raop + playfair]               |
|  (_airplay / _raop)      ├── Video NALs (H.264/HEVC)              |
|                          ├── Audio (FFmpeg -> PCM 48kHz)          |
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
|  └── BT HID (Opcional D-Pad)    ├── SurfaceView B: ExoPlayer      |
|                                 │   (4K HDR + User-Agent Safari)  |
|                                 └── Audio: Oboe + AudioFocus      |
+-------------------------------------------------------------------+
```

## 3. Especificación Técnica por Capa

### Capa 1: Motor de Sesión (`android:process=":session"`)

- **Protocolo**: Núcleo C mínimo (raop + playfair, sin GStreamer) encapsulado detrás de un puente JNI propio. Si el código nativo sufre un crash o fuga de memoria, Android reinicia `:session` sin tumbar la UI ni ExoPlayer.
- **Descubrimiento mDNS** (`MdnsAdvertiser`): Publica `_airplay._tcp` (puerto 7000) y `_raop._tcp` (puerto 5000) con `features=0x5A7FFFF7,0x1E`, `flags=0x44`, `model=AppleTV11,1`, `overscanned=0` y WifiLock (`WIFI_MODE_FULL_LOW_LATENCY`).
- **Decodificación de Audio en `:session`**: FFmpeg (`libavcodec`) normaliza AAC-ELD, AAC-LC y ALAC a PCM 16-bit / 48 kHz estéreo antes de escribir en memoria compartida.
- **Asignación de Memoria**: Creada 100% en C++ con `ASharedMemory_create()` (`<android/sharedmem.h>`), sin reflexión en Kotlin. Los descriptores (fd) se entregan a la UI vía `ParcelFileDescriptor.adoptFd(fd)` por AIDL.

### Capa 2: Motor de Presentación (Proceso Principal)

- **Video (Cero OpenGL en v1.0)**: Dos `SurfaceView` nativos superpuestos gestionados por SurfaceFlinger. Garantiza paso directo al Hardware Composer y preserva metadatos HDR10 / HLG / Dolby Vision en ExoPlayer.
- **Gestión de VPU (Límite de Hardware)**: Los SoCs de Android TV (Amlogic/MediaTek) no soportan doble decodificación 4K HEVC simultánea.
  - Al entrar `POST /play` (video web maximizado), ExoPlayer prepara el stream con User-Agent de Safari iOS y `setForceHighestSupportedBitrate(true)`.
  - Se libera MediaCodec del espejo antes de preparar ExoPlayer. `onRenderedFirstFrame` registra cuándo ExoPlayer muestra imagen.
- **Retorno limpio al Espejo (Caché SPS/PPS + Compuerta IDR)**: Al detener ExoPlayer, se recrea MediaCodec (`KEY_LOW_LATENCY = 1`), se inyectan los últimos headers SPS/PPS/VPS cacheados y se descartan todos los paquetes entrantes hasta recibir el primer frame IDR limpio (evita corrupción/pantalla verde).
- **Audio**: Google Oboe (C++) lee directamente del SharedRingBuffer de audio con ajuste manual de sincronización (-200 ms a +200 ms). AudioFocus nativo arbitra la transición con ExoPlayer.

### Capa 3: Capa de Interacción (Proceso Principal)

- **DACP (Obligatorio)**: Captura DACP-ID y Active-Remote al conectar. Traduce las teclas multimedia del mando a comandos HTTP `GET /ctrl-int/1/...` hacia el dispositivo Apple. El video directo informa el progreso en `GET /playback-info` y envía cambios de estado a la misma sesión AirPlay por `/reverse`.
- **Bluetooth HID (Opcional)**: Implementado con `BluetoothHidDevice` (perfil teclado estándar). Al iniciar la app, verifica soporte del stack Bluetooth del televisor; si falla o no existe, se desactiva silenciosamente sin afectar el resto del sistema.
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

- Requisitos de compilación: `minSdk = 29`, `targetSdk = 35`, Kotlin DSL, NDK r27+, CMake 3.22+, `buildFeatures { compose = true; prefab = true }`, `aidl = true`.
- Librerías NDK obligatorias: `android` (para `ASharedMemory_create`), `log`, `oboe::oboe`.

```
TVPlay/app/src/main/
├── aidl/com/tvplay/receiver/ipc/
│   ├── ITvPlaySession.aidl
│   └── ITvPlayCallback.aidl
├── cpp/
│   ├── CMakeLists.txt
│   ├── shared_ring_buffer.hpp       # SPSC Lock-Free (ASharedMemory_create + mmap)
│   ├── session_jni.cpp              # Puente productor (:session -> RingBuffer)
│   ├── consumer_jni.cpp             # Puente consumidor (RingBuffer -> MediaCodec / Oboe)
│   └── third_party/                 # libuxplay.a, openssl, libplist, llhttp, ffmpeg (solo audio)
└── java/com/tvplay/receiver/
    ├── service/TvPlaySessionService.kt
    ├── network/MdnsAdvertiser.kt
    ├── engine/
    │   ├── MirrorDecoder.kt         # MediaCodec + Caché SPS/PPS + Compuerta IDR
    │   └── WebVideoPlayer.kt        # Media3 ExoPlayer (4K HDR + Safari UA)
    ├── control/
    │   ├── DacpController.kt
    │   └── TvRemoteHidBridge.kt     # Best-effort BluetoothHidDevice
    └── ui/
        ├── MainTvActivity.kt        # Doble SurfaceView + KeyEvents + DeathRecipient
        └── TvOverlayMenu.kt         # Compose for TV
```

## 6. Hoja de Ruta y Criterios de Aceptación

| Hito | Entregable Técnico | Criterio de Aceptación (Verificable en Hardware) |
|------|----------------------|-----------------------------------------------|
| **Hito 1: Tubería Base** | Proceso `:session` + SharedRingBuffer + MirrorDecoder (1 SurfaceView) + Oboe. | Conexión estable desde iOS en 4K/HEVC a 60fps durante 30 min continuos sin crash, sin fuga de memoria y con audio PCM sincronizado. Prueba de estrés matando `:session` (`adb shell am crash`) con recuperación automática de la UI. |
| **Hito 2: Salto Web 4K HDR** | Segundo SurfaceView + WebVideoPlayer (ExoPlayer) + liberación de MediaCodec + compuerta IDR. | Maximizar un video web en Safari dispara ExoPlayer en máxima resolución/HDR sin saturar la VPU. Al salir de pantalla completa, el espejo regresa limpio sin pantalla verde ni congelamientos. |
| **Hito 3: Control y UI v1.0** | DacpController + TvRemoteHidBridge (opcional) + TvOverlayMenu (Compose TV). | Play/Pausa/Seek funcionan desde el mando en cualquier Android TV. Si el TV soporta BT HID, el D-Pad navega por Safari. El menú permite ajustar offset de audio (± ms) en caliente. |
| **v2.0 (Post-estabilidad)** | GLSurfaceView (PiP/Split con espejo a 1080p), USB CDC-NCM y evaluación de motor Rust. | Solo se inicia tras cerrar los 3 hitos anteriores sin regresiones. |
