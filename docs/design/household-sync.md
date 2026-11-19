# Household sync (design, not built yet)

Phones of the same household (for example four family members) share one set of receipts,
products, categories, budgets and household settings by syncing directly with each other on
the same Wi-Fi. No server, no account, no internet traffic.

## Decisions (owner, 2026-11-19)

1. **Off by default.** A switch turns it on and off. While it is off the app opens no socket
   and announces nothing on the network.
2. **Everything lives on one dedicated screen, not a tab.** The Sync screen holds the on/off
   switch, the sync history (an audit log: when, with which phone, done or failed and why,
   what changed), the paired phones, the "Sync now" button, pairing and leaving. Nothing about
   sync appears anywhere else, except the one row in Settings that opens the screen and the
   synced mark on receipts (decision 4). The bottom bar does not change.
3. **Receipt URLs stay on the phone that scanned them.** They are never sent to another phone.
   A receipt received by sync has no URL on that phone.
4. **A synced receipt is marked as such**, not with who added it: "synced from another phone"
   on the row and in the detail. No member names or attribution are stored.
5. **Sync runs only while the app is open.** No foreground service, no background work.
6. **Household settings are shared:** name, annual income, budgets, e-payment target,
   categories, merchant names and default categories, products and mappings.

## How it works

### Pairing (once per phone)

- The first phone to turn sync on creates a **household sync key**: 32 random bytes, wrapped
  with an Android Keystore key like the database key (`KeyManager`), in `noBackupFilesDir`.
  Each install also gets a random **device id**.
- On the Sync screen, "Add a phone" shows a QR code with: protocol version, the sync key, this
  phone's device id, and its current local IP and port (the fallback when discovery fails).
- The new phone scans it ("Join household" on its Sync screen; reuses the camera scanner of
  feature:scan), stores the key, and runs a first sync right away. Its existing data is
  merged in, not replaced: the first sync is an ordinary sync.
- Anyone holding the key is a member. Every paired phone can add further phones.
- "Leave household sync" (on the screen) deletes the key and the paired phone list on this
  phone. The data stays. Turning the switch off does **not** forget the pairing: turning it on
  again resumes.

### Discovery and triggers

- While the app is in the foreground and sync is on: listen on a TCP port and announce
  `_eksoda._tcp` with `NsdManager` (platform, no Google Play services). Discover the others.
  Stop both when the app goes to the background (`ProcessLifecycleOwner`) or the switch goes off.
- **Automatic:** when a paired phone is found and the last successful sync with it is older
  than 15 minutes, sync with it.
- **Manual:** "Sync now" on the screen syncs with every paired phone that can be reached (found
  by NSD, else its last known address).
- Only private addresses are used, outgoing and incoming: 10/8, 172.16/12, 192.168/16,
  169.254/16, fc00::/7, fe80::/10. Anything else is refused before connecting and on accept.

### Protocol (one TCP connection, both directions)

1. Each side sends `Hello {protocol, deviceId, deviceName, schemaVersion, nonce}`.
   A session key is derived with HKDF-SHA256 from the household key and both nonces. Every
   later frame is `length (uint32 BE) | AES-256-GCM(session key, counter nonce)`. A phone
   without the key fails the first frame (`not_paired`). A different `schemaVersion` stops
   the sync (`schema_mismatch`: update the app on both phones).
2. Each side sends its **snapshot**: the `BackupArchive` NDJSON of the synced tables
   (below), gzip-compressed. Without `receipt.source_url`, without `import_job`.
3. Each side merges what it received in one `WriteCoordinator` transaction and replies
   `Done {inserted, updated, deleted}`. Both write an entry in the sync history.

The whole snapshot is sent every time. For a family this should be a few MB compressed
(measure it in phase 2). Sending only rows changed since the last sync with that phone is
an optimization for later; the merge does not change.

### What syncs

| Table | Syncs | Note |
|---|---|---|
| household, category, budget, merchant, store, product, merchant_product_mapping, receipt, receipt_item, tombstone | yes | `receipt.source_url` is left out |
| import_job | no | local queue, holds the URL |
| sync_peer, sync_event (new) | no | local; also left out of the backup file |

### Merge rules (pure Kotlin in core:engine, with tests)

Merging depends only on the two row sets, never on the order of syncs, and running it twice
changes nothing. So any phone can sync with any other, in any order, and all phones converge.

- **Same id:** the row with the later `updated_at` wins; on a tie, the larger device id.
  Rows are whole: a concurrent edit of two different fields of the same row keeps only one
  (acceptable for a household).
- **Deletes:** a tombstone wins over a row when `deleted_at >= updated_at`. Tombstones are
  merged by union (latest `deleted_at`).
- **Same thing, different ids.** Two phones that imported the same thing on their own have
  different random ids. Rows are matched by their unique key, the **smaller id is kept** on
  both sides, and child rows are repointed:

  | Table | Unique key |
  |---|---|
  | merchant | `country_code`, `vat_number` |
  | store | `merchant_id`, `store_key` |
  | receipt | `mark`, else (`issuer_country`, `issuer_vat`, `uid`), else `source_key` |
  | receipt_item | `receipt_id`, `line_number` |
  | merchant_product_mapping | `merchant_id`, `mapping_key` |
  | category | `slug` (system categories already have the same ids everywhere) |
  | budget | id is already `total` or the category id |
  | household | one row: the later `updated_at` wins |

- **Products** have no unique key. When two mappings with the same key point to different
  products, the two products are merged: the smaller id is kept, receipt items and mappings
  are repointed, and the later `updated_at` decides name, brand, category and package.
- **Receipt URL:** when a receipt row from another phone wins, the local `source_url` (if
  this phone has one) is kept.
- Analytics, budgets and Home simply see the merged rows; nothing else changes.

### The Sync screen (feature:household)

A full screen with its own destination (`SyncDestination` in `app/ui/Destinations.kt`,
wired in `MainNavHost`), opened from a "Household sync" row in Settings whose subtitle says
"Off" or "On · last synced <time>". Back returns to Settings. Follows the UI pattern in
OVERVIEW.md and `docs/design/ui-redesign.md`.

- **Switch** at the top (on/off, default off). While off, the screen shows a one-line
  explanation and the history stays readable; phones, Sync now and pairing are hidden.
- **Status:** listening or not, local network permission missing, phones found now.
- **Sync now** button.
- **Paired phones:** name (the device model, editable), last seen, last successful sync.
- **History** (newest first, paged): time, phone, done / failed, duration, inserted /
  updated / deleted counts, and the failure reason, mapped from a code like import errors:
  `peer_unreachable`, `not_paired`, `schema_mismatch`, `permission_denied`, `interrupted`,
  `merge_failed`. Kept for the last 500 entries.
- **Add a phone** (QR), **Join household** (scan), **Leave household sync**.

### Synced receipts

A receipt with no `source_url` came from another phone. The receipt row and detail show a
"synced from another phone" mark, and actions that need the URL are hidden. If this phone
later scans the same receipt, the import finds it as a duplicate and stores its URL: from then
on it counts as scanned here too.

## Changes by module

- **core:database:** version 6 (done): `sync_peer` (device id, name, paired at, last address,
  last seen, last success) and `sync_event` (id, peer device id and name, `started_by`
  auto/user, started, finished, result, error code, counts), `SyncDao`; both in
  `BackupArchive.LOCAL_TABLES` (not exported, untouched by a restore). Version 7 (phases 2
  and 5): `receipt.source_url` nullable, `tombstone.natural_key` (see open points).
- **core:engine** `sync/`: frame and crypto (javax.crypto), handshake, table descriptors
  (unique keys, foreign keys), merge planner (input: local and remote rows; output: upserts,
  deletes, id remaps), private-address check. Unit tests for every rule above.
- **core:data** `sync/`: `SyncRepository` (done: enabled flag in DataStore, peers, history
  paging, last success; to come: status, sync now, pair, join, leave), `SyncKeyStore`, NSD announce and discovery, socket listener tied to
  the app's foreground state, snapshot writer and merge applier (built on `BackupArchive`).
  Robolectric test: two in-memory databases synced through the real merge.
- **feature:settings:** the "Household sync" row in the Household card that opens the Sync
  screen ("Off", "On", or "On · last sync: <time>"). The switch itself is on the Sync screen.
- **app:** `SyncDestination` and its route in `MainNavHost`. `MainTabs` does not change.
- **feature:receipts:** the synced mark; no URL actions without a URL.
- **docs:** OVERVIEW.md (module, tables, the local network exception to "network only to
  provider hosts"), CLAUDE.md ground rules, `NoTelemetryCheck` if it inspects sockets.

## Platform notes

- With `targetSdk` 36 (today), local network access comes with `INTERNET`. When the app
  targets 37, all local network traffic needs the runtime permission `ACCESS_LOCAL_NETWORK`
  (Nearby devices group). It is then requested when the user turns sync on; when denied, the
  screen says so and offers to ask again. Don't declare it before targeting 37.
- Guest networks and some routers block traffic between devices, and some block mDNS. The
  screen must make "not found" clear instead of failing silently.

## Phases

1. **Done (2026-11-19).** Sync screen (switch, paired phones, history) and its Settings row,
   `sync_peer` / `sync_event` tables, migration 6.
2. Merge rules in core:engine with tests; snapshot writer and merge applier in core:data,
   tested with two databases. Measure snapshot size.
3. Pairing: key store, device id, QR show and scan, leave.
4. Transport and NSD: handshake, listener, discovery, automatic and manual sync, history
   entries.
5. Synced receipts: nullable URL, the mark, URL actions hidden.
6. When moving to `targetSdk` 37: the local network permission.

## Open points

- **Deleted receipt coming back.** If two phones imported the same receipt on their own before
  their first sync and one deletes it, the other's copy (different id) is not matched by the
  tombstone and returns. Fix: receipt tombstones also store the receipt's unique key
  (`tombstone.natural_key`), and the merge matches on it.
- **Clock skew.** Later `updated_at` wins, so a phone with a wrong clock wins edits it should
  not. Acceptable for now; a hybrid logical clock fixes it later if needed.
- **Tombstone cleanup.** Kept forever for now.
