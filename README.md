# Momir Android

Android app for the Momir printer flow.

## Origin

This project is an Android-native adaptation of the original Momir project:
https://github.com/Devin-Cooper/momir-printer

The primary focus for this Android version is the **Phomemo M02S** workflow.

## Features

- First launch bootstrap:
  - Downloads AtomicCards from https://mtgjson.com/api/v5/AtomicCards.json
  - Processes it into a compact local creature index
- UI flow:
  - Pick mana value (0-16)
  - Roll a random creature
  - Show card image from Scryfall
- BLE printing:
  - Scan/connect to compatible Phomemo devices by name prefix
  - Build ESC/POS raster commands
  - Send data with model-specific chunking behavior

## Requirements

- Android Studio (latest stable)
- Android SDK 36
- JDK 21
- Android device with BLE support (recommended for printer testing)

## Getting Started

1. Clone the repository.
2. Open this folder in Android Studio.
3. Let Gradle sync complete.
4. Run the app on a device or emulator.

## Build From Command Line

Use Windows PowerShell from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

Release build:

```powershell
.\gradlew.bat :app:assembleRelease
```

## Permissions

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- Android 11 and below: `ACCESS_FINE_LOCATION`

## Printer Support

Current BLE printer matching and profile selection are implemented in `BlePrinterManager`.

| Printer model / name pattern | Status | Notes |
| --- | --- | --- |
| M02S | Supported (primary target) | Default profile in app; tuned chunking for stability. |
| M02 PRO | Supported | Mapped to M02S profile settings. |
| M02 | Supported | Uses M02 profile (384px width, conservative transfer mode). |
| T02 | Supported | Mapped to M02 profile. |
| M04 / M04S / M04AS | Supported | Matched by `M04*` naming and mapped to M04S-style profile. |
| Mr.in_ naming | Detected (fallback profile) | Name is matched during scan; currently falls back to M02S profile. |

If your printer advertises a different Bluetooth name, it may not be auto-detected until the name matcher is extended.

## Project Structure

- `app/`: Android app module
- `gradle/`: Gradle wrapper configuration
- `build.gradle.kts`, `settings.gradle.kts`: project build setup

## Notes

- First bootstrap can take time because the app downloads and processes a large JSON file.
- BLE write pacing may need slight tuning on some devices/printers.
