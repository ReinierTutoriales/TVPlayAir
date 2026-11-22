# Política obligatoria de actualización tecnológica — TVPlayAir

Fecha: 2026-11-21. Política permanente del proyecto.

## Regla

En cada cambio de implementación o publicación se revisan **todas** las versiones directas y transitivas: Gradle, AGP, Kotlin, Android SDK/NDK/CMake, Compose BOM, Activity, TV Material, Media3, Oboe, BouncyCastle, librerías nativas y herramientas CI. No mantener versiones antiguas por comodidad ni solicitar autorización reiterada para actualizar. Evaluar también beta/RC/alpha cuando exista una mejora pertinente para continuidad multimedia, seguridad o rendimiento.

**Última disponible no significa sustituir una versión estable por un snapshot no publicado.** Seleccionar la versión publicada y verificable más reciente en el canal decidido, documentar el canal, requisitos, compatibilidad y resultados. Preferir estable cuando el prerelease no aporte beneficio comprobable; no inventar versiones ni checksums.

## Procedimiento obligatorio

1. Leer versiones reales de catálogos, wrapper, build scripts, lockfiles, CI y submódulos nativos.
2. Consultar fuentes oficiales (Google AndroidX/Media3/AGP, JetBrains Kotlin, Gradle, Google Oboe, proveedores nativos); registrar versión, fecha, enlace y canal.
3. Actualizar por grupos compatibles (toolchain, SDK/Compose, multimedia, seguridad), migrar APIs eliminadas y corregir deprecaciones.
4. Ejecutar build, lint, tests JVM, instrumentadas y tests de transición multimedia; medir regresiones.
5. No declarar actualización completa si alguna versión sigue pendiente, el build falla o la compatibilidad no se ha demostrado.
6. Revisar dependencias transitivas y CVEs, generar evidencia CI y registrar excepciones con fecha de revisión.

## Estado verificado 2026-11-21

- Gradle: versión estable oficial **9.9.1**, SHA-256 `d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228`; wrapper corregido desde referencia no estable 9.9.2.
- Compose: BOM estable **2026.11.01**; requiere Kotlin >= 2.2 y `compileSdk 37.1` (https://developer.android.com/blog/posts/jetpack-compose-november-2026-release). Actualmente proyecto BOM 2026.06.01 y compileSdk 36: **migración pendiente, debe ser atómica**.
- Media3: 1.11.1 estable en catálogo; comprobar periódicamente pre-releases contra https://developer.android.com/jetpack/androidx/releases/media3 .
- AGP 9.5.2, Kotlin 2.4.21, Activity Compose 1.13.0, TV Material 1.1.0, Oboe 1.11.0, BouncyCastle 1.87: **actualidad no certificada**; consultar versiones oficiales antes de cambiar.
- NDK 30.0.16248370 y CMake 4.1.2: verificar compatibilidad con AGP y CI.

## Condición de cierre

Una versión solo se considera actualizada cuando el inventario completo, la compilación, las pruebas y las excepciones están documentados. Este documento es una política, no una afirmación de que la migración completa haya concluido.
