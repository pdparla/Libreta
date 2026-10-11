# Fase 14: pasar a limpio una nota entera

Convertir toda una nota a mano en una nota de texto legible y estructurada, sin tocar la original
([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- **Menú de la nota a mano → «Pasar a limpio».** Crea una **nota de texto nueva** en la misma categoría, titulada
  «*Título* (a limpio)», con todo lo escrito en Markdown: títulos, listas, tablas. Al terminar la abre, ya formateada
  (Fase 7).
- Mientras trabaja muestra el progreso («Leyendo la página 2 de 3…», «Ordenando el texto…») y se puede cancelar.
- La nota nueva acaba con una línea que dice de qué nota a mano sale y cuándo.
- La nota a mano no cambia.

## Diseño

- **Lectura de las páginas.** Se aprovecha la transcripción de la Fase 11: una página cuyo `libreta.text.json` está al
  día (misma huella) no se vuelve a enviar. Las que faltan se leen ahora con `gpt-6.1-sol` (el modelo de «Convertir a
  texto»), una a una, y su lectura se guarda también en `libreta.text.json`, así que la búsqueda sale ganando. Las
  páginas vacías se saltan.
- **Ordenar.** Con una sola página escrita, su texto es el resultado. Con varias, una petición más a `gpt-6.1-sol` las
  une en un solo Markdown: sin repetir, sin cortar frases entre páginas, con títulos y listas donde los haya, y **sin
  añadir nada que no esté escrito**. Los títulos marcados en la Fase 13 se le pasan como guía de la estructura.
- **Nota nueva.** `NoteService.create`, como cualquier nota de texto: se sincroniza con Nextcloud. Si ya existe una con
  ese título, se numera («(2)»), como siempre.
- **Fórmulas.** Se piden en LaTeX (`$…$`) y quedan como texto. El módulo `ext-latex` de Markwon 4.6.2 es compatible con
  API 19 (lo mismo que su dependencia `jlatexmath-android` 0.2.0, comprobado en Maven Central), pero añade unos 650 KB
  de fuentes a una app de 1,1 MB; queda para cuando haga falta.
- **Errores.** Si falla una petición, se avisa con el mensaje habitual de la IA y no se crea nada. Las páginas leídas
  hasta ese momento quedan guardadas para la próxima vez.

## Verificado en la tablet (2026-10-12)

Sobre una nota a mano de dos páginas: «LUZ» en la página 1 (marcada como título y etiqueta) y «TEN» en la página 2.

| Prueba | Resultado |
|---|---|
| Menú → Pasar a limpio | Progreso («Ordenando el texto…») y nota «Nota a mano 2026-10-12 01.02 (a limpio)» abierta y formateada |
| Contenido | `## LUZ` (el título marcado) y «TEN», con el pie de origen y fecha |
| Nota a mano | Sin cambios |

**Fallos encontrados y corregidos:**

- La primera nota a limpio solo traía «TEN». Las instrucciones piden ahora conservar todo el texto y los títulos
  marcados, y no añadir un título general.
- Desde la Fase 13, `libreta.ink.json` se escribía siempre como versión 2, lo que cambiaba la huella de las páginas y
  las daba por no transcritas. `InkJson.encode` escribe la versión 1 cuando no hay marcas, así que las
  transcripciones antiguas siguen valiendo (test de regresión en `InkTest`).

Cancelar y el error de una petición están cubiertos por el código compartido con «Convertir a texto»; no se
repitieron en la tablet.

## Hecho cuando

- Una nota de varias páginas da una nota de texto legible y fiel, en la misma categoría, y la nota a mano no cambia.
- Las páginas ya transcritas no se vuelven a enviar.
- Cancelar no crea nada.
- Probado en la tablet (con letra real, cuando la haya).
