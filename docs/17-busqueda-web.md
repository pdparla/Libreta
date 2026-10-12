# Fase 17: búsqueda web en el chat

Pedido tras usar la app (2026-10-12): que la IA pueda buscar en internet cuando conversa, además de en las notas
([`docs/roadmap.md`](roadmap.md)).

## Qué hace

- En los dos chats (sobre un recorte y sobre toda la libreta, Fases 7 y 12) la IA puede **buscar en la web** cuando la
  pregunta lo necesita: datos actuales, algo que no está en las notas, comprobar lo que dice un recorte. Decide ella;
  no hay que activarlo.
- Mientras busca se ve una línea gris, como con las herramientas de las notas: «Buscando en la web «…»…».
- La respuesta **cita las páginas** con enlaces. Tocar uno muestra la dirección, con «Abrir» (en el navegador de la
  tablet, si lo hay; la de casa no tiene desde la Fase 5, y lo dice) y «Copiar».
- Al guardar la conversación como nota, los enlaces se guardan con ella.

## Diseño

- **Herramienta oficial de OpenAI.** Se añade `{"type": "web_search"}` a las herramientas de la Responses API
  ([guía oficial](https://developers.openai.com/api/docs/guides/tools-web-search)), con `user_location` aproximada en
  España para que las búsquedas locales tengan sentido. La búsqueda la hace OpenAI: la app no necesita otro servicio ni
  clave, y la tablet no navega.
- **Lo que llega.** Cada búsqueda es un elemento `web_search_call` con `action.query`, que se anuncia en cuanto llega
  (`response.output_item.done`); la respuesta es un mensaje con anotaciones `url_citation`. Las citas que el modelo no
  haya puesto ya como enlace en el texto se añaden al final como «Fuentes».
- **Historial.** Como todo (`store: false`), la conversación entera se reenvía en cada turno, con los
  `web_search_call` dentro: la API los acepta de vuelta con la sesión del plan (comprobado en la tablet).
- `AiAssistant.send` tiene `webSearch` (activada por defecto) por si alguna vez hace falta un chat sin web.
- **Límites del plan.** Una búsqueda gasta uso del plan de ChatGPT como cualquier turno; las instrucciones piden buscar
  solo cuando haga falta y preferir las notas para lo que es del usuario.

## Verificado en la tablet (2026-10-12)

En «Preguntar a la libreta», con la sesión del plan de ChatGPT.

| Prueba | Resultado |
|---|---|
| «Qué tiempo hace hoy en Madrid» | Línea gris «Buscando en la web «weather: Madrid, España»…» y respuesta con el tiempo del día |
| «Quién ganó el último Balón de Oro. Cita la fuente» (segundo turno) | Nueva búsqueda; el historial con la búsqueda anterior se acepta; respuesta con enlaces a ballondor.com y uefa.com |
| Tocar un enlace | Diálogo con la dirección; «Abrir» avisa de que no hay navegador; «Copiar» la copia |

## Hecho cuando

- Una pregunta sobre algo actual se contesta buscando, con la línea gris y enlaces a las fuentes.
- Una pregunta sobre las notas sigue usando las herramientas de las notas, sin buscar en la web.
- Se puede seguir conversando después de una búsqueda.
- Tests con MockWebServer: la búsqueda se anuncia, las fuentes se añaden y el historial se reenvía sin errores.
