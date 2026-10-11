# Fase 11: buscar en lo escrito a mano

Encontrar una nota por lo que dice, también si está escrita a mano ([`docs/roadmap.md`](roadmap.md)). Hasta ahora la
lista no tenía buscador y lo escrito a mano solo existía como trazos e imágenes.

## Qué hace

- **Buscador en la lista de notas** (lupa en la barra). Busca en el título y el texto de todas las notas, y en la
  transcripción de las notas a mano. No distingue mayúsculas ni acentos («medico» encuentra «médico»).
  - Cada resultado muestra un fragmento alrededor de lo encontrado y, si está en lo escrito a mano, la página
    («A mano · pág. 2 · …compraré pan y leche…»).
  - Tocar un resultado de lo escrito a mano abre la nota en esa página.
  - La búsqueda respeta el filtro de categoría.
- **Transcripción automática en segundo plano.** Cada página de nota a mano que cambia se transcribe con
  el modelo de «Convertir a texto» (`gpt-6.1-sol`; al principio `gpt-6-luna`, que se descartó en la Fase 12), cuando la nota se deja de editar y al abrir la lista. Nunca
  mientras se escribe.
- **Ajustes:** «Transcribir lo escrito a mano para buscarlo» (activado por defecto) y cuántas páginas quedan
  pendientes.

## Diseño

### Transcripción guardada con la nota

`.attachments.<id>/libreta.text.json`, junto a los trazos:

```json
{"format":"libreta-text","version":1,"pages":[
  {"hash":"…","text":"Lista de la compra\n- pan\n- leche"},
  {"hash":"…","text":""}]}
```

- Un elemento por página, en orden. `hash` es la huella de la página (sus trazos, tamaño y papel) en el momento de
  transcribirla: si la página cambia, la huella deja de coincidir y la página queda pendiente.
- Va en la carpeta de adjuntos, así que se sincroniza con Nextcloud, acompaña a la nota al moverla o borrarla, y
  otra tablet no la vuelve a transcribir.
- Se escribe con la misma fusión con precondición que el resto (`WritePrecondition.MustMatch`), para no pisar una
  transcripción guardada mientras tanto.
- Códec escrito a mano en `:core`, como `InkJson` (org.json no existe en los tests de la JVM).

### Qué páginas se transcriben

- `InkTranscripts` (`:core`) compara las páginas de una nota con su `libreta.text.json` y devuelve las pendientes:
  las nuevas, las cambiadas y, si la nota perdió páginas, recorta las que sobran.
- Una página **sin trazos** se guarda con texto vacío sin llamar al modelo.
- Se envía la página entera dibujada **solo con la tinta**, sobre blanco y sin pautado (como la selección de la Fase
  7), con el lado mayor limitado a 1600 px.
- Es lo que limita el gasto del plan: una nota nueva cuesta una petición por página escrita, y retocar una página,
  una más.

### Cola en segundo plano (`InkTranscriber`)

- Un hilo propio. Recorre las notas a mano, transcribe las páginas pendientes **de una en una** y guarda cada
  resultado al momento: si la app se cierra a medias, lo hecho no se pierde y el resto queda pendiente.
- Se lanza al salir de una nota a mano (cuando ya se ha guardado) y al abrir la lista. Nunca hay dos pasadas a la
  vez.
- Se detiene sin avisar si no hay sesión de ChatGPT, si no hay red o si se alcanza el límite de uso del plan; lo
  pendiente se reintenta en la siguiente pasada. No se reintenta en bucle.
- Al terminar una pasada que transcribió algo, la lista se actualiza para que la búsqueda lo encuentre.

### Búsqueda

- `NoteSearch` (`:core`, con tests): normaliza (minúsculas y sin acentos, con `java.text.Normalizer`) y busca la
  consulta en el título, el texto y el texto de cada página, en ese orden. Devuelve dónde la encontró (título, texto
  o página *n*) y un fragmento de unos 60 caracteres alrededor.
- El índice se arma al cargar la lista, en segundo plano: la lista ya lee cada nota para saber si es a mano, y ahora
  lee además su `libreta.text.json`. Para cientos de notas basta un índice en memoria.
- En las notas a mano no se busca en el Markdown de las líneas gestionadas (imágenes y referencia de los trazos),
  solo en el título, el texto del usuario y la transcripción.
- Los fragmentos se ajustan a palabras enteras cuando hay un espacio a menos de 15 caracteres, para no empezar ni
  acabar a mitad de una palabra.
- Con una búsqueda sin resultados la lista dice «Ninguna nota contiene «…»», en lugar del mensaje de lista vacía.

## Verificado en la tablet (2026-10-12)

| Prueba | Resultado |
|---|---|
| Primera pasada al abrir la lista | Leyó las 4 páginas de las notas a mano que ya había |
| «LUZ» escrito con trazos en una nota nueva, al salir de ella | La pasada leyó 1 página; buscar «luz» da «A mano · pág. 1 · LUZ» |
| Página 2 con «TEN» en la misma nota | La pasada leyó solo esa página; buscar «ten» da «pág. 2» y el resultado abre la nota en la página 2/2 |
| Búsqueda en texto | «hola» encuentra el texto que «Convertir a texto» había añadido a una nota a mano |
| Sin resultados | «Ninguna nota contiene «zzz».» |
| Ajustes | Opción activada y «0 páginas pendientes de transcribir.» tras las pasadas |

No se comprobó desde Nextcloud que `libreta.text.json` llegara al servidor (los datos del snap de Nextcloud son de
root); la sincronización terminó sin fallos y el fichero va en la carpeta de adjuntos, que se sincroniza como el
resto. Los casos sin sesión, sin red y con el límite del plan se cubren en el código (la pasada para al primer error
y no reintenta) pero no se provocaron en la tablet.

## Hecho cuando

- Una palabra escrita a mano aparece en la búsqueda poco después de salir de la nota, sin hacer nada más.
- Retocar una página solo vuelve a transcribir esa página; una página vacía no llama al modelo.
- El resultado abre la nota en la página donde está.
- La transcripción viaja a Nextcloud y acompaña a la nota al moverla.
- Sin sesión, sin red o con el límite alcanzado, no hay errores visibles ni reintentos en bucle.
- Tests de `InkTranscripts`, del códec y de `NoteSearch` en `:core`; probado en la tablet.
