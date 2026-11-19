# Dreame Vacuum for Hubitat Elevation

A Hubitat Elevation Groovy integration for **Dreame**, **MOVA**, and **Trouver** robot vacuums using the **Dreamehome Cloud API (HTTPS + MQTT)**.

Ported from the Python Home Assistant integration [`Tasshack/dreame-vacuum`](https://github.com/Tasshack/dreame-vacuum).

## Features

- **Cloud Authentication & Auto-Refresh:** Supports Dreamehome, MovaHome, and Trouver accounts across `us`, `eu`, `cn`, `sg`, `ru`, `kr`, `in`, and `de` regions with automatic OAuth token refresh and request signing.
- **Automatic Device Discovery:** Discovers all vacuums bound to your account and creates managed child devices.
- **Real-Time MQTT Push + Scheduled HTTP Polling:** Uses Hubitat's `interfaces.mqtt` for instant property updates, with automatic fallback to scheduled HTTP polling.
- **Core Controls:**
  - `start` / `on`, `pause`, `stop`, `returnToBase` / `off`, `locate`
  - `cleanRooms(roomIds, repeats, suctionLevel, waterVolume)` — clean one or more rooms by segment ID
  - `cleanZone(coordinates, repeats)` — zone cleaning (`x1,y1,x2,y2`)
  - `setSuctionLevel` (`Quiet`, `Standard`, `Strong`, `Turbo`)
  - `setWaterVolume` (`Low`, `Medium`, `High`)
  - `setCleaningMode` (`Sweeping`, `Mopping`, `Sweeping and Mopping`, `Mopping after Sweeping`)
  - `setWetnessLevel` (1–32 for supported models)
  - Base station controls: `startAutoEmpty`, `startWashing`, `pauseWashing`, `startDrying`, `stopDrying`
  - Settings & maintenance: `setDnd`, `setChildLock`, `setVolume`, `resetConsumable`
- **Telemetry & Consumables:**
  - Battery, charging status, cleaning state/status/task status, error code & description
  - Cleaning time, cleaned area, progress %, lifetime totals
  - Consumable life percentages & hours (main brush, side brush, filter, sensor, mop pad, silver ion, detergent) plus Hubitat `Consumable` capability summary
  - Base station tank/dust bag status attributes

> **Note on Hubitat Platform Scope:** Live map rendering (`Pillow`/`numpy` camera entities) and local UDP MiIO encryption from the Home Assistant integration are omitted due to Hubitat's Groovy sandbox constraints.

## Installation

### Option 1: Manual Install

1. **Install the Driver:**
   - In Hubitat, go to **Drivers Code** → **+ New Driver**.
   - Paste or import [`drivers/dreame-vacuum-driver.groovy`](drivers/dreame-vacuum-driver.groovy) and click **Save**.
2. **Install the App:**
   - Go to **Apps Code** → **+ New App**.
   - Paste or import [`apps/dreame-vacuum-manager.groovy`](apps/dreame-vacuum-manager.groovy) and click **Save**.
3. **Add & Configure the App:**
   - Go to **Apps** → **+ Add User App** → **Dreamehome Vacuum Manager**.
   - Select your **App Ecosystem** (Dreamehome / MovaHome / Trouver) and **Server Region**, and enter your account email/phone and password.
   - Click **Authenticate & Discover Vacuums**.
   - Select your vacuum(s), choose your preferred polling interval, and click **Done**.

### Option 2: Hubitat Package Manager (HPM)

In HPM, choose **Install** → **From a URL** and enter:
```
https://raw.githubusercontent.com/DPBurek/dreame-vacuum/master/packageManifest.json
```

## Using `cleanRooms`

Pass comma-separated integer room/segment IDs to `cleanRooms` (e.g., `1,3`):
- In **Rule Machine**, use **Run Custom Action** → **Actuator** → select your vacuum → `cleanRooms` with string parameter `"1,3"` (and optional numeric `repeats` and enum `suctionLevel` / `waterVolume`).
- Room IDs are integer segment IDs assigned by your vacuum's saved map (typically `1`, `2`, `3`, ...).

## Credits

Protocol reverse-engineering, MIoT property/action mappings, and state enums are ported from [`Tasshack/dreame-vacuum`](https://github.com/Tasshack/dreame-vacuum).
