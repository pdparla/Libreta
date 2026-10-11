# Fase 8: editar lo seleccionado

Con la herramienta **Seleccionar** de la nota a mano (Fase 7) ya no solo se convierte o se conversa sobre un
recuadro: también se edita lo que hay dentro. Es lo básico de cualquier libreta y era la mayor carencia
([`docs/roadmap.md`](roadmap.md)).

## Qué hace

| Gesto o botón | Resultado |
|---|---|
| Arrastrar fuera de la selección | Marca un recuadro nuevo. Al soltar, coge **los trazos enteros** con algo de tinta dentro y el recuadro se ajusta a ellos, con un tirador azul en la esquina inferior derecha |
| Arrastrar dentro del recuadro | Mueve los trazos seleccionados |
| Arrastrar el tirador | Cambia el tamaño, en proporción, desde la esquina superior izquierda. El grosor del trazo escala igual |
| Muestra de color | Panel de colores de la pluma, aplicado a lo seleccionado |
| Grosor (≡) | Fino, medio o grueso, como la pluma |
| Copiar | Guarda los trazos para pegarlos en esta página, en otra o en otra nota a mano |
| Borrar (papelera) | Quita lo seleccionado |
| Pegar (portapapeles) | Aparece en cuanto hay algo copiado. Pega un poco abajo y a la derecha del original, sin salirse de la página, y lo deja seleccionado para colocarlo |
| Convertir a texto, Conversar con IA | Como en la Fase 7, sobre el recuadro |

Si el recuadro no coge tinta se queda como se dibujó y solo valen las acciones de IA. Cada edición (mover, escalar,
color, grosor, borrar, pegar) es **un solo paso de deshacer**. Deshacer, rehacer, cambiar de página o de herramienta
quitan la selección.

## Diseño

- **`:core`:**
  - `Stroke.transformed` escala alrededor de un punto y desplaza. Conserva la hora de cada punto: mover tinta no
    cambia cuándo se escribió, que sirve al reconocimiento y servirá al audio sincronizado.
  - `Stroke.touches` decide si un trazo cae en un recuadro.
  - En `InkRect`: `selectedStrokes` (los índices de los trazos que coge), `around` (el recuadro que envuelve unos
    trazos, contando su grosor), `moveWithin` y `scaleWithin` (límites para no salirse de la página y no encoger por
    debajo de `MIN_SIDE`) y `transformed`.
  - En `InkEditor`: `transformStrokes`, `restyleStrokes`, `deleteStrokes` e `insertStrokes`, cada una un paso de
    deshacer. Una operación que no cambia nada no añade paso.
- **`InkView`:** la selección son índices de los trazos de la página actual, más su recuadro. Al empezar a mover o
  escalar se redibuja la caché sin los trazos seleccionados y se preparan sus `Path` una sola vez
  (`InkRenderer.strokePath`). En cada movimiento solo se aplica una transformación del canvas (traslación y escala),
  sin crear trazos nuevos ni redibujar la página, así que la tablet no se atasca. Al soltar, el cambio entra en el
  documento y se vuelve a pintar todo. Un `ACTION_CANCEL` a mitad deja todo como estaba.
- **Portapapeles** (`InkClipboard`): en memoria del proceso. Solo Libreta entiende los trazos, así que no se usa el
  portapapeles del sistema; se pierde si Android cierra la app.

## Verificado en la tablet (2026-10-12)

Sobre una nota con «HOLA» escrito con trazos, con `adb shell input`:

- Marcar las dos primeras letras: el recuadro se ajusta a la «H» y la «O» y aparece el tirador.
- Moverlas 200 px hacia abajo y escalarlas con el tirador hasta casi el doble: el trazo engorda en proporción.
- Ponerlas en rojo; el recorte para la IA sale rojo.
- Copiar, pegar (queda desplazado y seleccionado), cambiar el grosor de la copia a fino y borrarla.
- Seis «Deshacer» devuelven exactamente la nota original.
- Copiar, añadir una página y pegar en ella; dos «Deshacer» quitan lo pegado y la página.

Tests en la JVM: `InkEditorTest` (cada operación un paso de deshacer, la hora de los puntos se conserva, una
operación sin cambios no añade paso) e `InkRectTest` (trazos enteros, recuadro envolvente, límites al mover y
escalar).
