# Changelog

What changed in each release of Eksoda. Earlier releases are described on the
[Releases](https://github.com/tsaklidis/MyEksoda/releases) page.

## 0.3.2 - One more receipt site, and synced receipts at a glance

A small update: receipts from one more e-receipt site now import, and the lists show which
receipts came from another phone of the household. Install it over 0.3.1 as usual: your data
stays, and household sync keeps working between 0.3.1 and 0.3.2.

### New

- **Receipts on `einvoice.s1ecos.gr`** now import, for example Sephora's. Scan the receipt's
  QR code as usual; the link printed on the receipt's page works too. Scan these receipts
  again: the earlier attempt was refused before anything was saved.

### Improved

- **Receipts from another phone are marked in the lists.** With household sync on, a receipt
  that another phone of the household scanned shows a small ⇄ mark next to the shop's name,
  in Receipts and in Home's recent receipts, so you can tell before opening it. Inside the
  receipt, the note about where it came from stays as it was.

## 0.3.1 - More receipts import

A small update for receipts that some shops' e-receipt pages kept from importing. Install it
over 0.3.0 as usual: your data stays, and household sync keeps working between 0.3.0 and
0.3.1.

### Fixed

- **Impact receipts that said "The receipt page has conflicting information"** now import.
  Some shops put an id of their own in the receipt link instead of the receipt's official one.
  The app now accepts such a receipt when its page names the scanned link as its own
  ([#6](https://github.com/tsaklidis/MyEksoda/issues/6)). Receipts that failed this way are
  tried again automatically after the update, so there is no need to scan them again.
- **Epsilon Digital receipts from smaller shops**, such as fuel stations (links on
  `epsilondigital-3rd.epsilonnet.gr`), are now supported. The shop's name is shown as the
  receipt prints it, which may be the station's label (for example "R203-ΚΟΖΑΝΗ"); you can
  rename the shop in the app. Scan these receipts again: the earlier attempt was refused before
  anything was saved.

### Improved

- **Payments on Impact receipts are checked more carefully.** The amount and the tip must
  match in both parts of the receipt page; a page whose payment details disagree is refused
  instead of being saved with the wrong amount. The receipt shows the payment method as
  printed (for example "EPAY A80").

## 0.3.0

### New: household sync

The phones of a household can now share one set of receipts, directly with each other over the
same Wi-Fi. There is still no server and no account, and nothing goes over the internet.

- **Off by default.** Turn it on in **Settings → Household sync**. Everything about sync is on
  that screen.
- **Pairing with a QR code.** On one phone tap **Add a phone**; on the other tap **Join a
  household** and scan the code. Pair each phone of the household once. The code works like a
  password for the household's data, so the app does not allow screenshots of it.
- **What is shared:** receipts and their lines, products, categories, shops, budgets and the
  household's settings. A receipt imported on two phones appears once, and so does a product
  both phones created. Edits and deletions reach the other phones; when the same thing was
  changed on two phones, the later change wins.
- **When it syncs:** automatically when two phones of the household have Eksoda open on the
  same Wi-Fi, or at once with **Sync now**.
- **History:** every sync is listed with its time, the other phone, how many items were added,
  changed or deleted, and the reason when it failed.
- **Receipt links stay on the phone that scanned them.** A receipt that came from another phone
  says so, and has no "Open original".
- **Leave household sync** at any time: the phone stops syncing and forgets the paired phones.
  Your data stays on the phone.
- **Private by design:** each sync is encrypted with the household's key, only phones on the
  local network are contacted, and phones without the key are refused.

Good to know:

- Both phones need this version. With an older version on one of them, the sync stops and
  asks you to update the app on both.
- Sync needs Wi-Fi (or Ethernet), with Eksoda open on both phones. Guest networks and some
  routers keep devices from seeing each other; the Sync screen then says that no phone was
  found.

### Other changes

- **Settings → Data and privacy** now mentions household sync among the app's connections.
- The database is upgraded automatically on the first start (schema 7). Your data is kept. As
  always, exporting a backup before updating is a good idea.

No new permissions. Joining a household uses the camera, which the receipt scanner already
uses.
