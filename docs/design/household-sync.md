# Household sync

Phones of the same household (for example four family members) share one set of receipts,
products, categories, budgets and household settings by syncing directly with each other on
the same Wi-Fi. No server, no account, no internet traffic. For the overview in pictures, see
[How it works](../../README.md#-how-it-works) in the README.

Sync is off by default and runs only while the app is open: no foreground service, no
background work. Receipt URLs never leave the phone that scanned them.

## How it works

### Pairing (once per phone)

- The first phone to turn sync on creates a **household sync key** (`HouseholdKey.create`,
  32 random bytes), wrapped with an Android Keystore key under its own alias, in
  `noBackupFilesDir/sync.key` (core:data `sync/SyncDevice.kt`, `KeystoreSyncDevice`). Each
  install also gets a random **device id** (`sync.device`); its name is the device model.
- On the Sync screen, "Add a phone" shows a QR code (`PairingCode`:
  `eksoda-pair:1?k=<key>&d=<device id>&n=<name>&a=<host:port>`; the address while the
  phone listens). The dialog's window is secure (`SecureFlagPolicy.SecureOn`: no screenshots, hidden
  in recent apps) and the code is held only while it is open. Drawn with zxing core (pure
  Java, offline), since zxing-cpp's Android library only reads.
- The new phone scans it ("Join a household": its own camera scanner in feature:household,
  zxing-cpp like feature:scan; features do not depend on each other). It stores the key, pairs
  the phone that showed the code, and turns sync on. A different household's key replaces its
  own and forgets the phones paired before (asked first when there are any). Its existing
  data is merged in on the first sync, not replaced: the first sync is an ordinary sync.
- Anyone holding the key is a member. Every paired phone can add further phones.
- "Leave household sync" (on the screen, confirmed) deletes the key and the paired phone list
  on this phone and turns sync off. The data and the history stay. Turning the switch off does
  **not** forget the pairing: turning it on again resumes.

### Discovery and triggers (core:data `sync/SyncRuntime`, `SyncDiscovery`)

- While sync is off the app opens no socket and announces nothing on the network.
- While the app is in the foreground, sync is on, the phone holds a key and it is on Wi-Fi or
  Ethernet: listen on a TCP port (any free one) bound to that network's private IPv4 address
  only (never mobile data, whose carrier addresses can look private too) and announce `_eksoda._tcp` with `NsdManager` (platform, no Google Play
  services), with the device id and the household tag (`HouseholdKey.tag`, an HMAC that says
  nothing of the key) as attributes, so other households' phones are skipped without
  connecting. Discover the others. Stop all of it when the app goes to the background
  (`ProcessLifecycleOwner`), the switch goes off, the network changes or the key goes, and
  start over when the key changes (joining another household). Started from
  `EksodaApplication` (off the main thread); entries a killed app left running end as
  `interrupted` at the next start. Nothing a session throws stops the listener or the app.
- **Automatic:** when a household phone appears, the phone with the smaller device id starts
  a sync. Each phone is tried at most every 15 minutes, whatever the outcome, and not at all
  when the two synced in the last 15 minutes.
- **Manual:** "Sync now" on the screen syncs with every household phone found by NSD and
  with every other paired phone at its last known address; with none, the history says no
  phone was found.
- **After joining:** the first sync starts at once, through the address in the code if the
  phone is not found by NSD. The "Add a phone" code carries this phone's local IPv4 address
  and port while it listens.
- One session at a time (`SyncRunner`). A household phone that connects while another sync
  runs is told `busy` at once (no waiting, so two phones syncing each other never wait on
  each other). 5 s to connect; until the other phone proved it holds the key, 10 s of
  silence or 15 s in all end the connection, and it holds no lock; after that, 120 s of
  silence (the other phone may be merging a large household).
- Only private addresses are used, outgoing and incoming: 10/8, 172.16/12, 192.168/16,
  169.254/16, fc00::/7, fe80::/10 (core:engine `LocalNetwork`). Anything else is refused
  before connecting and on accept.

### Protocol (one TCP connection, core:engine `SyncSession`)

Frames are `length (uint32 BE) | bytes`. The phone that connects is the client.

1. Each side sends a hello `{protocol, device_id, device_name, schema, nonce (32 bytes)}`,
   sealed with AES-256-GCM under the household key (random 12-byte nonce, the role as
   additional data, so a hello cannot be sent back as the other's). A hello is at most 4 KB,
   refused before anything is allocated. A phone that cannot open the client's hello answers
   with an empty frame: both stop with `not_paired`. A server syncing with another phone
   answers a household hello with a one-byte frame: both stop with `busy`. A different
   protocol or schema stops both with `schema_mismatch` (update the app on both phones);
   the server records it too.
2. The session key is HKDF-SHA256 of the household key with both nonces. Every later frame is
   sealed with it; each direction numbers its frames from 0 and the number is the GCM nonce,
   so a frame repeated, dropped or reordered does not open (`damaged`).
3. The client sends its **snapshot** (core:engine `SnapshotCodec`: gzip of JSON lines, values
   as stored, without `receipt.source_url` and `import_job`: `Snapshot.forPeer`). The server
   merges it in one transaction, then sends its own snapshot, which now holds the client's
   data too (or an error frame with its reason, e.g. `merge_failed`). The client merges that.
   Both phones end with the same data, and each writes an entry in its history (the server
   only once the client proved it holds the key: strangers leave no trace; its entry says the
   other phone started it).

The whole snapshot is sent every time. For a family this should be a few MB compressed
(not measured yet: needs a real household's data on two phones). Sending only rows changed since
the last sync with that phone is an optimization for later; the merge does not change.

### What syncs

Everything the household shares: its name, annual income, budgets and e-payment target,
categories, merchant names and default categories, products and mappings, receipts and lines.

| Table | Syncs | Note |
|---|---|---|
| household, category, budget, merchant, store, product, merchant_product_mapping, receipt, receipt_item, tombstone | yes | `receipt.source_url` is left out |
| import_job | no | local queue, holds the URL |
| sync_peer, sync_event | no | local; also left out of the backup file (`BackupArchive.LOCAL_TABLES`) |

### Merge rules (core:engine `sync/`)

Merging depends only on the two row sets, never on the order of syncs, and running it twice
changes nothing. So any phone can sync with any other, in any order, and all phones converge.
The table rules are `SyncSchema.TABLES`; the merge is `SyncMerge.merge(local, remote)`;
core:data `SnapshotStore` reads the snapshot and applies the result, and `SyncMerger` runs
both under the write lock.

- **Same id:** the row with the later `updated_at` wins; on a tie, the larger row content
  (local columns left out), so both phones pick the same copy. Rows are whole: a concurrent
  edit of two different fields of the same row keeps only one (acceptable for a household).
- **Deletes:** a tombstone wins over a row when `deleted_at >= updated_at`. Tombstones are
  merged by union (latest `deleted_at`). A receipt's tombstone also holds its unique key
  (`natural_key`), so it deletes the copy another phone imported under its own id. A deleted
  parent takes its `CASCADE` children along and is cleared from `SET NULL` pointers; a
  deleted row that a surviving row points at through `RESTRICT` (a product a new line was
  matched to on the other phone) is kept.
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

- **Products** have no unique key. When two different mappings with the same key point to
  different products (two phones each created one), the products are merged: the smaller id
  is kept, receipt items and mappings are repointed, and the later `updated_at` decides name,
  brand, category and package. One mapping that a phone pointed at another product merges
  nothing (its later copy decides). Products merged on a phone ("merge products") leave
  tombstones with `replaced_by`: on the other phones their lines and mappings move to the
  product kept, instead of the merged product coming back.
- **Receipt URL:** when a receipt row from another phone wins, the local `source_url` (if
  this phone has one) is kept.
- Import jobs are repointed at the receipt kept, or marked `receipt_deleted`.
- Analytics, budgets and Home simply see the merged rows; nothing else changes.

### The Sync screen (feature:household)

A full screen with its own destination (`SyncDestination` in `app/ui/Destinations.kt`,
wired in `MainNavHost`), opened from a "Household sync" row in Settings whose subtitle says
"Off" or "On · last synced <time>". Back returns to Settings. Follows the UI pattern in
OVERVIEW.md and `docs/design/ui-redesign.md`. Nothing about sync appears anywhere else, except
that Settings row and the note on synced receipts; it is not a tab, and the bottom bar does
not change.

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

A receipt with no `source_url` came from another phone. Its detail says it was synced from
another phone (`ReceiptDetail.hasLink`, the link itself never in the model) and has no "Open
original". It says only that, not who added it: no member names or attribution are stored.
In the receipt list and in Home's recent receipts, such a receipt carries a small ⇄ mark next
to the shop's name (`core:ui` `FromOtherPhoneMark`, from the list row's `from_other_phone`).

## Platform notes

- With `targetSdk` 36 (today), local network access comes with `INTERNET`. When the app
  targets 37, all local network traffic needs the runtime permission `ACCESS_LOCAL_NETWORK`
  (Nearby devices group). It is then requested when the user turns sync on; when denied, the
  screen says so and offers to ask again. Don't declare it before targeting 37.
- Guest networks and some routers block traffic between devices, and some block mDNS. The
  screen must make "not found" clear instead of failing silently.

## Not built yet

- **Synced receipts:** storing the URL when this phone later scans a receipt it already has
  from sync (the import finds it as a duplicate and today keeps no URL).
- **The local network permission,** when moving to `targetSdk` 37 (Platform notes).
- **A test on two real phones** with a real household's data (also to measure the snapshot
  size).

## Open points

- **Unique keys that change.** The merge writes changed rows one by one, so two rows that
  swap a unique value in the same sync (two user categories renamed into each other's
  names) hit the unique index: the merge fails as `merge_failed` and nothing changes. Rare;
  if it happens in practice, write such rows in two steps.
- **A missing `RESTRICT` parent** (a row pointing at a merchant or product that neither phone
  has) fails the final foreign key check: `merge_failed`, nothing changes. It should not
  happen; it is not silently dropped, so no line is lost.
- **Clock skew.** Later `updated_at` wins, so a phone with a wrong clock wins edits it should
  not. Acceptable for now; a hybrid logical clock fixes it later if needed.
- **Tombstone cleanup.** Kept forever for now.
