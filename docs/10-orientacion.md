# Fase 10: vertical y horizontal

**Antes** (comprobado en la tablet el 2026-10-11 forzando la horizontal): la lista, el editor de texto y los ajustes
giraban bien, pero **la nota a mano y el chat con la IA estaban fijados en vertical** (`screenOrientation="portrait"`
desde las Fases 6 y 7). Con la tablet en horizontal se seguían viendo en vertical ([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- **La nota a mano gira** con la tablet, sin perder nada: trazos, deshacer, herramienta, selección y zoom.
- **Página vertical en horizontal:** se muestra **ajustada al ancho** (es el «1×» en esa posición) y se baja por
  ella arrastrando con dos dedos (Fase 9). Se escribe igual que en vertical; la página no cambia.
- **Páginas horizontales:**
  - una **nota nueva** creada con la tablet en horizontal tiene la página apaisada, porque toma el tamaño de la zona
    de escritura, como hasta ahora;
  - **«Añadir página»**, cuando la orientación de la pantalla no coincide con la de la página actual, pregunta si la
    nueva va **vertical u horizontal**. Si coinciden, añade una igual que la actual, como antes.
- **El chat con la IA gira** sin perder la conversación.

## Diseño

- **Sin recrear la actividad.** `InkNoteActivity` y `AiChatActivity` pierden `screenOrientation="portrait"` y
  mantienen `configChanges="keyboardHidden|orientation|screenSize"`: al girar, Android no las destruye, así que la
  nota, el historial de deshacer y la conversación siguen en memoria. Las vistas se reajustan al nuevo tamaño.
- **`InkView.onSizeChanged`** pasa la vista a `InkViewport.resized`, que conserva el zoom y el punto de la página que
  estaba en el centro. Si había un trazo, un borrado o un arrastre en curso, se cancela como un `ACTION_CANCEL`.
- **Ajuste en `InkViewport`:** si la vista es apaisada y la página vertical, `fitScale` ajusta al ancho; en el resto
  de casos sigue ajustando la página entera. El zoom va de ese 1× a 4×.
- **Páginas de distinto tamaño en una nota.** Cada página ya guarda su ancho y alto en `libreta.ink.json`
  (`InkPage.width`/`height`) y su imagen sale con su tamaño, así que el formato no cambia. `InkEditor.addPage` acepta
  el tamaño de la página nueva. Una página horizontal tiene el ancho y el alto de la vertical intercambiados.
- **La barra sigue en dos filas también en horizontal** (el plan del roadmap proponía una). Sin recrear la actividad
  no se puede cambiar de layout sin rehacer las vistas, y la fila de selección (color, grosor, copiar, borrar, pegar y
  las dos acciones de IA) no cabe en una sola línea de 1024 px junto a las herramientas. Se pierden unos 44 px de
  alto, que compensan el zoom y el desplazamiento.

## Verificado en la tablet (2026-10-12)

Forzando la rotación con `settings put system user_rotation` (y dejando después la rotación automática como estaba),
en notas creadas para la prueba y borradas después:

| Prueba | Resultado |
|---|---|
| Nota vertical a 1,5× girada a horizontal | Conserva trazos, herramienta y zoom (ahora relativo al ajuste al ancho) y el punto que estaba en el centro |
| 1× en horizontal | La página vertical ocupa todo el ancho. Bajar por ella es el arrastre con dos dedos de la Fase 9, que se probó allí en vertical y aquí no se repitió |
| «Añadir página» en horizontal sobre una página vertical | Pregunta «Vertical / Horizontal»; la horizontal (851×600) cabe entera |
| Vuelta a vertical | La página apaisada se ajusta al ancho arriba; la página 1 sigue con sus trazos |
| Nota nueva creada en horizontal | Página apaisada del tamaño de la zona de escritura |
| Chat con una respuesta, girado a horizontal | La conversación sigue; al volver, la nota a mano se reajusta sola y conserva la selección |

## Hecho cuando

- Girar con una nota a mano abierta no pierde trazos, deshacer, herramienta, selección ni zoom.
- En horizontal, una página vertical se ve ajustada al ancho y se puede escribir en toda ella desplazándose.
- Se puede añadir una página horizontal y la nota la guarda, con su imagen apaisada en Nextcloud.
- Una nota nueva creada en horizontal tiene la página apaisada.
- El chat gira sin perder la conversación.
- Tests de `InkViewport` (ajuste al ancho) e `InkEditor.addPage` con tamaño; probado girando la tablet.
