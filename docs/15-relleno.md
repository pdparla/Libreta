# Fase 15: rellenar figuras

Pedido tras usar la app (2026-10-12): para colorear un círculo hoy hay que pintarlo entero a mano. Ahora hay un **bote
de pintura**, como en cualquier programa de dibujo ([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- **Herramienta «Rellenar»** en la barra, junto a la pluma, el borrador y la selección. Su fila de opciones muestra
  el color (el mismo de la pluma) y una pista: «Toca dentro de una figura cerrada».
- **Tocar dentro de una figura** la rellena con ese color hasta la tinta que la rodea. El relleno queda **detrás de la
  tinta**: el contorno y lo escrito dentro se siguen viendo.
- **Huecos pequeños** en el contorno (un círculo que no se cerró del todo, de unos pocos milímetros) no dejan escapar
  el relleno.
- **Tocar otra vez** dentro de una figura ya rellena cambia el color del relleno, no le pone otro encima.
- Tocar fuera de cualquier figura rellena la zona abierta hasta los bordes de la página, como en un programa de dibujo;
  se deshace de un paso.
- Tocar justo encima de la tinta no hace nada y lo dice.

Un relleno es tinta como la demás: se deshace de un paso, se borra con el borrador (por zonas, o entero con el
borrador de trazos), se selecciona, mueve, copia y recolorea, y sale en las imágenes de Nextcloud y en lo que lee la IA.

## Diseño

- **Un relleno son trazos.** Se guarda como franjas horizontales: trazos de dos puntos, uno por fila, gruesos y
  solapados, que juntos cubren la zona. Así todo lo que ya sabe hacer la tinta (borrar por zonas, seleccionar, mover,
  escalar, recolorear, pintar la página para Nextcloud) funciona con los rellenos sin código nuevo, y una zona con
  agujeros (las letras escritas dentro) no necesita geometría de polígonos.
- **Formato.** Las franjas llevan `"fill":true` en `libreta.ink.json` y comparten `start`, que identifica el relleno
  entero. Es un campo nuevo y opcional: no cambia la versión del formato ni la huella de las páginas sin rellenos
  (Fase 11), y una versión anterior de la app las mostraría igual, como trazos.
- **Cálculo** (`InkFill`, en `:core`, con tests):
  1. Se rasteriza la página en una rejilla: cada celda que toca la tinta (sin contar otros rellenos) queda bloqueada,
     con la tinta **engordada** un margen *g* que cierra los huecos pequeños.
  2. Desde la celda tocada se inunda (relleno por líneas, con una pila: sin recursión) la zona libre conectada.
  3. Cada *s* unidades de alto se toma la fila y, de cada tramo seguido de celdas de la zona, sale una franja de grosor
     `s + 2g`: el grosor y las puntas redondas devuelven el margen *g* que quitó el paso 1, de modo que el relleno
     llega hasta la tinta sin salirse por fuera del contorno.
- **Tamaños.** *g* = 3 unidades en pantalla y *s* = *g* / 2, divididos por el zoom, para que una figura pequeña
  dibujada con la página ampliada (Fase 9) también se pueda rellenar. La celda mide 1 / zoom unidades, pero nunca menos
  de lo que deja la rejilla en 1,5 millones de celdas (1,5 MB de memoria; la app tiene 64 MB).
- **Volver a rellenar.** Las franjas de relleno que caen dentro de la zona nueva se quitan antes de añadir las nuevas,
  en el mismo paso de deshacer.
- **Orden.** Las franjas nuevas van al fondo de la página, delante de la primera tinta y después de los rellenos que ya
  hay allí: la tinta queda encima y un relleno nuevo, sobre los anteriores.
- **Agrupar.** El borrador de trazos enteros quita el relleno entero, y la selección que toca parte de un relleno lo
  toma entero, por su `start` compartido.
- **Selección.** Una franja de relleno es una línea larga con solo dos puntos, así que un recuadro marcado en medio del
  relleno no tocaba ningún punto. La selección mira ahora también las líneas entre puntos (`Stroke.touches`), lo que
  de paso acierta con los trazos rápidos de pocos puntos.

## Verificado en la tablet (2026-10-12)

Con dos círculos de 100 unidades dibujados con la pluma «Medio», uno con una cruz dentro y otro con un hueco de
8 unidades, inyectando los toques con `sendevent` como en la Fase 9.

| Prueba | Resultado |
|---|---|
| Tocar dentro del círculo con la cruz | Relleno amarillo hasta el contorno; la cruz se ve encima. **~125 ms** |
| Círculo con un hueco de 8 unidades | Se rellena sin escaparse por el hueco |
| Tocar otra vez con azul | El relleno pasa a azul; a 2× los bordes quedan limpios bajo el contorno |
| Deshacer | Vuelve el amarillo, de un paso |

## Hecho cuando

- Un círculo, también con un pequeño hueco, se rellena hasta su contorno; lo escrito dentro se sigue viendo.
- Volver a tocar cambia el color; deshacer lo quita de un paso.
- Borrar, seleccionar y mover funcionan con el relleno; se ve en Nextcloud.
- Tests de `InkFill` y del formato en `:core`; probado con el lápiz en la tablet.
