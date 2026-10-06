# Ad-free Viewer

A minimal Android app that opens one website full-screen, using Firefox's engine (GeckoView) with **uBlock Origin** built in.

- No address bar, tabs or menus
- uBlock Origin blocks ads; its filter lists update themselves
- Pop-ups, redirects to other sites, and links that try to open other apps are blocked
- Full-screen video rotates to landscape and keeps the screen on
- **Picture-in-picture**: leave the app while a video plays and it keeps going in a floating window
- **Reopens the last page** you were on
- **Full-screen gestures**: swipe up/down on the left for brightness, right for volume; double-tap the left/right third to skip 10 s
- **Playback speed** 0.5×–2×
- **Screen lock** while watching (lock button, top-left in full-screen)
- **Pull down to refresh** at the top of a page
- **Colours & themes**: swap dark/light, colour shift, brightness, warmth, presets, and custom CSS

## Get the APK

Every push to `main` builds a new APK. Open **Releases → latest** and download:

- `AdFreeViewer-arm64.apk` for almost all phones from the last ~7 years
- `AdFreeViewer-arm32-older-phones.apk` only if the first won't install

Allow "Install unknown apps" for your browser when Android asks.

## Settings

Edit `config.properties`:

- `start_url`: the site to open. Leave empty and the app asks on first launch.
- `app_name`: name under the icon.

Tap the **⋮** button (bottom-right), or press **Back** on the first page, for the menu: Home, Reload, Playback speed, Colours & themes, Picture-in-picture, Settings (turn each feature on/off), Change site, Exit.

In full-screen, tap once to show the lock, speed and picture-in-picture buttons.

## Notes

- The signing key in `keystore/` keeps updates installable over the old version. Keep this repo private.
- Android 8.0 or newer.
