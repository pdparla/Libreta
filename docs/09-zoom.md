# Fase 9: zoom en el lienzo

Hasta ahora la página de una nota a mano se veía entera y fija: al quedarse sin sitio había que añadir otra, y no se
podía ampliar una zona para escribir más pequeño, completar un hueco o retocar un detalle
([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- **Pellizco con dos dedos:** amplía o reduce entre 1× (la página entera, como antes) y 4×, alrededor del punto
  entre los dedos.
- **Arrastre con dos dedos:** desplaza la página ampliada.
- **Controles sobre el lienzo**, en la esquina inferior derecha: **−**, el zoom actual (p. ej. «2×») y **+**. Tocar el
  zoom actual vuelve a la página entera. Están ahí porque el pellizco con un lápiz capacitivo no siempre es cómodo, y
  no caben en la barra de herramientas.
- Con la página ampliada se escribe, se borra y se selecciona igual que a 1×. Lo escrito queda más pequeño en la
  página: es lo que da sitio para seguir escribiendo en ella.
- El zoom no se guarda con la nota: al abrirla se ve la página entera. Al cambiar de página se mantiene el zoom y se
  va a la esquina superior izquierda.

## Diseño

### Vista y página

Los trazos siguen en unidades de página (1 unidad = 1 dp de la tablet al crear la página): el zoom es solo cómo se
muestra, y `libreta.ink.json` no cambia.

`InkViewport` (`:core`, sin Android, con tests en la JVM) lleva la transformación entre página y pantalla:

- `scale = fitScale × zoom`, donde `fitScale` hace caber la página entera en la vista;
- `originX`, `originY`: dónde cae, en píxeles de la vista, la esquina superior izquierda de la página;
- `toPageX/Y` y `toViewX/Y` para convertir puntos, `zoomAround(focoX, focoY, zoom)` para ampliar sin que se mueva lo
  que hay bajo los dedos, y `panBy(dx, dy)` para desplazar;
- los límites: con la página más pequeña que la vista en un eje, se centra en él; si es más grande, no se puede
  desplazar más allá de sus bordes. Así nunca se ve vacío alrededor de una página ampliada.

`InkView` pasa todas las conversiones (pluma, borrador, selección, tirador de la Fase 8) por `InkViewport`, en lugar
del antiguo `offsetX` y `y / scale`.

### Grosor y borrador con zoom

- Un trazo hecho a zoom *z* se guarda con el grosor de la pluma dividido entre *z*. En pantalla se ve igual que a 1×
  mientras se escribe, y al volver a 1× queda proporcionado con la letra, que es más pequeña.
- El radio del borrador también se divide entre *z*: en pantalla mide lo mismo, y a 4× permite borrar con precisión.

### Lápiz frente a pellizco

Hasta ahora el primer contacto dibujaba y los demás se ignoraban (palma o dedo apoyado). Ahora, si llega un segundo
contacto cuando el primero aún es reciente (**menos de 150 ms** o **menos de 8 dp recorridos**), lo que había empezado
se descarta y los dos dedos pasan a ser un gesto:

- pluma: se descarta el trazo en curso;
- borrador: se deshace lo borrado en ese toque (`InkEditor.cancelGesture`, que vuelve al estado del inicio del gesto
  sin dejar paso de deshacer);
- selección: se cancela el recuadro, el movimiento o el cambio de tamaño en curso.

Si el primer contacto ya lleva un trazo hecho, el segundo se sigue ignorando, como antes: una mano apoyada mientras se
escribe no hace zoom. El gesto dura hasta que se levantan todos los dedos. Los umbrales hay que calibrarlos con el
lápiz AmberVec, como se hizo con la palma.

### Rendimiento

- La caché sigue siendo un bitmap del tamaño de la vista. Al redibujar solo se pintan los trazos cuyo recuadro
  envolvente toca la zona visible.
- **Durante el pellizco** no se redibuja la página: se dibuja la caché escalada y desplazada con la transformación
  del canvas, y al soltar los dedos se redibuja nítida. Los controles −/+ redibujan directamente.

### Imágenes de las páginas

- Las imágenes que se suben a Nextcloud pasan de 1 a **2 píxeles por unidad**: escrita a 3× o 4×, la letra sería
  ilegible a 1 píxel por unidad. Una página de la tablet (600×851 unidades) pasa a 1200×1702 píxeles; en RGB 565,
  porque no tiene transparencia y así ocupa la mitad de memoria al generarla. Una página pautada con pocos trazos
  pesa unos **12 KB** en PNG.
- El pautado (líneas y margen) se dibuja con un grosor que escala con la página (`max(1, escala)`): una línea de
  1 píxel desaparecía al reducir la imagen a 2× al ancho de la pantalla, y con zoom queda proporcionada.
- En la tablet, la vista formateada de la nota (Fase 7) decodificaría cada página a 1200×1800, unos 8 MB por página,
  con solo 64 MB de memoria para toda la app. Por eso `Markdown.forNote` usa un decodificador propio que reduce cada
  imagen al ancho de la pantalla al decodificarla (`inSampleSize`).

## Verificado en la tablet (2026-10-12)

El pellizco y el arrastre con dos dedos se probaron inyectando dos dedos con `sendevent` en
`/dev/input/event1` (pantalla MELFAS, multitáctil con slots), porque `adb shell input` solo tiene un dedo. Las pruebas
se hicieron en notas creadas para la ocasión y borradas después.

| Prueba | Resultado |
|---|---|
| Controles − / nivel / + | + dos veces: 2×, alrededor del centro; tocar el nivel vuelve a 1× |
| Pellizco (dedos de 100 a 250 px) | 2,5×, sin trazos fantasma: el primer dedo era reciente y su trazo se descartó |
| Arrastre con dos dedos | La página se desplaza y se detiene en su esquina superior izquierda |
| Escribir a 2,5× | El trazo se ve con el grosor de la pluma; a 1× queda más pequeño y más fino, proporcionado |
| Borrador a 2,5× | Borra justo donde se frota, con el mismo tamaño en pantalla |
| Selección a 2,5× | Recuadro ajustado a los trazos y movimiento correctos |
| Segundo dedo con un trazo ya en marcha | Se dibuja la línea y no hay zoom: una mano apoyada no amplía |
| Imagen de página | 1200×1702, unos 12 KB; en la vista formateada se ve entera y con el pautado. La app usaba 8 MB de memoria con ella abierta |

Pendiente: calibrar los umbrales (150 ms, 8 dp) con el lápiz AmberVec y una mano de verdad.

## Hecho cuando

- El pellizco y los controles amplían, reducen y desplazan sin trazos fantasma ni saltos.
- Se puede escribir con la página ampliada y a 1× se ve proporcionado.
- La pluma, el borrador, la selección y el tirador aciertan en el sitio a cualquier zoom.
- Un segundo dedo al empezar no deja trazos ni borrones; con la mano apoyada mientras se escribe, no hay zoom.
- Las páginas siguen siendo legibles en Nextcloud y la vista formateada de la nota no se queda sin memoria.
- Tests de `InkViewport` y de `cancelGesture` en `:core`; probado en la tablet.
