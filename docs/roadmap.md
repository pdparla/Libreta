# Roadmap

This page lists what Libreta does not do yet. It has no dates and no commitments: the order shows interest, not a
schedule. For what the app does today, see the [documentation index](README.md).

Every idea has to respect the constraints in [Compatibility](reference/compatibility.md): Android 4.4 (API 19), about
1 GB of RAM with 64 MB per app, and no modern browser. Ideas that use the AI also live within the limits of plan
usage, which forces `store: false` and a resent conversation on every turn
(see [AI design](design/ai.md)).

## Ideas

From most to least interesting:

| Idea | What it would do | Notes |
|---|---|---|
| **Paper to calendar** | Select "doctor appointment tomorrow at 9:30" or a to-do list and create the event or the tasks in the Nextcloud Calendar and Tasks (CalDAV) | Reuses the selection and the model's tools. Needs CalDAV support in the sync layer |
| **Record lessons or meetings with audio synced to the strokes** | A long recording transcribed in parts on the server. Tapping a stroke plays the audio from that moment. A summary at the end | Strokes already record the time of each point. It is the most distinctive idea and the longest |
| **Page templates** | Daily, weekly, Cornell, to-do and meeting-minutes pages | Extends the current paper types |
| **Review** | A weekly summary, study cards or questions, saved as notes | Builds on the notebook tools |
| **Annotate PDFs from Nextcloud** | Write on top of a PDF | `PdfRenderer` needs API 21, so a server would have to convert the pages to images |
| **Handwritten math** | Solve or check formulas | Worth it only for study or formula-heavy work |
| **Links between notes** | `[[note]]` links | Easier now that the notebook tools can resolve notes |

## Known gaps

- **Stylus calibration.** Palm rejection uses the contact size, and its threshold is `0`, which accepts every contact.
  That is what writing with a finger needs. With a capacitive stylus, set `PALM_TOUCH_MAJOR` between the size of the
  stylus and the size of a palm, using the contact sizes that debug builds log with the tag `LibretaInk`. The two-finger
  gesture thresholds (150 ms and 8 dp) also need checking with a real hand and a stylus.
- **Crowded toolbar in the note list.** With six icons, the category selector can be cut off ("Todas las."). It still
  works. It needs a layout pass.
