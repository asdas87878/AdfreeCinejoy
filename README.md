# Ad-free Viewer

A minimal Android app that opens one website full-screen, using Firefox's engine (GeckoView) with **uBlock Origin** built in.

- No address bar, tabs or menus
- uBlock Origin blocks ads; its filter lists update themselves
- Pop-ups, redirects to other sites, and links that try to open other apps are blocked
- Full-screen video rotates to landscape and keeps the screen on

## Get the APK

Every push to `main` builds a new APK. Open **Releases → latest** and download:

- `AdFreeViewer-arm64.apk` for almost all phones from the last ~7 years
- `AdFreeViewer-arm32-older-phones.apk` only if the first won't install

Allow "Install unknown apps" for your browser when Android asks.

## Settings

Edit `config.properties`:

- `start_url`: the site to open. Leave empty and the app asks on first launch.
- `app_name`: name under the icon.

Inside the app, press **Back** on the first page for **Exit / Change site / Reload**.

## Notes

- The signing key in `keystore/` keeps updates installable over the old version. Keep this repo private.
- Android 8.0 or newer.
