# Fase 16: grosor y tamaño a medida

Pedido tras usar la app (2026-10-12): la pluma solo tenía tres grosores (fino, medio y grueso) y el borrador, tres
tamaños. Ahora los dos se ajustan con una **barra deslizante**, sin saltos ([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- **Pluma:** en su fila de opciones, junto al color, una barra que va de un trazo finísimo (0,5) a uno muy grueso
  (40 unidades; la pluma «Medio» de antes es 3,5). A su lado, una muestra del trazo con su color y tamaño reales, y el
  número.
- **Borrador:** igual, de un radio de 2 a uno de 80 unidades (el «Mediano» de antes es 10), con su muestra: un círculo
  como el que se ve al borrar. Sigue pudiendo borrar «Donde toco» o el «Trazo entero».
- **Grosor de lo seleccionado:** el botón de grosor de la selección (Fase 8) abre la misma barra en lugar de la lista
  de tres grosores, empezando en el grosor del primer trazo seleccionado. El cambio es un paso de deshacer.
- Se recuerda el último grosor y tamaño, como hasta ahora.

## Diseño

- **Escala.** La barra va de 0 a 1000 pasos, y el valor crece de forma exponencial (`min · (max/min)^(p/1000)`): así
  hay tanto recorrido entre 1 y 3 como entre 13 y 40, que es donde se nota la diferencia. `InkSizes`, en `:core`, con
  tests de que es continua, monótona y reversible.
- Los grosores son, como siempre, **como se ven en pantalla**: con la página ampliada (Fase 9) el trazo se guarda
  dividido por el zoom. El grosor de la selección, en cambio, es el guardado en la página, que es lo que se cambia.
- **Muestra** (`SizePreview`): un círculo del diámetro real en pantalla, relleno con el color de la pluma o con el
  contorno del borrador; si no cabe en su sitio se queda del tamaño del sitio, y el número dice el resto.
- Las preferencias guardadas con los tres grosores fijos siguen valiendo: son valores dentro de la escala.

- El grosor de la selección no se aplica a los rellenos (Fase 15): sus franjas tienen el grosor justo para cubrir la
  figura.

## Verificado en la tablet (2026-10-12)

| Prueba | Resultado |
|---|---|
| Barra de la pluma al máximo y al mínimo | Trazos de 40 y de 1,7; la muestra y el número siguen a la barra |
| Barra del borrador a 45 | Borra una franja ancha; la muestra en anillo se queda del tamaño de su sitio |
| Grosor de lo seleccionado (una cruz de 3,5) | El diálogo empieza en 3,5; al aceptar a ~15 la cruz engorda de un paso y el relleno de alrededor no cambia |

## Hecho cuando

- La barra cambia el grosor de la pluma, el tamaño del borrador y el de lo seleccionado de forma continua, con su
  muestra.
- Tests de `InkSizes`; probado en la tablet.
