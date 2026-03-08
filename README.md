# Unofficial DJI camera remote control Android app for RTMP livestreaming

Android app that can remote control DJI cameras like Action 4 via Bluetooth and allows to configure and start/stop RTMP livestream a lot faster and easier compared to official DJI Mimo app.

This functionality was originally developed in iOS app [Moblin](https://github.com/eerimoq/Moblin), which I've ported to Android.

RTMP stream parameters that can be configured:
- Wi-Fi network name and password
- RTMP URL to stream to
- Resolution
- Bitrate
  - Note: DJI Mimo's max bitrate is 6000 kbps. This can make camera send higher bitrates. I recommend to not go above 10,000 kbps or at least test first that your gear can handle longer streams reliably with higher bitrates.
- Stabilisation
- FPS for DJI Osmo Pocket 3

![dji-remote screenshot](docs/dji-remote-screenshot.png)

## Project status / roadmap

- Core functionality is stable for at least Action 4 camera. Needs more testing/time to see if something needs polishing.
- App name is not finalised.
- I probably won't be trying to publish it on Google Play.

## Feedback

Share ideas or report issues in Discord https://discord.gg/2UzEkU2AJW or create Git issues.

## What cameras are supported?

I only have Action 4 to test. I can confirm it works.

List of all cameras that can work in theory:

- ❓ DJI Osmo Action 2
- ❓ DJI Osmo Action 3
- ✅ DJI Osmo Action 4
- ✅ DJI Osmo Action 5 Pro
- ❓ DJI Osmo Action 6
- ❓ DJI Osmo 360
- ✅ DJI Osmo Pocket 3

## How to install

### GitHub releases

I plan to release .apk files using [GitHub releases](https://github.com/dimadesu/dji-remote/releases).

Open [GitHub releases page](https://github.com/dimadesu/dji-remote/releases) on your phone, download .apk file and install.

## Other projects of mine

- [LifeStreamer](https://github.com/dimadesu/LifeStreamer) - Android IRL live streaming app - use device cameras, RTMP, SRT, USB as sources, publish HEVC with adaptive bitrate over SRT.
- [Bond Bunny](https://github.com/dimadesu/bond-bunny) - Android SRTLA bonding app. Add SRTLA bonding to any SRT stream.
- [MediaSrvr](https://github.com/dimadesu/MediaSrvr) - run RTMP media server as an Android app.
- [ScreenStreamerGo](https://github.com/dimadesu/ScreenStreamerGo) - free app to stream Android device screen over SRT/RTMP (in early stages of development).

## Special thanks

Special thanks goes to [Spillmaker](https://github.com/Spillmaker) who has done the hard work of reverse-engineering the DJI Bluetooth commands.

He is continuing the work on the project [here](https://github.com/Spillmaker/DJILib.swift), building a Swift library for this.
