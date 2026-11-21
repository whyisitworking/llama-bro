# Searching Hugging Face: paging and filters (addendum to models-page.md)

Status: built, 2026-11-21: paging is D127, the filter sheet D128 (in [../decisions.md](../decisions.md)); agreed with
the maintainer, 2026-11-20. Extends [models-page.md](models-page.md) §2 (Search mode) and §1 (offline
first). Replaces that design's "no paging" non-goal.

## Why

Search asked Hugging Face for one page of 40 GGUF uploads, most downloaded first. A newcomer searching "qwen" saw a wall
of 27B models the phone can't run, and nothing past the first 40. The maintainer asked for paging with Android's own Paging 3
library (no homegrown pager), for the API's own criteria to shape results, and for an advanced mode.

## What the Hub accepts (tested against the live API, 2026-11-20)

- `search` (substring of the repo id), `filter=gguf` (or `apps=llama.cpp`), `author`, `gated` (`false` = only repos
  anyone can download), `pipeline_tag` (`image-text-to-text` = takes pictures; many GGUF uploads leave it blank, so it
  can include, never safely exclude), `num_parameters` (`max:4B`, `min:1B,max:4B`; it trusts each upload's reported
  count, which is sometimes wrong), `sort` (`downloads`, `trendingScore`, `likes`, `createdAt`, `lastModified`),
  `expand[]` (e.g. `gguf` for the parameter count), and paging through the `Link: rel="next"` cursor (the SDK's
  `SearchCursor`).

## The design

1. **Paging 3, offline first.** Hub results are a `Pager` whose `PagingSource` reads Room and whose `RemoteMediator`
   fetches the next page with the SDK's cursor into Room (results keyed by query + filters, in the Hub's order, with the
   next cursor kept per query). Scrolling near the end loads the next page; pages already stored show at once, offline
   too; a refresh on open replaces the first page in place. The list is the Paging Compose `LazyPagingItems` in the same
   one LazyColumn as the local matches (models-page.md §2), appended below them without moving anything above.
2. **Order is the Hub's** (the sort chosen), never reordered on the phone: reordering per page would reshuffle the list
   as pages arrive. Uploads of one model on later pages merge into the model's existing row (the SDK groups per page).
3. **Fits this phone by default.** The search sends `num_parameters=max:<cap>`, the cap being the largest parameter
   count that isn't Too big on this phone at the usual quantisation (from the same device facts and F175 guess that draw
   the fit symbol; derived in F176, built in D127). The filter shows "Fits this phone" as the size choice; "Any size"
   removes the cap.
4. **Gated models are always hidden** (`gated=false`): the app can't accept a model's terms. No control for it.
5. **Advanced filters**, one tap away (a filter action in the search field's trailing slot, opening a sheet), off by
   default, remembered across launches:
   - **Sort:** Most downloaded (default), Trending, Most liked, Newest, Recently updated.
   - **Size:** Fits this phone (default), Any size, or a custom min–max parameter range.
   - **Sees images:** only models tagged as taking pictures (says that untagged ones are left out).
   When any filter differs from the default, the action shows it (a dot) and the "From Hugging Face" label names the
   active filters in one muted line with a way to reset them.
6. **Local matches** (your models, suggestions) are never filtered by these: they're yours, or already chosen for the
   phone.

## SDK

`SearchOptions` gains what the Hub accepts and the app needs: a parameter range, `gated = false` (hide gated), and a
`Newest` sort; the SDK reports facts and passes the app's choices, it decides nothing (sdk-no-app-policy). `apiDump`
with the change.

## Data

A Room migration from version 5 (already on the test phone) adds the paged results and their cursors.

## Left to the builder

The tables' shape, the cap's exact derivation (documented as a finding), the filter sheet's layout within the design
system, page size (the SDK's 40 or the Hub's maximum of 100).
