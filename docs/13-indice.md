# Fase 13: títulos, etiquetas e índice

Organizar las notas a mano sin teclado, como en Supernote: marcar con el recuadro qué es un título y qué es una
etiqueta, y navegar por ellos ([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- **Marcar.** Con la herramienta Seleccionar, la barra ofrece **Título** y **Etiqueta**. La IA lee lo seleccionado
  (como «Convertir a texto», con `gpt-6.1-sol`) y propone el texto en un cuadro editable; al aceptar, queda marcado.
  Para que quepan, los botones de texto de la barra se acortan: **A texto**, **Chat IA**, **Título**, **Etiqueta**.
- **Índice de la nota** (menú «Índice»): los títulos en orden, con su página; tocar uno lleva a esa página.
- **Etiquetas en la lista:** el desplegable de categorías añade una entrada por etiqueta (`#compras`) que filtra la
  lista. También cuentan las etiquetas escritas en cualquier nota de texto (`#compras`, no `# Título`).
- **En Nextcloud:** el Markdown de la nota a mano lleva, encima de las páginas:

  ```markdown
  ## Índice

  - Viaje a Lisboa (pág. 1)
  - Presupuesto (pág. 3)

  Etiquetas: #viajes #2026
  ```

## Diseño

### Las marcas siguen a sus trazos

- Una marca (`InkMark`) es de una página y tiene un tipo (título o etiqueta), su texto y los **trazos que la forman**,
  identificados por su hora de inicio (`Stroke.startedAt`), que mover, escalar o recolorear (Fase 8) no cambian.
- El recuadro de la marca no se guarda: es el que envuelve sus trazos, así que **se mueve con ellos**.
- Si se borran algunos trazos, la marca sigue con los que quedan; si se borran todos, **desaparece**. Lo aplica
  `InkEditor` tras cada cambio, así que deshacer devuelve la marca.
- Marcar es un paso de deshacer.

### Formato

- `libreta.ink.json` pasa a la **versión 2**: cada página puede tener `"marks"`:

  ```json
  {"kind":"title","text":"Viaje a Lisboa","strokes":[1760197500000,1760197501200]}
  ```

  Las versiones 1 se siguen leyendo (sin marcas).
- La huella de una página para la transcripción (Fase 11) **no incluye las marcas**: marcar un título no vuelve a
  transcribir la página.
- Los textos de las etiquetas se normalizan: minúsculas, sin `#`, espacios convertidos en `-`.

### Markdown gestionado

`InkNoteFormat` ya mantiene las líneas de las imágenes de las páginas y la referencia de los trazos. Ahora también:

- un bloque **`## Índice`** seguido de las líneas `- Título (pág. N)`, solo si hay títulos;
- una línea **`Etiquetas: #a #b`**, solo si hay etiquetas.

Van detrás del texto del usuario y delante de las imágenes, y se regeneran en cada guardado a partir de las marcas.
Solo se reconocen como gestionadas exactamente esas formas (el encabezado `## Índice` y sus líneas `- … (pág. N)`,
y la línea que empieza por `Etiquetas: #`), así que el resto del texto del usuario no se toca.

### Etiquetas en la lista

- `NoteTags` (`:core`) saca las etiquetas de un Markdown: palabras precedidas de `#` sin espacio (`#compras`), lo que
  excluye los encabezados (`# Título`). Sirve igual para las notas a mano (por su línea gestionada) y las de texto.
- `CategoryFilter` gana la opción «etiqueta»; el desplegable muestra las categorías y después las etiquetas.

## Verificado en la tablet (2026-10-12)

Sobre una nota a mano con «LUZ» escrito con trazos:

| Prueba | Resultado |
|---|---|
| Seleccionar → Título | La IA propone «LUZ»; al aceptar, la tinta queda resaltada en amarillo |
| Seleccionar → Etiqueta | Propone «luz» (minúsculas); resaltado en verde |
| Menú → Índice | «LUZ · pág. 1» |
| Markdown («Editar el texto») | `## Índice`, `- LUZ (pág. 1)` y `Etiquetas: #luz`, encima de la imagen de la página |
| Transcripción | Marcar no provocó otra lectura de la página (sin pasada en el registro) |
| Lista | El desplegable añade «#luz»; elegirla deja solo esa nota |

**Fallo encontrado y corregido:** «Título» devolvía un cuadro vacío, y «A texto», «No se ha reconocido texto». Desde
la Fase 8 la selección se ajusta a los trazos y el recorte llegaba sin margen, con las letras tocando los bordes, y
el modelo no leía nada. `InkRenderer.renderSelection` añade ahora un margen blanco (16 unidades o el 10 % del lado
mayor). Afectaba también a «Convertir a texto» y al chat sobre un recorte desde la Fase 8.

Que una marca siga a sus trazos al moverlos, desaparezca al borrarlos y vuelva al deshacer, y las etiquetas escritas en
notas de texto, están cubiertos por los tests de `:core`; no se repitieron en la tablet.

## Hecho cuando

- Marcar un título lo añade al índice con su página; tocarlo en el índice lleva a esa página.
- Mover los trazos de una marca la mueve; borrarlos la quita; deshacer la devuelve.
- Las etiquetas filtran la lista, también las escritas en notas de texto.
- El índice y las etiquetas aparecen en el Markdown (y por tanto en Nextcloud), y marcar no provoca otra
  transcripción.
- Tests de `InkMark` en `InkEditor`, del formato v2, de `InkNoteFormat` y de `NoteTags`; probado en la tablet.
