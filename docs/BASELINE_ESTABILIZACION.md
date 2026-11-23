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

## Rutas de reproducción

| Lo que debe aparecer en el TV | Ruta y responsable | Qué hace falta para darla por operativa |
|---|---|---|
| App oficial del proveedor, con control desde iPhone | Usar la función Cast del proveedor cuando la ofrezca. El proveedor elige el receptor y gestiona su sesión. | Probar el emisor y destino compatibles. Confirmar qué app o receptor se abrió y qué controles siguen disponibles. |
| Un contenido concreto en la app oficial de Android TV | Abrir un enlace de contenido publicado por el proveedor y comprobar que lo atiende su app. Un Android App Link verificado requiere la asociación entre dominio y app del proveedor. | Confirmar app instalada, enlace, paquete resuelto e ítem abierto en el TV. El enlace por sí solo abre el contenido. Cualquier control desde iPhone requiere su propia integración. |
| Audio o vídeo en TVPlayAir desde una app con AirPlay | El emisor selecciona TVPlayAir y entrega audio o vídeo directo según la ruta negociada. | Verificar primer audio o frame, metadatos y comandos para la sesión activa. Las órdenes de pausa o parada del emisor se aplican a esa sesión. |
| La pantalla del iPhone en el TV | Duplicación seleccionada en iOS. TVPlayAir recibe y presenta los flujos negociados. | Verificar códec, dimensiones, audio negociado, primer frame y salida al cambiar a otro modo. |

Una URL multimedia por sí sola sirve para evaluar reproducción directa. Para abrir un ítem en la app oficial hace falta un enlace o identificador que el proveedor documente para esa acción. La interfaz y el control de una app abierta por separado quedan bajo su propia integración.

## Qué mostró la captura y qué sigue abierto

- **Run #191, Android TV.** Tras el cambio directo a otro ítem, el emisor envió `/stop` seguido de `/play`. El vídeo se preparó y los manifiestos observados respondieron. La captura posterior del paso de duplicación a vídeo muestra que Oboe se cerró antes de que ExoPlayer entrara en preparación. El log del receptor por sí solo no identifica cómo sonó el iPhone.
- **Audio previo durante duplicación.** `ec94447` añade la retirada de RAOP anterior, conserva audio asociado a la misma sesión y procesa las descripciones de audio y vídeo de un `SETUP` combinado. La prueba en un destino real sigue pendiente.
- **Capacidad del decoder.** El código intenta un decoder por MIME cuando la búsqueda por formato falla. Tratar este camino como riesgo de anuncio y selección para retrato de alta resolución. Antes de cambiar perfiles o anunciar límites, cruzar la configuración codificada y las capacidades que publica Android. Registrar si falla al configurar, al recibir la imagen clave o al presentar.
- **Soporte del emisor.** No deducir de un log ADB la salida del altavoz, el soporte de reproducción en segundo plano ni la política de una app de iOS. Observaciones distintas requieren trazas distintas.

## Auditoría de las dos vías de AirPlay

| Recorrido | Contrato comprobado en código |
|---|---|
| Vídeo directo A → B | Una imagen, una orden de parada o un timeout de A no modifica B. Una actualización tardía de la ruta anterior no vuelve a activarla. |
| Vídeo directo → duplicación | Una conexión nueva y configurada de duplicación toma el TV. El receptor invalida el vídeo anterior, cancela FCUP y cierra su canal inverso después del evento de parada. |
| Duplicación → vídeo directo | Una solicitud explícita de vídeo cierra el flujo TCP anterior de duplicación. Se suspende Oboe y se libera MediaCodec antes de preparar Media3. El vídeo directo se descubre al confirmar una imagen del ítem activo. |
| Audio AirPlay y audio asociado a duplicación | El cambio de generación descarta tanto los paquetes antiguos del ring como la porción de PCM anterior que quedaba en Oboe. El cambio de generación de audio no altera la generación de vídeo de duplicación. |
| Fin de duplicación → audio | El cierre TCP retira la proyección. Una sesión RAOP vigente conserva audio y carátula. Se respeta la orden del emisor de retirar la carátula. |

La configuración SPS/PPS/VPS de duplicación viaja por AIDL con una generación de vídeo propia. El ring conserva el transporte de los frames y puede llenarse sin impedir que el destino reciba una configuración nueva. La prueba física sigue siendo necesaria para confirmar imagen y sonido con el iPhone, incluidos reproducción con el móvil bloqueado, cambio de orientación, silencio o eco entre el altavoz del teléfono y el TV, y tiempos hasta la primera imagen y el primer audio.

## Captura del 22 de noviembre de 2026

En el onn. 4K, los dos inicios de duplicación a 884×1920 presentaron una primera imagen. En la segunda sesión, desde las 11:51:46.267, Realtek informó repetidamente que sus 12 buffers de salida estaban ocupados, con cinco trabajos pendientes en el primer informe de reintentos. El canal RTSP seguía contestando al control a las 11:52:12 y RAOP seguía produciendo PCM a las 11:52:13.898. La captura no registra la hora en que se bloqueó el iPhone.

El decoder ahora intenta reconstruirse una vez si acumula al menos cuatro frames sin entregar salida durante dos segundos. Retoma desde un fotograma clave con la configuración vigente y vuelve a habilitar la recuperación cuando entrega salida. El log de TCP distingue fin de datos del emisor, timeout y lectura truncada. Se conserva el timeout de red existente.

En esta captura se observó duplicación con audio AAC-ELD. No se observó una solicitud de vídeo directo ni una medición del altavoz del iPhone. La reproducción con pantalla bloqueada se prueba por separado con AirPlay desde el reproductor y con duplicación, registrando la hora del bloqueo y ambas salidas. [Rutas AirPlay](https://support.apple.com/en-us/102661) · [Reproducción en segundo plano de la app emisora](https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback)

## Una sola pasada de verificación por candidato

1. Fijar un único SHA de APK, el modelo y SoC del destino, versión de Android, versión de iOS y app emisora. Marcar cada acción con su hora y registrar ambas salidas de audio.
2. Ejecutar de una vez `./gradlew :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. La CI ejecuta también las pruebas nativas de host. El recorrido `MediaContinuityBaselineTest` forma parte de la suite JVM.
3. En una sesión de captura continua, ejecutar los recorridos de la tabla. Alternar escenarios de prueba en el mismo orden con la misma versión. Para duplicación usar horizontal y vertical, incluyendo el perfil de alta resolución que falló. Para audio grabar un estímulo común que permita identificar eco y sincronía en ambas salidas.
4. Correlacionar por sesión, medio y generación. Los tiempos de solicitud a primer frame y a primer PCM se informan por separado. Normalizar la captura para quitar URLs firmadas, credenciales y material criptográfico antes de agregar una regresión.
5. Agrupar en el mismo candidato las correcciones demostradas que comparten contrato. Repetir la suite automática completa al final. Si queda un caso abierto, registrar el primer evento ausente o incorrecto y la evidencia necesaria para resolverlo.

La JVM comprueba los contratos entre componentes mediante dobles de motor. Android CI comprueba compilación, pruebas y empaquetado. La validación física de salida de audio, decodificación y sincronía se registra en [HARDWARE_VALIDATION_v1.0.md](HARDWARE_VALIDATION_v1.0.md).

## Referencias oficiales

- AndroidX Media3 `1.11.1` es la estable publicada en la tabla consultada. `1.12.0-beta01` queda como candidata cuando exista una mejora aplicable y verificable. [Notas de versiones](https://developer.android.com/jetpack/androidx/releases/media3)
- [Foco de audio de Android](https://developer.android.com/media/optimize/audio-focus) · [Oboe](https://developer.android.com/games/sdk/oboe/low-latency-audio) · [AAudio y MMAP](https://source.android.com/docs/core/audio/aaudio)
- [Capacidades de MediaCodec](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities) · [Métricas de Media3](https://developer.android.com/media/media3/exoplayer/analytics) · [AIDL](https://developer.android.com/develop/background-work/services/aidl)
- [Android App Links](https://developer.android.com/training/app-links) · [Enviar YouTube al TV](https://support.google.com/youtube/answer/7640706)
- [Selección de ruta AirPlay en iOS](https://developer.apple.com/documentation/avfoundation/supporting-airplay-in-your-app) · [Uso de AirPlay y duplicación](https://support.apple.com/en-us/102661)

### Diagnóstico local de cortes

```sh
adb logcat -v threadtime -d -s TVPlayAirDiag TVPlayAirOboe RaopAudioRtpReceiver
```

- Vídeo directo y FCUP comparten `id`. En errores del proxy, `request` enlaza la carga Media3 con la entrega FCUP: `QUEUED` indica que el evento estaba en cola, `WRITTEN` que se escribió al socket y `ACKNOWLEDGED` que se observó el acuse. `retired_load` identifica una carga local que recibió 410 al quedar retirada su reproducción.
- `load_summary` conserva el primer error y cuenta los fallos observados hasta completar esa carga, volver al estado de reproducción efectiva o cerrar el intento. `load_completed` indica éxito de la carga; `first_frame` confirma salida de imagen. Los eventos que Media3 no permite atribuir se registran sin asignarlos al medio activo por suposición.
- Duplicación usa `generation` para enlazar configuración y cierre TCP con errores y primer fotograma del decodificador. El cierre separa `rtsp_teardown` de `rtsp_peer_eof`, timeout y error de E/S. Tras `await_keyframe`, `codec_started` confirma que el decodificador arrancó. El siguiente `first_frame` confirma que volvió a haber imagen.
- `default_lost` registra lo que Android observó sobre la ruta de red predeterminada del televisor. Es contexto temporal, no prueba de que la conexión con el iPhone usara esa red. Los eventos de IPC muestran la vinculación y reconexión del servicio. Los eventos de RAOP identifican la generación de audio; Oboe conserva su registro de recuperación.
- Las líneas `TVPlayAirDiag` omiten IP, URL completa, tokens, claves y metadatos del contenido. Los demás tags de aplicación o Android pueden incluir datos de red.

### Auditoría de continuidad y transporte

La referencia del onn. 4K es el Run #212. Registró vídeo directo interrumpido por FCUP, un espejo sin recuperación de imagen confirmada y dos EOF del control RTSP. Las pruebas nativas reproducen el descarte anterior de un fotograma clave que llevaba parámetros del códec en el mismo paquete. El test de encuadre comprueba ahora solicitudes consecutivas, lectura fragmentada y cuerpos cortos o con longitud inválida.

| Capa | Acción y evidencia pendiente |
|---|---|
| HTTP AirPlay y canal de eventos | Encuadrar mensajes por cabecera y longitud conforme a RFC 9112. Los registros del Run #212 no vinculan una solicitud mal encuadrada con los EOF observados. |
| Duplicación y VPU | Conservar el fotograma clave junto con los parámetros del códec (referencia UxPlay). Medir cuánto tarda el primer fotograma después de `await_keyframe` y observar la recuperación en el decodificador Realtek. |
| Vídeo directo | Correlacionar cada 502/504 con el estado FCUP del emisor y el resultado de Media3. UxPlay documenta su ruta HLS para YouTube iOS, así que esa compatibilidad no prueba las demás apps. |
| Audio y reloj | Medir underruns, atraso al cambiar de ruta y sincronía. El receptor actual procesa RTP por secuencia y descarta mensajes de sincronía/retransmisión en el ingreso de audio. Shairport Sync es una referencia para estudiar reloj y búfer. La sincronía AirPlay 2 completa sigue sin verificar. |
| Descubrimiento, IPC y mando | Medir redescubrimiento tras Wi-Fi y colisión de nombres NSD. Incluir muerte del proceso de sesión, restablecimiento del Binder, retorno de superficie y foco D-pad en la prueba de continuidad. |

Referencias [HTTP/1.1 RFC 9112](https://www.rfc-editor.org/rfc/rfc9112.html) · [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) · [UxPlay](https://github.com/FDH2/UxPlay/releases) · [Shairport Sync](https://github.com/mikebrady/shairport-sync).
