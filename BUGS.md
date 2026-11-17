# Bugs

Bugs found while building and releasing 0.5.0 (multi-device send, share menu, text and links, live
camera, Switch phones, contact cards), how each was fixed, and what is still open.

## Open

| Issue | Platform | Notes |
| --- | --- | --- |
| Google Play may reject full photo/video access | Android | 0.5.0 asks for `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` for Switch phones and declares it in Play Console. If Google treats moving phones as "infrequent use", drop the permission: Switch phones would then send only photos picked by hand. |
| History lock not in Play build 7 | Android | Fixed in source (see below); ships with the next Android release. |
| Finished text and contact receives stay in the session list | All | Quiet sessions of kind `text` and `contact` are never pruned (camera and switch ones are). Small, grows by one entry per receive until the app restarts. |
| Share extension shows "iPhone" as the sender name | iOS | The extension can't read the name set in the app. |
| No "Send to" entry in the Microsoft Store build | Windows | The per-user shortcut is only added by the MSI/EXE installer. |
| A refused drive upload can look like a dropped connection | All (seen on macOS) | Writing to a read-only Wireless Drive gets a 403, but the server closes without reading the request body; macOS then resets the connection and the client may see the reset instead of the 403. Made `VaultDavTest` fail once on the Intel macOS CI for 0.5.0 (passed on rerun). Fix: drain the body (bounded) or close with a lingering shutdown. |
| Contact notes don't move from iPhone | iOS | Reading notes needs an entitlement Apple grants case by case. |
| "Already in your contacts" has no View button | Android, iOS | Only newly added contacts can be opened from the result card. |

### Not yet verified on real hardware

- iPhone camera; Switch phones photo export on iPhone (needs the "Allow Full Access" tap); the iOS
  share extension end to end, including Contacts' "Share Contact"; the contact picker in the Mac
  (Catalyst) app.
- Windows "Send to" and macOS "Open With" on an installed build.
- A multi-gigabyte phone move between real phones.

## Fixed in 0.5.0

### Found while releasing

- **Windows: history and settings kept only their first save.** Both files are written to a temp
  file and renamed over the old one, and `File.renameTo` refuses to replace an existing file on
  Windows. History never grew past one entry; a renamed device or a changed toggle came back after a
  restart. Present since Windows support was added. Now replaced with
  `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`, with a regression test that runs on the Windows CI.
- **Android: a history entry could be lost when two sends finished together.** Each session of a
  multi-device send ends on its own thread; two unsynchronised read-modify-write saves could drop one
  entry. `HistoryStore.add` is now synchronised.
- **Android: the update dropped 9 device models on Google Play.** The microphone permission (for
  video sound) made a microphone a required feature. It's now optional, and videos record without
  sound on devices that have none.
- **Desktop tests read history the instant a session ended.** History is written just after the
  terminal state is published; the tests now wait for it.

### Found in testing on the emulator and simulator

- **Android: crash when accepting a text offer** (`ForegroundServiceDidNotStartInTimeException`). The
  transfer service started observing before calling `startForeground`. Observation now starts after
  it, text receives don't start the service at all, and stopping is delayed briefly.
- **Switch phones: receiving failed with `ENOENT` on the `.part` file.** `FileDeltaStore` didn't create
  its folder (Kotlin and Swift). `targetFor` now creates it.
- **Switch phones: importing one batch deleted the next batch's files.** Batches shared one staging
  folder. Each batch now has its own folder.
- **Switch phones: photos with the same name in different batches overwrote each other.** Staged names
  now carry the item's index.
- **Android: moved photos had no capture date.** MediaStore ignores EXIF times without a time-zone
  offset on freshly inserted files. The importer now writes `DateTimeOriginal` plus
  `OffsetTimeOriginal` before inserting.
- **Desktop: live-camera photos rotated twice.** Skia already applies EXIF orientation; the extra
  rotation was removed before release and a test now checks it.

### Found by an independent code review

- **Android share target could be used as a confused deputy.** Another app could hand CrossDrop a
  content URI it couldn't read itself (including CrossDrop's own files or another user's, via `@`
  authorities) and have CrossDrop send it. The share screen now requires the sender's read grant and
  accepts only other apps' content.
- **iOS Switch phones counted photos it failed to export from iCloud as moved.** They're now left out of
  the ledger and reported ("N items couldn't be read"), so Resume tries them again.
- **Switch phones staging names could collide** after sanitising. Fixed with index prefixes.
- **A negative file time crashed the importer, and one bad item aborted a whole batch.** Times of zero or
  less are ignored; per-item errors are caught and counted as failed items.
- **A cancelled immediate send left its session behind**, keeping the "transferring" state (and the
  Android notification) up forever. Cleanup now runs on cancellation too.
- **Retry raced the previous attempt**, which could unregister the new one when it finally ended.
- **iOS Retry failed after the group's files were released.** Files stay readable until the group is
  dismissed.
- **A tap could answer the wrong offer** when a newer offer replaced the one on screen. Answers now go
  to the offer that was shown.
- **Desktop single-instance socket:** any local process could make CrossDrop send files, network (UNC)
  paths were accepted, and line reads were unbounded. It now needs a per-launch secret, refuses network
  paths and caps line length.
- **Switch phones resume ledger was keyed only by device name**, so a different phone with the same
  name could skip items. Entries now expire after 3 days.
- **vCard parsing was quadratic on folded lines**, so a card with a large photo took seconds. Now
  linear (a 3 MB photo parses in about 0.1 s).
