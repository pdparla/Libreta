# Sync

Libreta works offline and synchronises with the notes folder of a Nextcloud server over WebDAV. The notes are
the same files that the Nextcloud Notes app shows, so they also appear there. This document explains the engine,
the rules it follows to avoid losing text, and the WebDAV specifics.

To connect a tablet, see [Connect to Nextcloud](../setup/nextcloud.md). For the files themselves, see
[Note format](../reference/note-format.md).

## Overview

```
            NoteRepository  (port: list / read / write / delete with WritePrecondition)
              ^          ^
   local store         SyncStrategy (remote side, one protocol)
   (.md files)            ^
                     NextcloudWebDavSync   (later, optionally, the Notes API)

 SyncEngine ── uses ──> SyncPlanner (pure function, three-way comparison)
     │                    local x remote x SyncBase (state of the last sync)
     └── persists ──> SyncStateStore
```

| Type | Module | Role |
|---|---|---|
| `NoteRepository` | `:core` | The port both sides implement: `list`, `read`, `write` and `delete`, with a `WritePrecondition`. |
| `SyncStrategy` | `:core` | A `NoteRepository` that is the remote side, for one protocol, plus `checkConnection()`. |
| `SyncPlanner` | `:core` | A pure function with no I/O. It compares each file with the last synced state and returns actions. |
| `SyncEngine` | `:core` | Lists both sides, asks the planner what to do, and carries out the actions. |
| `SyncStateStore` | `:core` | Persists `SyncBase`, the versions of both sides at the last sync. |
| `NextcloudWebDavSync` | `:app` | The `SyncStrategy` for Nextcloud over WebDAV. |
| `SyncCoordinator`, `SyncService` | `:app` | Decide when to sync, run the engine and report to the screens. |

## Rules of the engine

### Opaque versions

Each side gives a file a version, and the only valid operation on a version is to compare two of them for equality.
Locally the version is a hash of the content. Remotely it's the ETag.

A file has changed on a side when its version differs from the one saved in `SyncBase` for that side. The two
kinds of version are never compared with each other.

### Optimistic concurrency

Writes carry a `WritePrecondition`:

| Precondition | Meaning | HTTP |
|---|---|---|
| `None` | Write unconditionally. | No header. |
| `MustNotExist` | Create only. | `If-None-Match: *` |
| `MustMatch(version)` | Replace or delete only this exact version. | `If-Match: <etag>` |

The server enforces the precondition, so a write that loses a race fails cleanly with 412 and is race-free.
The engine never forces a write that fails. It turns the failure into a conflict resolution, or leaves the file for
the next sync.

### What the planner decides

The planner takes the local version, the remote version and the saved base of every path. The rows below show the
outcome for a path that has a base:

| Local | Remote | Action |
|---|---|---|
| Unchanged | Unchanged | Nothing. |
| Unchanged | Modified | Download (`MustMatch` the local version). |
| Unchanged | Deleted | Delete the local file. |
| Modified | Unchanged | Upload (`MustMatch` the remote ETag). |
| Modified | Modified | Resolve a conflict. |
| Modified | Deleted | Upload (`MustNotExist`): the edit wins. |
| Deleted | Unchanged | Delete the remote file (`MustMatch` the remote ETag). |
| Deleted | Modified | Download (`MustNotExist`): the edit wins. |
| Deleted | Deleted | Forget the path. |

A path with no base was never synced. If it exists on both sides, it's a conflict to resolve; if it exists on one
side, the other side gets a copy (a `MustNotExist` upload or download).

### Conflicts

When both sides changed a note:

1. If the two texts are byte-identical, there's no conflict. The engine records the new versions and moves on.
2. Otherwise the remote text keeps the original name, because other devices already show it.
3. The local text is saved as a conflict copy, `Name (conflicto YYYY-MM-DD).md`, on both sides. The engine adds
   ` 2`, ` 3` and so on if the name is taken. The copy is written locally first, so it survives a failure in any
   later step.

If one side vanishes while the engine looks at it, whichever content is left is kept on both sides.

### Edit against delete

An edit always beats a deletion. If you edit a note on the tablet while someone deletes it on the server (or the
other way round), the note comes back. The same holds when a delete loses a race: if the remote note changes
between listing and deleting, the `If-Match` delete fails with 412 and the engine downloads the new version
instead. Syncing never loses text.

### Failures and progress

- If there's no network, or any other store becomes unavailable (`StoreUnavailableException`), the run stops. It
  changes nothing further, and the report says why.
- Any other store error, such as a locked note, only skips that note and is listed as a failure.
- The sync state is saved after the run even if it stopped halfway, so an interrupted sync resumes where it left
  off. If the run changed nothing, the engine skips the rewrite and its `fsync`.
- A missing or unreadable state file means "never synced". That is safe: the next sync compares the contents, and
  only notes whose texts differ become conflict copies.

### State file

`files/sync-state.tsv` has a header line, `# libreta sync state v1`, then one line per path:
`path<TAB>localVersion<TAB>remoteVersion`. Paths and versions never contain tabs or line breaks: `NotePath`
rejects control characters, and the versions are hashes and ETags. The store keeps the last loaded or saved state
in memory with the file's size and modification time, and reads the file again only if they differ.

## Which files sync

A path is either a note (`.md`, `.txt`) or an attachment: a file with an allowed extension (images, `json`,
audio, `pdf`) directly inside a `.attachments.<id>/` folder. Other hidden folders and other files are ignored.
Content is transferred as bytes, so images and strokes sync like text. Attachments follow their note when it moves
category or is deleted. A handwritten note is a Markdown file plus its attachments folder; see
[Note format](../reference/note-format.md).

## WebDAV with Nextcloud

`NextcloudWebDavSync` talks to `https://<server>/remote.php/dav/files/<user>/<notes folder>/`.

### Listing

The listing walks the tree one folder at a time with `PROPFIND` and `Depth: 1`, because Nextcloud may refuse
`Depth: infinity`. The request asks only for `getetag` and `resourcetype`. A note without an ETag aborts the
listing, because its version can't be known.

A folder that's missing (404) when the app reads the notes folder aborts the sync. It's never read as "all notes
were deleted", which would empty the tablet.

### Listing cache

Every handwritten note has its own `.attachments.<id>/` folder, so a full walk would cost one `PROPFIND` per
folder on every sync. Nextcloud changes a folder's ETag whenever anything inside it changes, at any depth. The
client uses that:

- `NextcloudClientFactory` keeps one `ListingCache` per account (the notes URL), in memory and across syncs. For
  each folder it holds the listing found below it and the folder's ETag.
- The root is always read. For each subfolder, if its ETag matches the cached one, the cached subtree is reused
  without a request. Only changed folders, with their parent folders, are read again.
- A sync with no remote changes makes one `PROPFIND`.
- The cache is replaced only when a walk finishes. A failure halfway leaves the previous cache untouched, and
  deleted folders drop out.
- Folders without an ETag are never cached, so a server that doesn't send ETags is simply listed in full.
- Every write or delete the app makes invalidates its folder and the parents, even when the request fails.
- The cache lives in memory. After a restart the first listing is complete.

The cache holds an immutable map that's swapped as a whole, and a version counter that an invalidation increments,
so a walk that raced with a write can't store a stale result.

### Versions

The version of a remote file is its ETag, taken from `getetag` in a `PROPFIND`, and from the `ETag` (or `OC-ETag`)
header of a `GET` or `PUT`. If a server sends none on a write, the client asks with a `PROPFIND` of `Depth: 0`.

### Writes

- `PUT` with `If-None-Match: *` to create, or `If-Match` to update. `DELETE` carries `If-Match`. The `Content-Type`
  comes from the file extension.
- A 412 becomes a `PreconditionFailedException`, which the engine resolves as a conflict.
- New categories are created with `MKCOL`, one level at a time. A 405 means the folder already exists. If a
  `PUT` answers 404 or 409, the category was deleted since it was listed: the client creates it again and retries
  once.
- A 423 means Nextcloud locked the note, typically because it's open in the web editor. That file counts as a
  failure of its own and is retried in the next sync, without stopping the others.
- Any other unexpected status is reported as `HttpStatusException` with the HTTP method and code only.

### Redirects are off

The clients are built with `followRedirects(false)`, and the URL must be HTTPS. A redirect is therefore treated as an
unexpected status instead of being followed. Following it could change the method of a request or send
credentials somewhere the user didn't configure.

### Credentials

The client sends HTTP Basic authentication with your user name and an app password. If no password is set, it sends
no credentials, so a TLS test on its own doesn't count as a failed login. See
[architecture](architecture.md#local-storage) for where the password is stored.

For the TLS setup of this client, see [TLS](tls.md).

## When a sync runs

A sync starts:

- when the app comes to the foreground and when it leaves it (`ActivityLifecycleCallbacks` in `LibretaApp`
  counts started activities; a rotation doesn't count, and turning the screen off stops the activity, so the app
  also syncs on screen off and on);
- when you tap the sync button in the note list;
- when you save the settings.

The editors save in `onPause`, before `onStop`, so the sync on leaving includes your last edit.

`SyncService` is an `IntentService`, because WorkManager needs API 21. Each trigger calls `SyncService.request`,
which counts the request and starts the service. The service handles requests one at a time on its worker thread,
and requests that piled up while a sync ran are served by a single further run.

`SyncCoordinator` loads the current settings, builds the remote with `NextcloudClientFactory`, runs the engine and
publishes a `SyncStatus` to its listeners on the main thread: `Idle`, `NotConfigured`, `Running`, `Finished` with
the report, or `Failed`. It writes one log line per run with counts and, for failures, the file kind, extension
and cause (for HTTP errors, the method and status). Note names never go to the log.

If the server, user or notes folder in the settings changes, `SyncCoordinator.changeAccount` deletes the sync
state first, so the old account's versions don't apply to the new one.
