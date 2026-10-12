# Voice dictation

Dictation turns your voice into text. You tap the microphone, speak, and tap «Parar» (Stop). The text comes back to the tablet in a few seconds.

Libreta does not transcribe speech on the tablet. It sends the recording to a dictation service that you run on your own server. The service must be set up and paired with the tablet first. See [Set up the dictation server](../setup/dictation-server.md).

If dictation is not set up, tapping the microphone shows the message «El dictado no está configurado» (Dictation is not configured) with the command to pair the tablet.

## Where the microphone is

| Screen | Where | What happens with the text |
|---|---|---|
| Text note | Microphone in the top bar | The text is inserted at the cursor. From the formatted view, Libreta switches to the editor and adds the text at the end as a new paragraph. |
| Handwritten note | Microphone in the first toolbar row | A box titled «Texto dictado» (Dictated text) shows the text. Edit it, then tap «Añadir a la nota» (Add to the note) to add it above the pages, or «Copiar» (Copy). |
| Chat with the assistant | Microphone next to «Enviar» | The text goes into the message box so you can check it before you send it. If the box already has text, the dictation is added after it. |

## Dictate

1. Tap the microphone. A box titled «Dictado» (Dictation) appears and the recording starts. It shows the time, for example «Grabando… 0:12 de 5:00».
2. Speak.
3. Tap «Parar» to finish. The message «Transcribiendo…» (Transcribing…) appears.
4. The text appears as described in the table above.

To discard a recording, tap «Cancelar» (Cancel). You can also cancel while the text is being transcribed.

### Limits

- A recording stops by itself after **5 minutes**, and Libreta sends what it recorded.
- Closing the box or leaving the screen discards the recording.
- Silence is removed before transcription, so a pause does not slow it down. A minute of speech takes about 15 seconds to transcribe, and less if it has pauses.
- The service transcribes in a fixed language, Spanish by default.

## If something goes wrong

| Message | What to do |
|---|---|
| «No se pudo usar el micrófono.» (The microphone could not be used.) | Close other apps that use the microphone and try again. |
| «No se ha entendido nada en la grabación.» (Nothing was understood in the recording.) | Speak closer to the microphone and try again. |
| «No se pudo transcribir: …» (It could not be transcribed: …) | The reason follows. Check that the tablet reaches the server. |
| «el servicio de dictado no acepta el token; vuelve a emparejar…» | The pairing is out of date. Pair the tablet again. See [Set up the dictation server](../setup/dictation-server.md). |

To check that the tablet can reach the dictation service, open «Ajustes» and tap «Probar dictado» (Test dictation). See [Settings](../reference/settings.md#dictation).

Your recordings are sent only to your own server. The service logs only durations and lengths, never what you say.

## Related

- [Text notes](notes.md)
- [Handwritten notes](handwriting.md)
- [The assistant](assistant.md)
