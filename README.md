# TVPlayAir

Receptor AirPlay independiente, libre y modular para Android TV.

Entrega calidad nativa 4K/HEVC/HDR, transición limpia entre espejo de pantalla y reproducción directa de video web, audio estable y control desde el mando de la TV, respetando las limitaciones reales de hardware (VPU y Binder).

## Instalar o desinstalar desde Windows

1. Clona el repositorio o descarga su ZIP y extráelo.
2. Descarga las [SDK Platform-Tools para Windows de Google](https://developer.android.com/tools/releases/platform-tools). Extrae la carpeta `platform-tools` completa en la raíz, junto a [TVPlayAir.ps1](TVPlayAir.ps1). Debe contener `adb.exe`, `AdbWinApi.dll` y `AdbWinUsbApi.dll`.
3. Pon el APK de TVPlayAir junto al script o indica su ruta en el menú. El APK solo se necesita para instalar o actualizar.
4. Conecta el PC y el TV a la misma red, activa las opciones de desarrollo y habilita la depuración. El script pide la IP y admite ADB por puerto o emparejamiento inalámbrico.

Desde Windows PowerShell 5.1 o posterior, ejecuta:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\TVPlayAir.ps1
```

También puedes usar la ruta completa entre comillas desde cualquier directorio. El script busca las Platform-Tools junto a su propio archivo y comprueba que ADB se pueda ejecutar antes de pedir la IP. Usa `-CheckDependencies` para ejecutar solo esa comprobación, sin conectarte a ningún dispositivo.

Android conserva los datos al actualizar con un APK de versión compatible y firmado con la misma clave. Los APK de depuración de GitHub Actions pueden tener firmas distintas entre ejecuciones. Para instalaciones que deban conservar los datos entre versiones, usa el [APK firmado](#generar-un-apk-firmado). La desinstalación pide confirmación antes de borrar los datos del perfil activo. Puedes conceder desde el menú los permisos declarados de notificaciones y de apertura sobre otras apps.

En algunos televisores, «Depuración USB» por sí sola no habilita ADB por red. Usa «Depuración inalámbrica» si el firmware ofrece emparejamiento con código. En el modo TCP/IP clásico, usa el puerto indicado por el televisor o actívalo primero mediante USB autorizado si tu equipo lo requiere. [Guía oficial de ADB](https://developer.android.com/tools/adb)

## Generar un APK firmado

El workflow **Signed APK** se ejecuta manualmente desde `main` y entrega un APK como artefacto de la ejecución. Indica un `versionCode` mayor que el de la última versión distribuida y su `versionName`. El nombre de paquete se conserva.

Configura el entorno `apk-signing` de GitHub Actions con acceso limitado a `main`. Usa siempre el almacén de claves de distribución a cargo del propietario y guarda una copia fuera del repositorio.

| Configuración del entorno | Valor |
|---|---|
| Secreto `TVPLAYAIR_KEYSTORE_BASE64` | Almacén de claves de distribución codificado en Base64 |
| Secreto `TVPLAYAIR_KEYSTORE_PASSWORD` | Contraseña del almacén |
| Secreto `TVPLAYAIR_KEY_PASSWORD` | Contraseña de la clave |
| Variable `TVPLAYAIR_KEY_ALIAS` | Alias de la clave |
| Variable `TVPLAYAIR_SIGNER_SHA256` | SHA-256 del certificado de distribución, 64 dígitos hexadecimales sin separadores |

La ejecución verifica la firma y compara el SHA-256 del certificado antes de subir el APK como artefacto. Si el televisor tiene un APK de depuración con otro certificado, el cambio a la primera versión firmada requiere reinstalar TVPlayAir. La desinstalación borra los datos del perfil seleccionado.

## Documentación

- **[Estado actual y siguiente paso](docs/CONTINUITY_EXECUTION_STATUS.md)** — Registro vivo de decisiones, evidencias, avances, riesgos y ruta de ejecución. Consultar primero antes de modificar la arquitectura.

- [Arquitectura de continuidad multimedia y auditoría 2026](docs/TVPLAYAIR_CONTINUITY_ARCHITECTURE_2026.md) — Objetivo de experiencia tipo CarPlay para TV, arbitraje de sesiones, pruebas y tecnologías oficiales.
- [Especificación de Ingeniería v1.0](docs/ENGINEERING_SPEC_v1.0.md) — Arquitectura definitiva, contratos IPC, estructura del repositorio y hoja de ruta.

## Alcance v1.0

| Incluido | Excluido (v2.0) |
|----------|-----------------|
| Proceso aislado `:session` + SharedMemory SPSC | Reemplazo del motor C por Rust/Kotlin puro |
| Espejo 4K/HEVC (MediaCodec Low-Latency) | GLSurfaceView / OpenGL ES (PiP y Split-Screen) |
| Video web directo 4K HDR (Media3 ExoPlayer) | Mezclador multi-bus con ducking |
| Audio PCM 48 kHz (ALAC nativo / AAC MediaCodec → Oboe) + offset ± ms | Reloj PTP multiroom |
| DACP obligatorio | USB CDC-NCM |
| | Emparejamiento HomeKit persistente |

## Arquitectura (3 capas)

1. **Motor de Sesión** (`:session`) — Protocolo + mDNS + audio PCM. Control por AIDL, datos por SharedMemory.
2. **Motor de Presentación** — Dos SurfaceView nativos (MediaCodec + ExoPlayer) + Oboe. Caché SPS/PPS + compuerta IDR.
3. **Capa de Interacción** — Compose for TV y DACP.

## Requisitos

- minSdk 29, targetSdk 35
- NDK 30.0.16248370, CMake 4.1.2
- Kotlin DSL, Jetpack Compose for TV, Media3, Oboe
