# NUVA v2.5 — Target-aware file mutations and photo editing handoff

Fourth safe non-financial implementation phase. Every file mutation is user-present and uses Android
Storage Access Framework URIs—never a model-generated path.

## Commands

```text
file rename koro new name report.pdf
file copy koro
file move koro
file delete koro
photo edit koro
photo crop koro
```

## Rename/delete safety flow

1. Command-level blocking confirmation.
2. Android picker: user selects the exact source file.
3. NUVA shows the selected display name and operation.
4. A second target-aware confirmation is required.
5. `DocumentsContract.renameDocument` or `deleteDocument` runs with the picker grant.

A provider that does not permit write/rename/delete returns an honest failure.

## Copy/move safety flow

1. Command-level blocking confirmation.
2. User picks source file.
3. User picks destination folder.
4. NUVA shows source, destination and operation for a second confirmation.
5. Copy creates a document in the chosen tree and streams bytes with a bounded buffer.
6. Move performs copy first, then attempts source deletion. If source deletion is denied, NUVA keeps
   both files and explicitly reports that the result is a copy—not a completed move.

## Photo editor handoff

The user picks one image. NUVA opens an installed editor through `ACTION_EDIT` with temporary
read/write grants. Crop/rotate/filter/save are performed in the visible editor and final Save remains
under user control. NUVA does not silently alter media.

## Still unsupported

- Broad storage or gallery scans.
- Arbitrary path access.
- Hidden/bulk deletion.
- Automatic face/date/location photo selection.
- Background image editing or saving without a visible editor.
