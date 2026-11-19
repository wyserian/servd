# servd

[![CI](https://github.com/wyserian/servd/actions/workflows/ci.yml/badge.svg)](https://github.com/wyserian/servd/actions/workflows/ci.yml)
[![Release](https://github.com/wyserian/servd/actions/workflows/release.yml/badge.svg)](https://github.com/wyserian/servd/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/wyserian/servd?sort=semver&label=release)](https://github.com/wyserian/servd/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
![Platforms](https://img.shields.io/badge/platforms-Windows%20%7C%20Linux%20%7C%20macOS%20%7C%20Android-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)

## About

**servd is a portable local-network hub.** Run it on a computer or an Android phone, and every
device on the same Wi-Fi or hotspot can use it from a browser. Nothing needs installing on those
devices. A phone is a full hub, running the same stack as desktop (Windows, Linux, macOS). The served
dashboard is the UI everywhere; Android adds only a thin native control screen.

The host serves one HTTPS dashboard. It offers chat, file sharing, a shared host folder, and a
reverse proxy for a running web app, plus optional SSH/SFTP and FTPS servers. Controls that
configure the host appear only on the host itself.

- **Trusted-LAN tool:** there are no accounts, and admin controls work only on the host device.
  On shared Wi-Fi the host can turn on an optional join PIN. SSH/FTP use real credentials. See
  [Security](#security).
- **Fully offline:** servd talks only to devices on your network. The dashboard is
  self-contained, with no CDN, webfonts, analytics or telemetry, and it works on a hotspot with no
  uplink. Internet is needed only for the first source build, to download dependencies.
- **Encrypted:** all traffic is encrypted (HTTPS/WSS, SSH, FTPS). A LAN has no certificate
  authority, so the self-signed certificate is checked by its **SHA-256 fingerprint** instead.

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
- [Security](#security)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Development](#development)
- [License](#license)

## Features

- **Drop files:** share files or whole folders with every device. Folders keep their structure, and
  a folder or everything shared downloads as one zip. Uploads show progress, speed and time left,
  and can be cancelled.
- **Storage limits:** the hub never fills its own disk. The host can add a quota and auto-clear
  shared files after 1 hour, 24 hours or 7 days.
- **Chat:** real-time chat over WSS, with a live list of who's connected.
- **Live streams:** the host goes live with their camera, microphone or (in desktop browsers) screen,
  and up to 10 devices watch. Media goes peer to peer over the LAN with WebRTC, so the hub isn't
  loaded.
- **Watch together:** the host plays a shared video or audio file, a YouTube video or an https
  media link for everyone. With sync on, the host's play, pause and seek apply on every device.
- **Previews:** shared files preview inline in chat: images (with a full-size viewer), video and
  audio players, and the first lines of text and code files. Image files also get thumbnails in the
  file list.
- **Activity alerts:** messages and files that arrive while you're away show as a count in the tab
  title and the chat tab. Browser notifications are optional, but some browsers don't allow them on
  a self-signed page.
- **Browse files** (optional): the host shares a real folder, read-only or with uploads. Clients
  can open subfolders and download single files or whole folders as a zip. The share is locked to
  the chosen folder.
- **Serve site** (optional): a reverse proxy that re-serves a running web app (Node/Vite dev
  server, static host, any HTTP/HTTPS service) to the LAN over servd's HTTPS, on its own port.
  WebSockets and HMR/live reload work.
- **SSH/SFTP and FTPS** (optional): FTPS uses explicit TLS. Both use a generated password and are
  limited to the shared files, with no OS shell.
- **Easy joining:** a scan-to-join QR code, a friendly **`https://servd.local:8443`** address over
  mDNS, and mDNS discovery (`_servd._tcp`, or run `servd discover`).
- **Join PIN** (optional): other devices must enter a 4-8 digit PIN before they can use the hub,
  including the serve-site port.

## Getting Started

### Installation

Get a build from the [**latest release**](https://github.com/wyserian/servd/releases/latest).
Desktop builds bundle their own Java runtime, so nothing else is needed. To build it yourself, see
[Development](#development).

| Platform | Asset | Run |
|---|---|---|
| Windows | `servd-<version>-windows.zip` | unzip, run `servd\servd.exe` |
| Linux | `servd-<version>-x86_64.AppImage` | `chmod +x`, run it |
| Android 8.0+ | `servd-<version>-release.apk` | install it (allow unknown sources) |

### Connecting Devices

On start, servd prints:

```
servd v1.8.0 - your device, as a local-network hub

admin   : https://127.0.0.1:8443/?admin=…   (this machine only - keep the link private)
serving : https://10.205.210.12:8443   (share with other devices)
by name : https://servd.local:8443   (iPhone, Mac, Windows, Linux - Android may need the IP)
cert    : self-signed, SHA-256 fingerprint:
          12:3A:07:38:...:F5:CF
keystore: C:\Users\you\.servd\keystore.jks
```

The **admin** URL is the host's own view, where the host-only tabs and settings appear. servd opens
it for you; the browser keeps the host role after that, so plain `https://127.0.0.1:8443` works too.
The **serving** URL is the one you share.

1. Put the other devices on the **same Wi-Fi**, or on the host's **hotspot**.
2. On each device, open **`https://<serving-address>:8443`**, or scan the QR code in the dashboard.
   iPhone/iPad, Mac, Windows 10+ and most Linux can also use **`https://servd.local:8443`**. Many
   Android browsers can't resolve `.local` names, so use the IP or QR there.
   - If another device on the network already uses `servd.local`, this hub takes the next free name
     (e.g. `servd-1.local`). The banner, the **settings** drawer and the Android control screen show
     the name in use.
   - An existing certificate is kept, so its fingerprint doesn't change; only newly created
     certificates list the name.
3. Accept the certificate warning once the fingerprint matches, then pick a display name. Each
   device then appears in the **Devices** roster.

### Android Host

1. Put the phone on the same Wi-Fi as the other devices, or turn on its hotspot.
2. Open **servd** and tap **Start hub**. The screen shows the share URL, the QR code, the
   fingerprint and the SSH/FTP credentials.
3. Other devices open the URL and get the same dashboard as on desktop.

SSH/FTP start off. To enable them, open the **ssh** / **ftp** tabs from the dashboard on the
phone's own `127.0.0.1`. The hub keeps running with a persistent notification; tap **Stop** there or
in the app. To share phone storage in browse files, tap **Allow file browsing (storage access)** and
grant all-files access. The folder picker then starts at Internal storage.

### Dashboard

| Tab | Who sees it | What it does |
|---|---|---|
| **drop files** | everyone | shared files and folders pushed to every device |
| **browse files** | host; clients once a folder is shared | left: browse and download the shared folder; right (host only): folder picker |
| **chat** | everyone | real-time chat |
| **stream** | everyone | watch the host's live stream or the file it's playing for everyone; the host can go live (camera / microphone / screen) or play a file or link |
| **serve site** | host only | re-serve a running web app to the LAN |
| **ssh** / **ftp** | host only | turn the server on or off; shows the port, password and connect command |

The ⚙ **settings** button at the right of the top bar opens a side drawer:

- **Every device** gets its own preferences: theme (system / light / dark) and browser
  notifications.
- **The host** also gets the join PIN, storage limits, and *about this host*: name, interface,
  `.local` name and fingerprint.

**Browse files:** on the host, pick a folder in the picker on the right; a whole drive works too.
Tick **allow uploads** for read + write, then click **share this folder**. Use **stop sharing** to
turn it off; you can switch folders anytime. Paths that try to leave the folder are refused.

**Serve site:**
1. Start your app as usual (e.g. `npm run dev` on `http://localhost:3000`).
2. In the **serve site** tab, enter that target and a port (default `8080`), then click
   **serve this site**.
3. Everyone opens `https://<serving-address>:8080/`, or scans the QR code shown in the tab.

The app is served at the root, so SPA routes and absolute asset paths keep working. servd adds TLS,
so even an HTTP-only or localhost-only app is reachable and encrypted across the LAN.

**Live streams:**
1. On the host, open the **stream** tab, pick **camera**, **microphone** or **screen**, then click
   **go live**. The browser asks for permission first. Only the host can go live; the hub refuses it
   from other devices.
2. Everyone else sees the stream in the tab and in chat, and clicks **watch**. On large screens,
   the list of what's live sits on the right.

Things to know:
- **Media path:** it flows directly between the two devices (WebRTC, about 0.5 s delay). The hub
  only relays the connection setup, so a phone hub isn't loaded.
- **Viewers:** up to 10 per stream. The streamer uploads one copy per viewer, with video capped at
  2.5 Mbps.
- **Screen sharing:** needs a desktop browser. Phones can stream their camera and microphone.
- **Networks:** Wi-Fi with *client isolation* (some hotel or office networks) blocks
  device-to-device traffic, so streams won't connect there. Your own hotspot or home Wi-Fi is fine.

**Watch together (play a file for everyone):**
1. **Start (host):** in the **stream** tab, pick a shared video or audio file and click **play**.
   Alternatively, choose a file from your device, which also shares it, or press **▶** on a
   video/audio row in drop files. Or paste a link and click **play link**: a YouTube video, or an
   `https://` link straight to a video or audio file.
2. **Join (everyone else):** the player appears with a chat note. With sync on, they tap
   **join playback** once, because browsers need a tap before playing sound.

How sync works:
- **On (default):** the host's play, pause and seek apply on every device. Each device allows for
  its own network delay and closes small gaps by playing up to 5% faster or slower, so devices stay
  within a few hundredths of a second of each other (YouTube links: about 0.3 s, since YouTube's
  player can only jump). Bluetooth speakers and headphones add their own delay, which no browser
  can see. A device that joins midway lands at the current spot.
- **Going your own way:** a viewer who pauses or seeks on their own stops following until they
  press **resync**.
- **Off:** everyone plays at their own pace.

Limits:
- **Viewers:** each device streams the file from the hub, so there's no 10-viewer cap.
- **Formats:** whatever the browser plays (MP4/H.264, WebM, MP3, M4A, OGG, WAV, FLAC). MKV and AVI
  often don't play.
- **Links:** each device streams the link from the internet, so every device needs internet
  access. Some YouTube videos can't be played this way because their owner turned off embedding.
- **Ending:** playback ends when the host stops it, leaves, or the file is deleted.

### Join PIN & Storage Limits

Both are set in the host's ⚙ **settings**, and on desktop also with the `--pin`, `--max-storage` and
`--expire` flags. Both are saved until changed.

**Join PIN:**
- **Who needs it:** other devices see a PIN screen first. The host itself never needs it.
- **Sessions:** after a correct PIN a device stays signed in for 30 days, until the PIN changes or
  the hub restarts.
- **Wrong guesses:** after 5 wrong tries a device is locked out for 30 s, and the wait doubles with
  each further miss.

**Storage limits:**
- **Always on:** uploads that would leave the host's disk with less than 1 GB or 5% free
  (whichever is larger) are refused.
- **Optional:** a quota for shared files, and auto-clear after 1 hour, 24 hours or 7 days. Both are
  off by default.
- **How it shows:** everyone sees the space used and free under the drop zone. A file that doesn't
  fit is refused before it uploads, or cut off mid-way with nothing left behind.
- **Not counted:** SFTP/FTP uploads don't count toward the quota.

### Ports & Data Directory

| Port | Service |
|---|---|
| `8443` | dashboard, chat, files (always on) |
| `2222` | SSH/SFTP (off until enabled) |
| `2121` | FTPS (off until enabled) |
| `8080` | serve site (only while serving; configurable) |

All ports are non-privileged. SSH and FTP share one generated credential, username `servd`. Data
(keystore, SSH host key, credential, shared files, join PIN, storage limits) lives in `~/.servd` on desktop, and
in the app's private storage on Android. Shared files last until cleared: they don't survive a
restart, and the previous run's files are deleted on start.

### Command-Line Options

```bash
servd [start] [flags]     # start the hub
servd discover            # find hubs on the network without typing an IP
```

| Flag | Meaning | Default |
|---|---|---|
| `--port N` | HTTPS port | `8443` |
| `--host IP` | listen on this address only (and advertise it) | all interfaces, advertising the detected LAN IP |
| `--dir PATH` | data dir | `~/.servd` |
| `--no-open` | don't open the browser | opens |
| `--browse-dir PATH` | share a folder on start | off |
| `--browse-write` | allow uploads to that folder | read-only |
| `--proxy-target URL` | serve a running site on start | off |
| `--proxy-port N` | serve-site port | `8080` |
| `--pin DIGITS` / `--pin off` | set (and save) or clear the join PIN | saved PIN, else off |
| `--max-storage 10G` / `off` | drop-files quota (saved; `K`/`M`/`G` sizes) | saved, else none |
| `--expire 1h\|24h\|7d` / `off` | auto-clear drop files after that age (saved) | saved, else off |
| `--name NAME` / `off` | the `.local` name answered over mDNS (`--name myhub` → `myhub.local`) | `servd` |

## Security

servd is built for a **trusted LAN**, and these limits are deliberate:

- **Client routes** (dashboard, chat, files, roster) are open to the network by default, with no
  account. Anyone who can reach the hub and accepts the certificate can use them.
- **Join PIN** (optional) gates every client route except the page itself and the PIN check, and
  the serve-site port too. A correct PIN gives a random session cookie (`Secure`, `HttpOnly`,
  `SameSite=Lax`), kept in memory, so a restart or a PIN change signs everyone out. Wrong guesses
  are rate-limited per device. It's a 4-8 digit PIN against casual access on shared networks, not a
  strong password.
- **Admin routes** (`/admin/*`: service toggles, join PIN, storage limits, folder and proxy setup)
  and the host-only stream actions need **loopback plus an admin token**. The app and CLI open the
  dashboard with the token (`/?admin=…`), and the hub swaps it for a cookie (`Secure`, `HttpOnly`,
  `SameSite=Strict`). Loopback alone isn't enough: websites open in the host's browser and other
  apps on a phone also connect from 127.0.0.1. The token lives in the data folder (`admin.token`);
  delete it to sign every host browser out.
- **Drop files** can't fill the host's disk: uploads stop at the free-space floor (1 GB or 5%),
  plus the host's optional quota. Limits are counted as bytes arrive, not from the size a client
  claims.
- **Previews** never run uploaded content. Files are served as attachments with `nosniff`. Only
  raster images, video, audio and plain text get a real media type, decided by the hub from the
  file name, not from what the uploader claims. SVG, HTML and everything else are served as plain
  bytes or plain text.
- **SSH/FTP** always require the password; there's no anonymous login. FTP uses explicit TLS
  (FTPS), and SFTP is encrypted by design. Both are limited to the shared files, with no OS shell.
- **Browse files** is off by default and set up only from the host. Clients can't go above the
  chosen folder, and uploads are refused unless the host allows them. An upload never replaces a
  host file (a taken name gets " (2)"), and a cut-off upload leaves nothing behind.
- **Live streams** use the browser's WebRTC encryption (DTLS-SRTP) between the two devices. The hub
  only relays setup messages, and only between a stream's owner and its current viewers, so the
  join PIN covers who can watch.
- **Serve site** is off by default and set up only from the host. While it's on, the target app is
  open to the LAN on its own port, behind the join PIN if one is set. Stopping it closes that port.
- **Encryption** covers the network hop, checked by fingerprint. It is **not end to end**: the
  host can see the traffic, as expected for a hub.

## Architecture

```
servd/
├─ core/            Kotlin Multiplatform engine shared by desktop + Android
│   ├─ commonMain     protocol models, PeerRegistry, LAN-address selection, Service model
│   ├─ jvmSharedMain  Ktor server, TLS, chat hub, file store, folder browser, proxy, QR, SSH, FTP,
│   │                 join PIN, .local name
│   ├─ desktopMain    mDNS service advert + `servd discover` (JmDNS)
│   ├─ androidMain    Android glue
│   └─ resources/webui  the dashboard (one HTML/CSS/JS file)
├─ desktopHost/     the CLI that starts the hub (Netty engine)
├─ androidApp/      foreground service + native control screen
├─ run.ps1 / run.bat / run.sh   run from source (Windows / Windows / Linux-macOS)
├─ package.ps1 / package.sh     build distributables into dist/
└─ gradlew*         Gradle wrapper
```

- **`jvmSharedMain`**: Android and desktop both run on the JVM, but Kotlin Multiplatform has no
  shared "android + jvm" source set. Both `androidMain` and `desktopMain` depend on this
  intermediate source set, so the server code lives in one place.
- **One HTTP engine**: `ServdServer` holds all routing and TLS setup and takes the engine as a
  parameter. Both platforms pass **Netty**, because Ktor's CIO engine can't serve HTTPS. The
  keystore is **PKCS12**, because Android has no JKS provider.
- **Key abstractions:**
  - `Service` (`commonMain`) is an optional server (SSH/SFTP, FTPS) with an id, port, state and
    password. `ServiceManager` starts and stops them, one call at a time.
  - Implementations (`jvmSharedMain`): `SshService` and `FtpService`.
  - `LiveRegistry` (`commonMain`) tracks live streams and their viewers, caps viewers, and decides
    which WebRTC setup messages may be relayed.
  - `ChatHub` (`jvmSharedMain`) tracks who's connected and broadcasts chat, join/leave and roster
    events.
  - `FileStore` + `DropLimits` (`jvmSharedMain`) store shared files and enforce the free-space
    floor, the quota and auto-clear (swept every minute).
  - `JoinPin` (`jvmSharedMain`) holds the optional PIN, the sessions and the per-device lockout.
  - `LocalName` (`jvmSharedMain`) answers mDNS lookups for the hub's `.local` name with JmDNS on
    both platforms. On Android it holds a Wi-Fi multicast lock, and the service itself is still
    advertised via `NsdManager`.
- **Request flow**:

  | Area | Routes |
  |---|---|
  | Dashboard | `GET /` |
  | Join PIN | `GET /auth` state · `POST /auth` check PIN, set session cookie |
  | Host status | `GET /status`: host info + fingerprint |
  | Chat | WebSocket `/ws`: announce a name, then chat |
  | Live streams | over `/ws`: `liveStart` / `liveStop` / `liveWatch` / `liveLeave`, and `signal` messages relayed between a streamer and its viewers (`LiveRegistry`) |
  | Watch together | over `/ws`: `mediaPlay` (a shared file) or `mediaPlayUrl` (a YouTube or https link, checked by `MediaLink`) / `mediaState` / `mediaStop` from the host (`Playback`); everyone gets `mediaSession` and streams `GET /files/{id}` or the link |
  | Drop files | `POST /files?name=&from=&device=` upload, the body is the file (folder files add `path` + `batch`; 413 when full) · `GET /files` list · `GET /files/{id}` · `GET /files/zip` (`?batch=` for one folder) · `GET /files/usage` · `DELETE /files/batch/{batch}` · delete/clear |
  | Browse files | `GET /fs/list`, `/fs/file`, `/fs/zip` · `POST /fs/upload?path=&name=` (body is the file) when writable |
  | Serve site | a separate HTTPS listener on its own port, forwarding requests and WebSocket upgrades |
  | Admin (loopback + admin token) | `/admin/*`, incl. `/admin/pin`, `/admin/storage`, `/admin/browse*` and `/admin/proxy` |

- **Out of scope**: Bluetooth (Wi-Fi/hotspot only), a native Compose UI, end-to-end encryption,
  scanning the whole LAN (the roster is the hub's peers plus mDNS hub discovery), and an external
  database. Chat, the shared-file list and sessions live in memory. Only keys and settings are saved
  to disk: the keystore, the SSH host key, the SSH/FTP credential, the join PIN and the storage
  limits.

## Tech Stack

Exact versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

| Area | Component | Version | Role |
|---|---|---|---|
| Language | Kotlin Multiplatform | `2.4.21` | shared `core` for desktop JVM + Android |
| Build | Gradle (Kotlin DSL) | `8.14.6` | wrapper committed |
| Build | Android Gradle Plugin | `8.13.2` | Android build (`multiDexEnabled` for Netty) |
| Runtime | JDK | 17 target | Java 17 bytecode, built with JDK 21 |
| Android | SDK | compile/target 35, min 26 (Android 8.0; Netty 4.2 needs it) | |
| Server | Ktor + Netty | `3.6.0` / Netty 4.2 | HTTPS + WebSocket; Netty is the engine on both platforms; partial-content for video seeking |
| Proxy | Ktor client (CIO) | `3.6.0` | outbound HTTP + WebSocket forwarding |
| TLS | JSSE / PKCS12 | JDK | self-signed keystore, SHA-256 fingerprint |
| Net | `java.net.NetworkInterface` | JDK | LAN/hotspot address detection |
| SSH | Apache MINA SSHD | `2.20.0` | SSH/SFTP server |
| FTP | Apache FtpServer | `1.2.1` | FTPS server |
| Discovery | JmDNS / `NsdManager` (Android) | `3.6.4` | mDNS/DNS-SD; the `.local` name (JmDNS on both) |
| QR | ZXing | `3.5.4` | scan-to-join QR |
| Data | kotlinx.serialization | `1.11.0` | JSON WebSocket protocol |
| Async | kotlinx.coroutines | `1.11.0` | async server + guarded broadcast |
| Logging | SLF4J + slf4j-simple | `2.0.20` | desktop logging |
| Frontend | plain HTML/CSS/JS | | single-file dashboard, no framework |

## Development

### Prerequisites

- **JDK 17+** (JDK 21 is fine). No Gradle install is needed; the repo has the wrapper.
- **Android SDK** for the app. Point `local.properties` at it, e.g. `sdk.dir=C:\\Android`.
- **Internet** for the first build only. Running servd needs only a JVM (packaged builds bundle
  one) and a Wi-Fi or hotspot network, with or without internet.

### Running from Source

```bash
.\run.ps1                          # Windows (run.bat also works)
```
```bash
./run.sh                           # Linux / macOS
```

These build servd, bind it to your LAN address, and open the dashboard. Both take servd flags
(`.\run.ps1 --port 9443 --no-open`) or any Gradle task (`.\run.ps1 :core:desktopTest`). On
Linux/macOS, use `./run.sh` wherever this README shows `.\run.ps1`.

Why a runner instead of `gradlew`? It runs the server as a direct JVM process, so Ctrl+C is clean.
On Windows it also applies the temp-dir workaround (see Troubleshooting). Prefer `run.ps1` over
`run.bat`, because cmd.exe adds a "Terminate batch job?" prompt after the server has stopped.

### Building & Testing

```bash
.\run.ps1 :core:desktopTest            # unit tests
.\run.ps1 :desktopHost:installDist     # desktop distribution
.\run.ps1 :androidApp:assembleDebug    # debug APK
```

The unit tests (`kotlin-test`) cover:
- **Networking:** LAN-address selection, the chat protocol, service start/stop, the serve-site
  localhost fallback.
- **Access:** the join PIN (sessions, lockout, persistence) and `.local` name validation.
- **Files:** path jailing in browse files, the file store with its storage limits, folder paths,
  and the media types used for previews.

### Android Builds

Build the debug APK, then install it on a USB-connected phone (with USB debugging on):

```bash
.\run.ps1 :androidApp:assembleDebug
```
```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

APKs land in `androidApp/build/outputs/apk/debug/` and `androidApp/build/outputs/apk/release/`.
The debug build needs no keystore.

### Packaging

```bash
.\package.ps1                      # Windows
```
```bash
./package.sh                       # Linux / macOS (-h for help)
```

This builds the desktop app image (with a bundled JRE) and the debug APK. It also builds the signed
release APK if signing is set up. Everything goes into `dist/`:

```
dist/
├─ servd-<version>-windows.zip           desktop app (Windows)
├─ servd-<version>-linux-x86_64.tar.gz   desktop app (Linux/macOS)
├─ servd-<version>-debug.apk             Android debug build
└─ servd-<version>-release.apk           Android release build (only if signed)
```

| `package.ps1` | `package.sh` | Effect |
|---|---|---|
| `-Desktop` / `-Android` | `--desktop` / `--android` | build one platform only |
| `-Installer` | `--installer` | also build the native installer (`.msi` / `.dmg` / `.deb`) |
| | `--appimage` | also build a single-file `<arch>.AppImage` (needs [`appimagetool`](https://appimage.github.io/appimagetool/)) |
| `-Clean` | `--clean` | wipe `dist/` first |

The scripts wrap two Gradle tasks:
- `:desktopHost:jpackageImage` builds a self-contained folder, `desktopHost/build/jpackage/servd/`
  (about 166 MB). Its launcher is `servd\servd.exe` on Windows and `servd/bin/servd` on
  Linux/macOS. It opens a console showing the URL and fingerprint; press Ctrl+C or close it to stop.
- `:desktopHost:jpackageInstaller` builds `.msi` (needs [WiX](https://wixtoolset.org/) on
  `PATH`), `.dmg` or `.deb`.

jpackage builds only for the OS it runs on, so build each platform on that platform.

### Android Signing

Create a keystore once. Reuse it for every release so updates install over the previous version.
`keytool` ships with the JDK:

```bash
keytool -genkeypair -v -keystore servd-release.jks -alias servd -keyalg RSA -keysize 2048 -validity 10000
```

- **Local:** create a git-ignored `androidApp/keystore.properties`:

  ```properties
  storeFile=androidApp/servd-release.jks
  storePassword=your-store-password
  keyAlias=servd
  keyPassword=your-key-password
  ```

  Then run `.\run.ps1 :androidApp:assembleRelease`. Without this file, the APK is built unsigned.

- **CI:** add these repo secrets under **Settings → Secrets and variables → Actions**:

  | Secret | Value |
  |---|---|
  | `ANDROID_KEYSTORE_BASE64` | the keystore, base64-encoded |
  | `ANDROID_STORE_PASSWORD` | store password |
  | `ANDROID_KEY_ALIAS` | key alias, e.g. `servd` |
  | `ANDROID_KEY_PASSWORD` | key password |

  To base64-encode the keystore, run `base64 -w0 servd-release.jks`, or in PowerShell
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("servd-release.jks"))`.

### Releasing

1. Set `servdVersion` in [`gradle.properties`](gradle.properties) and `Servd.VERSION`
   (`core/.../Servd.kt`) to the same version. CI fails if they differ.
2. Commit, then tag `v<servdVersion>` and push:

```bash
git tag v1.8.0 && git push origin v1.8.0
```

[`release.yml`](.github/workflows/release.yml) checks that the tag matches the version. It then
builds the Windows `.zip` (`servd.exe` + JRE), the Linux `.AppImage` and the signed `.apk`, each on
its own OS runner. Finally it publishes a GitHub release with auto-generated notes.

### Troubleshooting

- **`Unable to establish loopback connection`** (Windows): JDK 21's NIO creates a socket file in
  the temp dir, and some Windows temp folders reject it (e.g. under some AV/EDR setups).
  - **servd itself** avoids this by using `~/.servd/sock` instead.
  - **Gradle** can still hit it during a build. `run.ps1` / `run.bat` point `TMP`/`TEMP` at
    `.sock-tmp/`. If you call `gradlew` directly, set them yourself first:
    `set TMP=%CD%\.sock-tmp && set TEMP=%CD%\.sock-tmp && gradlew :desktopHost:run`
- **"Not secure / certificate invalid"**: expected with a self-signed certificate. Check that the
  fingerprint matches the one from the CLI or the Android screen, then proceed.
- **No LAN address found**: join Wi-Fi or start a hotspot, then restart. Otherwise servd binds to
  `127.0.0.1` only.
- **Ctrl+C prints `RejectedExecutionException`**: harmless shutdown noise from an old build.
  Rebuild.
- **`keytool` not found** (common in Git Bash): call it from the JDK's `bin` folder, e.g.
  `"/c/Program Files/Java/jdk-21/bin/keytool"`.

## License

MIT, see [LICENSE](LICENSE).
