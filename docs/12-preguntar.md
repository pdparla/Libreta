# Fase 12: pregúntale a tu libreta

El chat con la IA deja de limitarse a un recorte: puede buscar, leer y escribir en todas las notas. «¿Qué apunté
sobre el presupuesto en septiembre?», «haz una lista con lo pendiente de esta semana y guárdala»
([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- **Chat general** desde la lista de notas (icono de bocadillo, «Preguntar a la libreta»), además del chat sobre un
  recorte de la Fase 7. Los dos usan las mismas herramientas.
- La IA usa herramientas sobre las notas de la tablet. En el chat aparece una línea gris por cada una
  («Buscando «presupuesto»…», «Leyendo «Compra»…»), para ver qué está consultando.
- Las respuestas **citan las notas** que usan como enlaces; tocar uno abre la nota (en su página, si es a mano).
- Las herramientas que **escriben** piden permiso en pantalla antes de actuar: «La IA quiere crear la nota
  «Pendientes» en «Casa»» con **Permitir** o **No**. Si se rechaza, la IA lo sabe y sigue la conversación.
- La conversación se puede guardar como nota, como en la Fase 7 (sin las líneas de herramientas).

## Herramientas

| Nombre | Parámetros | Devuelve |
|---|---|---|
| `buscar_notas` | `consulta` | Hasta 10 notas que la contienen (con el buscador de la Fase 11): ruta, título, dónde (título, texto o página) y fragmento |
| `listar_notas` | `categoria` (opcional) | Hasta 50 notas, las más recientes primero: ruta, título, categoría, si es a mano; y las categorías que existen |
| `leer_nota` | `nota` (ruta) | Título, categoría, texto (en las notas a mano, el texto del usuario sin las líneas gestionadas) y lo que dice cada página a mano; como mucho 6000 caracteres |
| `crear_nota` | `titulo`, `texto`, `categoria` (opcional) | Ruta de la nota creada. **Pide permiso** |
| `anadir_a_nota` | `nota`, `texto` | Confirmación. Añade el texto al final de una nota de texto, o encima de las páginas de una nota a mano (como «Convertir a texto»). **Pide permiso** |

Los nombres van sin «ñ» porque la API solo admite letras ASCII, cifras, `_` y `-` en el nombre de una función.

## Diseño

- **Bucle de herramientas** (`AiAssistant.send`): pide un turno; si el modelo pide herramientas, las ejecuta, añade
  sus resultados (`function_call_output`) a la conversación y vuelve a pedir, hasta que responde con texto (como mucho
  8 vueltas). Las llamadas y resultados se guardan en la conversación y se reenvían en cada turno, como el resto
  (`store: false`). El bucle ya se probó en la prueba de viabilidad de la Fase 7.
- **`NotebookTools`** (`data.ai`) declara las herramientas (esquemas JSON) y las ejecuta sobre el repositorio local.
  Los resultados son JSON pequeños: `leer_nota` recorta a 6000 caracteres y `buscar_notas` devuelve fragmentos, para no
  reenviar mucho texto en cada turno.
- **`NotebookIndex`** (`:core`) arma lo que se puede buscar de cada nota (título, texto, páginas a mano). Lo usan la
  lista y las herramientas, para que encuentren exactamente lo mismo.
- **Permiso:** las herramientas se ejecutan en el hilo de la IA. Las que escriben llaman a `confirm(texto)`, que
  muestra el diálogo en el hilo principal y espera la respuesta (`CountDownLatch`). Cancelar la respuesta o cerrar el
  chat cuenta como «No».
- **Citas:** las instrucciones piden citar con enlaces `[Título](<nota:Categoría/Título.md#2>)` (los `<>` admiten
  espacios en CommonMark; `#2` es la página). Markwon resuelve los enlaces `nota:` abriendo la nota; los demás no se
  abren.
- Las líneas grises de herramientas son solo de la pantalla: no se guardan en la nota de la conversación.

## Verificado en la tablet (2026-10-12)

Con la libreta vaciada de notas de prueba y dos notas nuevas: «Compra» (texto: «Para el sábado: pan, leche, seis
huevos y tomates») y una nota a mano con «LUZ».

| Pregunta | Resultado |
|---|---|
| «¿Qué tengo que comprar el sábado?» | Líneas «Buscando «sábado»…» y «Leyendo «Compra»…»; respuesta con la lista y la cita [Compra]; tocarla abre la nota |
| «¿En qué nota a mano pone luz? Crea también una nota Pendientes con comprar bombillas» | «Buscando «luz»…»; diálogo «La IA quiere crear la nota «Pendientes».» con el texto; con «Permitir», la nota aparece en la lista; la respuesta cita la página 1 de la nota a mano y abre la nota en esa página |

El permiso denegado y las herramientas en el chat del recorte (el mismo código) están cubiertos por los tests; no se
repitieron en la tablet.

**Pega conocida:** con el icono nuevo, la barra de la lista tiene seis iconos y el selector de categoría queda
recortado («Todas las.»). Sigue funcionando; se puede revisar al ordenar la barra.

## Hecho cuando

- Preguntas sobre notas de texto y a mano se responden con citas correctas, y la cita abre la nota.
- Crear o añadir a una nota pide permiso; con «No» no se escribe nada y la IA lo dice.
- El chat del recorte también puede usar las herramientas.
- Tests del bucle de herramientas con MockWebServer (llamada, resultado, permiso denegado) y de `NotebookIndex`.
- Probado en la tablet.
