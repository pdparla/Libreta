# Fase 6: notas a mano

Lienzo para escribir o dibujar con el dedo o con un lápiz capacitivo (AmberVec). Cada nota se sincroniza con
Nextcloud como **Markdown, una imagen por página y los trazos**, de modo que Nextcloud Notes muestra las páginas
y la tablet puede seguir editándolas. Es la base de la Fase 7 ([`docs/07-ia.md`](07-ia.md)), que pasa la escritura y
la voz a texto.

## Qué hace

Desde la lista, el icono del lápiz («Nota a mano») abre una página en blanco. La nota solo se crea cuando se
dibuja algo; su título por defecto es `Nota a mano AAAA-MM-DD HH.MM`. Desde el menú se puede cambiar el título,
la categoría o el papel, editar el texto del Markdown o borrar la nota.

| Herramienta | Funcionamiento |
|---|---|
| Pluma | Botón de color, que abre un panel de 72 colores (grises y 12 tonos en 5 intensidades), y tres grosores |
| Borrador | **Donde toco** (por defecto): borra solo la tinta bajo el dedo y corta el trazo en dos. **Trazo entero**: quita la línea completa. Tres tamaños, con un círculo que muestra la zona mientras se borra |
| Deshacer / rehacer | Hasta 100 pasos. Una pasada del borrador es un solo paso |
| Páginas | Anterior y siguiente, y añadir página detrás de la actual, del mismo tamaño y papel |
| Papel | Pautado (por defecto), cuadrícula o en blanco, por página |

La segunda fila de la barra muestra las opciones de la herramienta elegida. La app recuerda el último color,
grosor y borrador, también entre notas distintas.

**Guardado.** Se guarda al salir de la nota o al apagar la pantalla, y además **3 s después de dejar de tocar
la pantalla** si hubo cambios. Mientras se escribe no se guarda: guardar renderiza la página, y eso se notaría en
el trazo. Si la app se cierra de golpe, como mucho se pierden los trazos de los últimos 3 s; está comprobado
matando la app con `am force-stop`.

## Formato en Nextcloud

```
Notas/
  Categoría/
    Nota a mano 2026-10-11 20.20.md
    .attachments.5wchxopbrt/
      libreta.ink.json
      pagina-1.png
      pagina-2.png
```

```markdown
# Nota a mano 2026-10-11 20.20

(texto escrito después o reconocido de la escritura; la tablet no lo toca)

![Página 1](.attachments.5wchxopbrt/pagina-1.png)

![Página 2](.attachments.5wchxopbrt/pagina-2.png)

[libreta-ink]: .attachments.5wchxopbrt/libreta.ink.json
```

- **Carpeta `.attachments.<id>`:** es la convención de Nextcloud Notes para las imágenes pegadas, así que Notes
  muestra las páginas sin hacer nada más. El `<id>` son 10 caracteres aleatorios en base 36.
- **Imágenes PNG** a 2 px por unidad de página (1 dp de la tablet) desde la Fase 9 (antes 1 px; ver
  [`09-zoom.md`](09-zoom.md)), con el papel incluido. Solo se vuelven a renderizar las páginas que cambian.
- **Trazos** (`libreta.ink.json`): cada trazo guarda su color, grosor, hora de inicio y puntos `x, y, t` (t en ms
  desde el inicio del trazo). Las coordenadas se redondean a 0,1. El orden y la velocidad de los trazos sirven
  para el reconocimiento de escritura de la Fase 7.

  ```json
  {"format":"libreta-ink","version":1,"pages":[{"width":600,"height":900,"paper":"lined","strokes":[
    {"color":"#ff202124","width":3.5,"start":1760197500000,"points":[12.5,40,0,13,41.5,16]}]}]}
  ```

- **Línea `[libreta-ink]: …`:** es una *definición de referencia* de Markdown, que los visores no muestran.
  Marca la nota como nota a mano y dice dónde están los trazos. Al guardar, la tablet solo gestiona las líneas
  de imagen y esta línea; las demás son del usuario y se respetan.

### El editor de Nextcloud Notes quita la línea oculta

Comprobado en este servidor: al abrir una nota a mano en Nextcloud Notes en el navegador, su editor la guarda de
nuevo sin la línea `[libreta-ink]`, porque ningún enlace la usa. Las imágenes se mantienen. Por eso **una nota
también cuenta como nota a mano si alguna carpeta `.attachments.*` que enlaza contiene `libreta.ink.json`**. Así
la reconocen la lista y el editor (`InkNoteFormat.inkFileCandidates`, `InkNoteService.isInkNote`), y la próxima
vez que la tablet la guarda vuelve a escribir la línea.

## Sincronización de adjuntos

- **Ficheros binarios:** la sincronización transfiere el contenido como bytes. `NotePath` distingue entre
  notas (`.md`, `.txt`) y adjuntos, que son ficheros con extensión permitida (imágenes, `json`, audio, `pdf`)
  directamente dentro de una carpeta `.attachments.<id>/`. Las demás carpetas ocultas se siguen ignorando.
- **WebDAV:** el listado entra en las carpetas `.attachments.*`, y cada `PUT` lleva el `Content-Type` de su
  extensión.
- **Los adjuntos siguen a su nota:** al moverla de categoría se copian a la nueva carpeta y se borran de la
  vieja, y al borrar la nota se borran con ella (`NoteService`, `NoteAttachments`).
- **Caché de hashes:** en local, la versión de cada fichero es su hash. El hash se recalcula solo si cambian la
  fecha o el tamaño del fichero, así que una sincronización sin cambios no vuelve a leer las imágenes.
- **Ficheros bloqueados (HTTP 423):** Nextcloud bloquea una nota mientras está abierta en el editor web. Ese
  fichero cuenta como fallo de esa nota y se reintenta en la siguiente sincronización. Antes, un 423 cortaba
  toda la sincronización. El log de `Libreta` da el código y el método HTTP del error que corta una
  sincronización, sin nombres de notas.

## Pantalla y lápiz

La pantalla de la SM-T113 es capacitiva (MELFAS MMS449) y no admite lápiz activo. El AmberVec es un lápiz
capacitivo: para la pantalla es un dedo fino. El rechazo de la palma se basa en el tamaño del contacto
(`touchMajor`): `InkView` dibuja con el primer contacto que no supere `PALM_TOUCH_MAJOR` e ignora el resto.

**Pendiente de calibrar cuando llegue el lápiz.** Ahora el umbral vale `0`, que acepta cualquier contacto y es
lo que hace falta para escribir con el dedo. Para calibrar:

1. Instalar una build debug: `InkView` registra cada contacto en logcat con la etiqueta `LibretaInk`.
2. Escribir con el lápiz apoyando la palma, y hacer también algunos trazos con el dedo.
3. Leer los tamaños con `adb logcat -d -s LibretaInk:D` y fijar `PALM_TOUCH_MAJOR`, en `InkNoteActivity`, entre
   el tamaño del lápiz y el de la palma.

## Compatibilidad con API 19

Animal Sniffer pasó de la firma `android-api-level-19` a **Gummy Bears** (`gummy-bears-api-19`). La firma
anterior rechazaba `Integer.hashCode(int)`, que Kotlin usa en las `data class` y que D8 reescribe para API 19.
Gummy Bears incluye esos métodos y sigue detectando lo que de verdad falla en la tablet (`java.time`,
`Map.getOrDefault`), comprobado con una clase de prueba.

## Verificado en la tablet

- Dibujo con colores del panel y los tres grosores, papel pautado y dos páginas.
- La nota aparece en la lista como «A mano» y sube 4 ficheros: `.md`, trazos y 2 PNG. Al reabrirla se
  recuperan las páginas y los colores.
- Borrador «Donde toco»: corta la línea por donde pasa el dedo, y con un solo deshacer vuelve entera.
  «Trazo entero» borra la línea completa.
- Autoguardado: tras dibujar, esperar 4 s y matar la app, el trazo sigue ahí al reabrir.
- Una nota editada en Nextcloud Notes, que perdió la línea `[libreta-ink]`, se reconoce de nuevo como nota a
  mano.
- Con una nota bloqueada en Nextcloud, la sincronización sube el resto y avisa de «una nota se reintentará».

## Siguiente: Fase 7

La Fase 7 ([`docs/07-ia.md`](07-ia.md)) cambió el plan: la escritura se pasa a texto y se conversa sobre ella
desde la propia tablet, con la suscripción de ChatGPT del usuario; el servidor solo se usará para el dictado
(faster-whisper).
