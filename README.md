# Offlines

Android app to download CoMaps map files and serve them over the local network — no internet connection needed once maps are downloaded.

## Build

```bash
cd android
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, open the `android/` folder in Android Studio and run.

## Usage

1. **Download** tab — Select a map series, pick the regions you want, tap download
2. **Serve** tab — Start the server, enter the shown URL (`http://<ip>:8080`) in CoMaps → Settings → Custom Map Server URL

## How it works

- Downloads `.mwm` map files from the CoMaps CDN
- Runs an embedded HTTP server with Range request support (required by CoMaps)
- Serves files over WiFi — works with any CoMaps app instance on the same network

Based on the [CoMaps Map Distributor](https://codeberg.org/gedankenstuecke/comaps-map-distributor) by Bastian Greshake.

## License

AGPL-3.0-or-later (same as comaps-map-distributor)
