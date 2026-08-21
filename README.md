# tetron-mobile-sync

An Android app that backs up your phone's camera roll to a computer you own, automatically, over a private mesh network you control -- no cloud storage, no third-party server, no subscription. GPL-3.0 licensed.

Working name only. The app/product name is not decided yet; `tetron-mobile-sync` is the repo and crate name until then.

## What it does

Your phone and your home computer both join the same [tetron](https://github.com/ErikAllanKincaid/tetron) mesh network -- a private, encrypted network between your own devices, not a public service. Once they're both on it, this app watches your camera roll and copies new photos and videos to your home computer whenever it's safe and convenient to do so: normally only over Wi-Fi, only when connected directly rather than through a relay, and (optionally) only while charging or above a battery threshold you choose. You can also just tap a button to back up right now. The transfer itself uses `rsync`, the same well-established file-sync protocol used by countless backup tools, so an interrupted transfer picks up where it left off instead of starting over.

Everything runs one-way, phone to home: this app never deletes or modifies anything unless you explicitly turn on "delete after backup," and even then it only ever deletes files that a given backup run actually finished copying.

## Setting up

1. Install [tetron](https://github.com/ErikAllanKincaid/tetron) and its companion mobile app on your phone, and join your mesh network -- this app relies on that connection already existing, and has no networking of its own.
2. On the computer you want backups to land on, install [tetron-sync-receiver](https://github.com/ErikAllanKincaid/tetron-sync-receiver) and set up at least one shared folder and one allowed device (your phone). See that project's own README for exact steps -- it can be done entirely from a terminal, or by clicking through [tetron-webui](https://github.com/ErikAllanKincaid/tetron-webui)'s dashboard if you run that too.
3. Open this app. If it's the first time it has talked to the mesh, you'll see a banner asking you to grant it access -- tap through and allow it.
4. Grant photo/video access when prompted.
5. In Settings, pick your home computer from the device list, and enter the folder name you shared in step 2.
6. Adjust the Wi-Fi/battery/charging rules if the defaults don't suit you, or leave them as they are.
7. Tap "Back up now" once to confirm everything works, or just leave the app alone -- it checks in on its own afterward.

## Using the app

**Home screen.** Shows whether the mesh connection is up, which computer you're backing up to, and a "Back up now" button. Tapping it starts an immediate backup; the screen shows progress while one is running, and the result of the last run once it finishes (how many files were added, how many were skipped, and whether anything failed).

**Settings screen.** Everything you can configure:

- **Backup target** -- which computer to back up to (picked from your mesh network's device list) and the shared-folder name it's exposing.
- **Gates** -- Wi-Fi only, direct connection only, require charging, and a low-battery cutoff. All are meant to keep a background backup from costing you mobile data, a slow relayed connection, or your battery.
- **Delete after backup** -- off by default. When on, a file is only ever deleted once a backup run has actually finished copying it.
- **Schedule** -- how often the app checks in on its own in the background, separate from the manual button.

## How this relates to tetron-sync-receiver

This app is the phone side of a two-piece system. [tetron-sync-receiver](https://github.com/ErikAllanKincaid/tetron-sync-receiver) is the piece that runs on the receiving computer: it exposes shared folders, keeps a list of which devices are allowed to connect, and accepts the transfers this app sends. Neither piece works alone -- this app needs somewhere to send backups to, and tetron-sync-receiver needs something sending it files. They're separate projects (and separate licenses -- this app is GPL-3.0, tetron-sync-receiver is MPL-2.0) because a phone backup app and a headless receiver service are genuinely different pieces of software with different audiences, not because of any technical requirement to keep them apart.

## License

GPL-3.0. See `LICENSE`.
