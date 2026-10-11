# Roadmap

Qué construir después de la Fase 7 ([`docs/07-ia.md`](07-ia.md)) y en qué orden. Sale de comparar Libreta con las
libretas y apps parecidas (reMarkable, Supernote, Kindle Scribe, Boox, GoodNotes, Notability, Apple Notes y Samsung
Notes) en octubre de 2026 y de cruzarlo con lo que ya tenemos.

**Punto de partida.** Lo que casi ninguna de esas apps combina, y en lo que se apoya todo lo de abajo:

- Notas Markdown en el Nextcloud propio, con adjuntos sincronizados.
- Trazos con la hora de cada punto (`libreta.ink.json`).
- Selección con recuadro sobre la página.
- Un modelo con visión y herramientas (`gpt-6.1-sol`, `gpt-6-luna`) pagado con el plan de ChatGPT, desde la tablet.
- Dictado con Whisper en el servidor.
- Markdown formateado (Markwon).
- Una tablet dedicada en modo de una sola app.

**Restricciones que condicionan el diseño:** Android 4.4 (API 19), 1 GB de RAM y 64 MB de memoria por app, sin
navegador moderno; el uso del plan tiene límites y obliga a `store: false`, con lo que cada turno reenvía la
conversación; el servidor no tiene GPU.

## Orden

| Fase | Qué | Esfuerzo | Depende de |
|---|---|---|---|
| 8 | [Editar lo seleccionado](#fase-8-editar-lo-seleccionado) — **hecha**, ver [`08-seleccion.md`](08-seleccion.md) | S–M | — |
| 9 | [Zoom en el lienzo](#fase-9-zoom-en-el-lienzo) | M | — |
| 10 | [Vertical y horizontal](#fase-10-vertical-y-horizontal) | S–M | 9 (desplazarse por la página) |
| 11 | [Buscar en lo escrito a mano](#fase-11-buscar-en-lo-escrito-a-mano) | M | — |
| 12 | [Pregúntale a tu libreta](#fase-12-pregúntale-a-tu-libreta) | M | 11 |
| 13 | [Títulos, etiquetas e índice](#fase-13-títulos-etiquetas-e-índice) | M | 8 (selección), 11 (transcripción) |
| 14 | [Pasar a limpio una nota entera](#fase-14-pasar-a-limpio-una-nota-entera) | S | — |
| — | [Sin prioridad](#sin-prioridad) | | |

---

## Fase 8: editar lo seleccionado

**Hecha** (2026-10-12): [`docs/08-seleccion.md`](08-seleccion.md). Lo de abajo es el plan original.

Lo básico de cualquier libreta, y hoy la mayor carencia: con el recuadro solo se puede convertir o conversar.

**Qué hace.** Tras marcar el recuadro, además de «Convertir a texto» y «Conversar con IA»:

- **Mover:** arrastrar desde dentro del recuadro desplaza los trazos seleccionados.
- **Cambiar el tamaño:** un tirador en la esquina escala la selección, manteniendo la proporción.
- **Copiar y pegar:** también en otra página o en otra nota a mano.
- **Cambiar el color o el grosor** de lo seleccionado, con el panel de colores que ya existe.
- **Borrar** lo seleccionado.

**Diseño inicial.**

- Qué cuenta como seleccionado: los trazos con algún punto dentro de `InkRect`. Es la regla de `InkRect.hasInk`, pero
  por trazo. No se cortan trazos por el borde del recuadro: mover medio trazo no tiene sentido.
- `InkEditor` gana operaciones sobre un conjunto de trazos: `transform` (traslación y escala), `recolor`, `delete` e
  `insert`. Cada una es **un solo paso de deshacer**, igual que una pasada del borrador.
- Al transformar se reescriben las coordenadas de los puntos y no sus tiempos, que se conservan para el
  reconocimiento y para el audio sincronizado de [sin prioridad](#sin-prioridad).
- Portapapeles: los trazos en memoria del proceso. No hace falta el portapapeles del sistema, porque solo los entiende
  Libreta.
- Mientras se arrastra, `InkView` pinta la selección en un bitmap aparte y la página sin ella, para no redibujar todo
  en cada movimiento: la tablet lo notaría.

**Hecho cuando:** mover, escalar, copiar a otra página, recolorear y borrar funcionan y se deshacen de un paso;
tests de `InkEditor` en `:core`; probado con el lápiz en la tablet.

## Fase 9: zoom en el lienzo

Hoy la página está fija a la escala de la pantalla: cuando se acaba el sitio hay que crear otra página, y no se
puede ampliar una zona para escribir más pequeño, completar un hueco o retocar un detalle.

**Qué hace.**

- **Ampliar y reducir** con dos dedos (pellizco), de 1× (página entera, como ahora) a 4×.
- **Desplazarse** por la página ampliada arrastrando con dos dedos.
- Botones **−**, **+** y **1×** en la barra, porque con un lápiz capacitivo el pellizco no siempre es cómodo y
  así hay una vuelta segura a la página entera.
- Con la página ampliada se escribe, se borra y se selecciona igual que ahora; lo escrito queda más pequeño en la
  página, que es justo lo que da sitio para seguir en la misma página.

**Diseño inicial.**

- Los trazos siguen en unidades de página: el zoom es solo una transformación de la vista (`InkView`: escala y
  desplazamiento) y no cambia el formato de `libreta.ink.json`. Todas las conversiones entre pantalla y página
  (pluma, borrador, selección y lo que añada la fase 8) pasan por esa transformación.
- **Grosor:** un trazo hecho con la página ampliada se guarda con el grosor dividido por el zoom, para que en
  pantalla se vea igual que el resto mientras se escribe y, a 1×, quede proporcionado con la letra más pequeña.
  Ajustable si en la práctica se prefiere lo contrario.
- **Lápiz frente a gesto:** hoy el primer contacto dibuja. Si llega un segundo dedo en los primeros ~150 ms, o antes
  de que el trazo avance unos pocos milímetros, el trazo se descarta y se trata como pellizco o desplazamiento. Hay
  que calibrarlo con el lápiz AmberVec, como se hizo con la palma (`docs/06-escritura-a-mano.md`).
- **Rendimiento:** el bitmap de la vista sigue siendo del tamaño de la pantalla; al ampliar se redibuja solo la zona
  visible, con un recorte (`clipRect`) que descarta los trazos de fuera. Durante el pellizco se escala el bitmap ya
  dibujado y se redibuja nítido al soltar, para que la tablet no se atasque.
- **Imágenes para Nextcloud:** hoy se generan a 1 píxel por unidad. La letra escrita a 3× o 4× sería ilegible ahí,
  así que conviene renderizarlas a 2× (más peso por página: medirlo). La selección para la IA ya se renderiza a 2×.
- El zoom y la posición no se guardan con la nota: al abrirla se ve la página entera.

**Hecho cuando:** pellizcar y los botones amplían y desplazan sin trazos fantasma; se puede escribir con la página
ampliada y a 1× se ve proporcionado; la pluma, el borrador y la selección aciertan en el sitio a cualquier zoom; las
páginas siguen siendo legibles en Nextcloud; probado con el lápiz en la tablet.

## Fase 10: vertical y horizontal

**Cómo está hoy** (comprobado en la tablet el 2026-10-11, forzando la horizontal): la lista, el editor de texto y
los ajustes giran bien. **La nota a mano y el chat con la IA están fijados en vertical** (`screenOrientation="portrait"`
desde la Fase 6): con la tablet en horizontal se siguen viendo en vertical. La página de una nota a mano toma el tamaño
de la zona de escritura al crearla (vertical), y en horizontal cabría muy pequeña.

**Qué hace.**

- **Nota a mano en horizontal:** la página se muestra **ajustada al ancho** y se desplaza en vertical para llegar al
  resto (con el desplazamiento de la fase 9). Se escribe igual que en vertical; la página y sus trazos no cambian.
- **Páginas horizontales:** al añadir una página o crear una nota con la tablet en horizontal, se puede elegir que la
  página nueva sea horizontal (apaisada). Cada página guarda su tamaño, como ya guarda su papel.
- **Barra de herramientas en horizontal:** en una sola fila (herramientas y opciones juntas), porque sobra ancho y
  falta alto.
- **Chat con la IA:** gira libremente; la conversación no se pierde.

**Diseño inicial.**

- Quitar `screenOrientation="portrait"` y mantener `configChanges`: la actividad no se recrea al girar, así que la
  nota, el deshacer y la conversación siguen en memoria. Al cambiar el tamaño, `InkView` recalcula la escala y el
  desplazamiento (ajuste al ancho en horizontal, página entera en vertical) y redibuja.
- Las páginas ya tienen ancho y alto propios en `libreta.ink.json` (`InkPage.width`/`height`), así que una página
  apaisada no cambia el formato. Su imagen para Nextcloud sale apaisada.
- La barra en una fila: un layout alternativo en `res/layout-land/`, o reordenar las dos filas al detectar
  horizontal.
- Que la pluma no deje un trazo a medias si la tablet gira mientras se escribe: se descarta el trazo en curso, como
  con un `ACTION_CANCEL`.

**Hecho cuando:** girar con una nota a mano abierta no pierde nada (trazos, deshacer, herramienta); en horizontal se
puede leer y escribir en una página vertical desplazándose; se puede crear una página horizontal; el chat gira sin
perder la conversación; probado girando la tablet.

## Fase 11: buscar en lo escrito a mano

Lo que más se nota a diario: encontrar una nota por lo que dice, también si está escrita a mano.

**Qué hace.**

- **Buscador en la lista de notas.** No existe todavía; busca en el título y el texto de las notas de texto, y en la
  transcripción de las notas a mano. Los resultados indican si el texto se encontró en lo escrito a mano y en qué
  página, y abren la nota en esa página.
- **Transcripción automática en segundo plano.** Cada página de nota a mano que cambia se transcribe con
  `gpt-6-luna` cuando la nota lleva un rato sin tocarse (y nunca mientras se escribe).

**Diseño inicial.**

- La transcripción se guarda junto a los trazos: `.attachments.<id>/libreta.text.json`, con el texto de cada página y
  la huella (hash) de la página de la que sale. Se sincroniza como cualquier adjunto, así que también se puede buscar
  desde Nextcloud y no se repite en otra tablet.
- Solo se envían las páginas cuya huella cambió. Es lo que limita el gasto del plan: una nota nueva cuesta una
  petición por página, y retocar una página, una más.
- La cola de páginas pendientes sobrevive a reinicios y se vacía con red y con sesión de ChatGPT; si se alcanza el
  límite de uso del plan, se espera y se reintenta más tarde, sin avisos molestos.
- La búsqueda es local: insensible a mayúsculas y acentos, sobre los ficheros. Con cientos de notas basta un índice en
  memoria construido al abrir la lista; un índice en disco solo si hiciera falta.
- Ajustes: activar o desactivar la transcripción automática y ver cuántas páginas hay pendientes.

**Riesgos.** El gasto del plan si se escriben muchas páginas (de ahí la huella por página y la pausa antes de
transcribir). La calidad con letra real, que falta medir (ver [`07-ia.md`](07-ia.md#verificado-en-la-tablet-2026-10-11)).

**Hecho cuando:** una palabra escrita a mano aparece en la búsqueda unos minutos después, sin hacer nada; retocar
una página solo vuelve a transcribir esa página; la transcripción viaja a Nextcloud.

## Fase 12: pregúntale a tu libreta

El chat deja de limitarse a un recorte y puede trabajar con todas las notas: «¿qué apunté sobre el presupuesto en
septiembre?», «haz una lista con todo lo pendiente de esta semana».

**Qué hace.**

- Un chat general, desde la lista de notas, además del que ya se abre desde un recorte.
- El modelo usa herramientas sobre las notas locales: `buscar_notas` (con el buscador de la fase 11), `leer_nota`,
  `crear_nota`, `añadir_a_nota` y `listar_categorias`.
- Las respuestas citan las notas que usan, con enlaces que las abren.

**Diseño inicial.**

- El bucle de herramientas ya está probado (prueba del 2026-10-11 con `guardar_nota`). Hay que generalizar
  `AiConversation`/`AiAssistant` para que guarden las llamadas a herramientas y sus resultados entre turnos.
- Las herramientas que **escriben** piden confirmación en pantalla («La IA quiere crear la nota “Pendientes”»)
  antes de actuar.
- Para no reenviar mucho texto en cada turno (`store: false`), `leer_nota` devuelve como mucho unos miles de
  caracteres y `buscar_notas`, fragmentos.
- La conversación se puede guardar como nota, como el chat de la Fase 7.

**Hecho cuando:** preguntas sobre notas de texto y a mano se responden con citas correctas; crear o modificar una nota
pide confirmación; tests del bucle con MockWebServer.

## Fase 13: títulos, etiquetas e índice

Organizar las notas a mano sin teclado, como en Supernote: marcar con el recuadro qué es un título y qué es una
etiqueta.

**Qué hace.**

- En la barra de selección: **«Título»** y **«Etiqueta»**. La IA lee lo seleccionado (como «Convertir a texto»).
- **Índice de la nota:** lista de títulos con su página; tocar uno lleva a esa página.
- **Etiquetas:** filtran la lista de notas, junto al filtro de categorías.
- El índice y las etiquetas aparecen también en el Markdown de la nota, así que se ven en Nextcloud Notes.

**Diseño inicial.**

- Se guardan junto a los trazos (en `libreta.ink.json` o en el `libreta.text.json` de la fase 11), con la página y el
  recuadro de cada una; si se borran o mueven los trazos (fase 8), la marca se actualiza o desaparece.
- En el Markdown, una sección gestionada como la de las imágenes de las páginas (`InkNoteFormat`): `## Índice`, con un
  enlace por título, y una línea de etiquetas `#etiqueta`.

**Hecho cuando:** marcar un título lo añade al índice con su página; las etiquetas filtran la lista; todo se ve en
Nextcloud.

## Fase 14: pasar a limpio una nota entera

**Qué hace.** Desde el menú de una nota a mano, «Pasar a limpio» crea una **nota de texto nueva** con todas las
páginas transcritas y estructuradas: títulos, listas, tablas, fórmulas. Se ve formateada gracias a Markwon. La nota a
mano no se toca.

**Diseño inicial.**

- Una petición por página a `gpt-6-luna` (con la página entera, como en «Convertir a texto») y, si hay más de una,
  otra a `gpt-6.1-sol` que une las páginas sin repetir ni cortar frases a medias. Si la fase 11 ya transcribió las
  páginas, se parte de ese texto.
- La nota nueva enlaza a la original y queda en la misma categoría.
- Las fórmulas en LaTeX necesitarían el módulo `ext-latex` de Markwon: comprobar antes su compatibilidad con API 19.

**Hecho cuando:** una nota de varias páginas da una nota de texto legible y fiel; probado con letra real.

---

## Sin prioridad

Ideas valiosas sin fecha, de mayor a menor interés:

| Idea | Quién lo tiene | Notas |
|---|---|---|
| **Del papel a la agenda.** Seleccionar «cita con el médico mañana a las 9:30» o una lista de tareas y crear el evento o las tareas en el Calendario y las Tareas del Nextcloud (CalDAV) | Casi ninguna libreta | Reutiliza la selección y las herramientas del modelo; encaja con la fase 12 |
| **Grabar clases o reuniones con el audio sincronizado a los trazos.** Grabación larga transcrita por partes en el servidor; tocar un trazo reproduce el audio desde ese momento; resumen al final | Notability, Samsung Notes, Apple Notes | La hora de cada trazo ya se guarda. Es la más diferenciadora y la más larga |
| **Plantillas de página:** diario, semanal, Cornell, tareas, acta de reunión | reMarkable, Supernote | Amplía el tipo de papel actual |
| **Repaso:** resumen semanal, tarjetas de estudio o preguntas, guardados como notas | Notability, Kindle Scribe | Sobre la fase 12 |
| **Anotar PDFs de Nextcloud** | reMarkable, Boox, GoodNotes | `PdfRenderer` pide API 21: las páginas se convertirían en imágenes en el servidor |
| Matemáticas manuscritas: resolver o comprobar fórmulas | Apple Notes, GoodNotes | Solo si se usa para estudiar o trabajar con fórmulas |
| Enlaces `[[nota]]` entre notas | Supernote, Obsidian | Más fácil tras la fase 12 |

## Fuentes de la comparación

- [Supernote: títulos, palabras clave y estrellas](https://ratta.helpjuice.com/1759244-using-titles-keywords-and-stars),
  [Digest](https://ratta.helpjuice.com/en_US/what-is-digest-and-what-does-it-do) y
  [sistema de notas](https://supernote.com/pages/note-system-everything-you-need-to-stay-organized)
- [GoodNotes: apps de notas con IA para escritura a mano y PDFs (2026)](https://www.goodnotes.com/blog/best-ai-notetaking-apps-handwritten-notes-pdfs)
- [GoodNotes frente a Notability en 2026 (Setapp)](https://setapp.com/app-reviews/goodnotes-vs-notability) y
  [funciones de IA (AFFiNE)](https://affine.pro/blog/goodnotes-vs-notability-ai-features-tips)
- [Apple Notes: grabar, transcribir y resumir (AppleInsider)](https://appleinsider.com/articles/24/06/13/record-summarize-and-transcribe-audio-with-apple-notes-on-ios-18)
- [Samsung Notes con Galaxy AI (SoyaCincau)](https://soyacincau.com/2024/07/29/samsung-galaxy-z-fold-6-notes-galaxy-ai-audio-transcription-sketch-to-image-pdf-translation-handwriting-help-auto-format-summary/)
- [Alternativas a reMarkable (iFLYTEK)](https://store.iflytek.com/blogs/news/best-remarkable-tablet-alternatives)
