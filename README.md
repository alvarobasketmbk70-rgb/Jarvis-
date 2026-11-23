# J.A.R.V.I.S. — asistente de voz para Android

**Descargar el APK:** https://github.com/alvarobasketmbk70-rgb/Jarvis-/releases/download/ultima/Jarvis.apk (se actualiza solo con cada cambio)

App nativa en Kotlin, pensada para el Xiaomi Redmi Note 15 4G (HyperOS / Android 14–15), pero válida para cualquier Android 8 o superior.

## Qué hace

| Pídale… | Ejemplos |
|---|---|
| Música (Spotify u otra app) | «pausa la música», «siguiente canción», «anterior», «reanuda», «pon Queen en Spotify», «ponme música de Rosalía» |
| Abrir apps | «abre Instagram», «abre la cámara», «abre ajustes» |
| Llamadas | «llama a mamá», «llama al 600123456» |
| WhatsApp / SMS | «manda un WhatsApp a Juan diciendo que llego tarde», «dile a Ana que ya voy», «manda un SMS a Pedro que me llame» |
| Controlar otras apps | «pulsa en Me gusta», «baja», «sube», «desliza a la izquierda», «atrás», «inicio», «escribe hola qué tal», «lee la pantalla», «haz una captura», «abre las notificaciones» |
| Reloj | «pon una alarma a las 7:30», «despiértame a las 8 y media», «temporizador de 10 minutos» |
| Móvil | «enciende la linterna», «sube el volumen», «volumen al 50», «cuánta batería queda», «qué hora es», «wifi» |
| Buscar e ir | «busca recetas de paella», «pon tutoriales de guitarra en YouTube», «llévame a la Puerta del Sol», «busca una farmacia cerca» |
| Conversar | Cualquier otra cosa: «cuéntame un chiste», «¿qué me pongo si hace frío?»… (requiere clave de IA) |

Si a una orden le falta algo, Jarvis pregunta («¿Qué le digo a Juan?»). Diga «cancela» para dejarlo, «cállate» para cortarle y «repite» para que lo repita.

Con la clave de IA puesta, también entiende órdenes dichas de forma libre («¿me avisas en un cuarto de hora?», «quiero oír algo de jazz») y las convierte en acciones.

## 1. Conseguir el APK (sin ordenador)

1. Cree una cuenta gratuita en github.com y un repositorio nuevo (puede ser privado).
2. Suba todo el contenido de esta carpeta (incluida la carpeta oculta `.github`). Desde el móvil: «Add file → Upload files».
3. Vaya a la pestaña **Actions**. La compilación arranca sola (o pulse «Compilar APK de Jarvis → Run workflow»). Tarda unos 5 minutos.
4. Abra la ejecución terminada y descargue **Jarvis-APK** (un .zip con `app-debug.apk` dentro).
5. Descomprímalo en el móvil e instale el APK (permita instalar desde el gestor de archivos o el navegador).

Con ordenador: abra la carpeta en Android Studio y pulse ▶ con el móvil conectado.

## 2. Configuración en el Xiaomi (5 minutos, una sola vez)

Abra Jarvis, acepte los permisos (micrófono, contactos, teléfono y notificaciones) y entre en **⚙**:

1. **Permitir ajustes restringidos**: al instalar fuera de la tienda, Android bloquea la accesibilidad. Botón 1 → menú ⋮ arriba a la derecha → «Permitir ajustes restringidos». Aproveche para activar **Inicio automático**.
2. **Accesibilidad**: botón 2 → busque «Jarvis» → actívelo. Sin esto no puede pulsar botones, desplazarse, leer la pantalla ni enviar WhatsApp solo.
3. **Mostrar sobre otras apps** y 4. **Otros permisos Xiaomi → «Mostrar ventanas emergentes mientras se ejecuta en segundo plano»**: necesarios para que el modo manos libres pueda abrir apps.
4. **Batería → Sin restricciones**: evita que HyperOS cierre a Jarvis.
5. **Asistente predeterminado**: botón 6 → «Asistente digital» → Jarvis. Así, **manteniendo pulsado el botón de encendido** se abre Jarvis escuchando, como Siri. (En HyperOS puede que tenga que activar esa función en Ajustes → Funciones adicionales → Botón de encendido.)
6. **Clave de IA (opcional, gratis)**: entre en aistudio.google.com/apikey con su cuenta de Google, cree una clave y péguela en ⚙. También vale una clave de Claude (`sk-ant-…`).

Extra: despliegue los ajustes rápidos → lápiz → arrastre el botón **Jarvis** para tenerlo a un toque.

## Formas de llamar a Jarvis

- Pulsación larga del botón de encendido (tras el paso 5).
- Botón de ajustes rápidos.
- Abriendo la app (le saluda y escucha).
- **Manos libres «Jarvis…»** (⚙ → interruptor experimental): deja el micrófono escuchando en segundo plano con una notificación fija. Diga «Jarvis, pausa la música». Gasta batería y datos y el reconocedor de Google puede pitar; úselo cuando lo necesite (conduciendo, cocinando…).

## Límites que impone Android

- Ninguna app que no sea de Google puede tener una palabra de activación de bajo consumo como «Ok Google». El modo manos libres es una aproximación.
- Las apps no pueden encender/apagar wifi o bluetooth directamente: Jarvis abre el panel para que usted lo toque.
- «Pon X en Spotify» usa la función de búsqueda y reproducción de Spotify; si su versión no la admite, abre la búsqueda.
- El envío automático de WhatsApp pulsa el botón «Enviar» por accesibilidad. Si WhatsApp cambia su diseño, Jarvis le dejará el mensaje escrito para que lo envíe usted.
- La voz depende del motor de texto a voz del móvil. En ⚙ puede elegir otra voz española (las de Google suenan mejor: instale «Servicios de voz de Google» si no la tiene).

## Privacidad

El reconocimiento de voz lo hace el servicio de Google del propio móvil. Solo lo que no son órdenes directas se envía a la IA que configure (Gemini o Claude). La pantalla solo se lee cuando usted lo pide.

## Estructura del código

```
app/src/main/java/com/jarvis/asistente/
  MainActivity.kt          Pantalla con el orbe, chat y micrófono
  Commands.kt              Interpretación de órdenes en español (sin internet)
  Brain.kt                 Decide entre orden local o IA; cliente Gemini/Claude
  ActionExecutor.kt        Ejecuta las acciones (música, llamadas, WhatsApp, alarmas…)
  JarvisAccessibilityService.kt  Tocar, desplazar, escribir, leer, enviar WhatsApp
  Voice.kt                 Texto a voz y reconocedor
  WakeWordService.kt       Modo manos libres «Jarvis…»
  JarvisTileService.kt     Botón de ajustes rápidos
  voice/VoiceAssistant.kt  Integración como asistente del sistema
  SettingsActivity.kt      Ajustes y accesos directos a permisos
```

Para añadir una orden nueva: añada la expresión en `Commands.kt`, su acción en `ActionExecutor.kt` y descríbala en el texto de sistema de `Brain.kt` para que la IA también la use.
