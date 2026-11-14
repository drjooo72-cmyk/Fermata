## !!!Be aware of scam!!! The sites like https :// fermata-auto . com  are 100% scam. This app is free and opensource!


## Youssef Auto (personal fork of Fermata)
This repository is a personal fork of [Fermata](https://github.com/AndreyPavlenko/Fermata),
the free and open source media player written by **Andrey Pavlenko**. All credit for the
application goes to the original author. The fork is distributed under the same
[GPL-3.0 license](LICENSE) and its source code is public here.

Changes made in this fork (November 2026):
* The Android Auto build is named "Youssef Auto" and uses the package suffix `.youssef`,
  so it can be installed next to the official Fermata Auto.
* Parked-only video: on Android Auto, video is paused when the car starts moving and is
  resumed when it is parked. Audio, screen mirroring and navigation are not affected.
  See `fermata/src/auto/java/me/aap/fermata/auto/DrivingMonitor.java`.
* The GitHub Actions workflow builds the arm64-v8a APK only.
* Car link (read-only): the owner's own computer can read the car data reported over
  Android Auto (battery, fuel, range, speed, gear, odometer) from the phone, over a private
  Tailscale network and with a secret token. It can not control anything in the car. Disabled
  unless the app is built with `-PCAR_TOKEN=...`. See `CarLinkServer.java` and `CarState.java`.

## Fermata Media Player
[![Download the latest APK](https://img.shields.io/github/v/release/AndreyPavlenko/Fermata?label=Download%20APK&logo=github&style=for-the-badge)](https://github.com/AndreyPavlenko/Fermata/releases/latest)

## About
Fermata Media Player is a free, open source audio, video and TV player with a simple and intuitive interface. It is focused on playing media files organized in folders and playlists.

Supported features:

* Play media files organized in folders
* IPTV addon with support for XMLTV EPG and Catchup
* Remembers the last played track and position for each folder
* Support for favorites and playlists
* Support for CUE and M3U playlists
* Support for bookmarks
* Audio effects: Equalizer, Bass/Volume Boost and Virtualizer
* Configure audio effects for individual tracks and folders
* Configure playback speed for individual tracks and folders
* Customizable titles and subtitles
* Support for Android Auto
* Support for Android TV
* Show favorites and playlists on Android TV home screen
* Pluggable media engines: MediaPlayer, ExoPlayer and VLC
* Video player with support for subtitles and audio streams (VLC Engine only)

## Building the project
* Download and install the latest Android SDK or Android Studio from https://developer.android.com/studio/
* Set the environment variable ANDROID_SDK_ROOT pointing to the SDK directory
```bash
export ANDROID_SDK_ROOT=<path to android SDK>
```

### Clone the repository
```bash
git clone --recurse-submodules https://github.com/AndreyPavlenko/Fermata.git
cd Fermata
```

### Build AAB
```bash
./gradlew bundleAutoRelease -PAPP_ID_SFX=.type.your.pkg.sfx.here
find $PWD -name *.aab
```

### Build APK
```bash
./gradlew bundleAutoRelease -PAPP_ID_SFX=.type.your.pkg.sfx.here
find $PWD -name *.apk
```

### Building in docker
```bash
docker run -ti --name Fermata andreypavlenko/fermata
```
Enter the requested key alias and password, when prompted.
Build the required package using the above commands.
To copy the built package to the host machine, open a new terminal and run:
```bash
docker cp Fermata:/home/mobiledevops/Fermata/fermata/build/outputs/bundle/autoRelease/ .
```


## Donation
If you like the application, please consider making a donation:

[PayPal](https://www.paypal.com/donate/?hosted_button_id=NP5Q3YDSCJ98N)

[CloudTips](https://pay.cloudtips.ru/p/a03a73da)

[Yandex Money](https://money.yandex.ru/to/410014661137336)
