# TVPlayAir

Receptor AirPlay independiente, libre y modular para Android TV.

Entrega calidad nativa 4K/HEVC/HDR, transición limpia entre espejo de pantalla y reproducción directa de video web, audio estable y control desde el mando de la TV, respetando las limitaciones reales de hardware (VPU, Binder, fragmentación Bluetooth).

## Documentación

- [Especificación de Ingeniería v1.0](docs/ENGINEERING_SPEC_v1.0.md) — Arquitectura definitiva, contratos IPC, estructura del repositorio y hoja de ruta.

## Alcance v1.0

| Incluido | Excluido (v2.0) |
|----------|-----------------|
| Proceso aislado `:session` + SharedMemory SPSC | Reemplazo del motor C por Rust/Kotlin puro |
| Espejo 4K/HEVC (MediaCodec Low-Latency) | GLSurfaceView / OpenGL ES (PiP y Split-Screen) |
| Video web directo 4K HDR (Media3 ExoPlayer) | Mezclador multi-bus con ducking |
| Audio PCM 48 kHz (FFmpeg → Oboe) + offset ± ms | Reloj PTP multiroom |
| DACP obligatorio + Bluetooth HID opcional | USB CDC-NCM |
| | Emparejamiento HomeKit persistente |

## Arquitectura (3 capas)

1. **Motor de Sesión** (`:session`) — Protocolo + mDNS + audio PCM. Control por AIDL, datos por SharedMemory.
2. **Motor de Presentación** — Dos SurfaceView nativos (MediaCodec + ExoPlayer) + Oboe. Caché SPS/PPS + compuerta IDR.
3. **Capa de Interacción** — Compose for TV + DACP + HID opcional.

## Requisitos

- minSdk 29, targetSdk 35
- NDK r27+, CMake 3.22+
- Kotlin DSL, Jetpack Compose for TV, Media3, Oboe
