# servd

[![CI](https://github.com/wyserian/servd/actions/workflows/ci.yml/badge.svg)](https://github.com/wyserian/servd/actions/workflows/ci.yml)
[![Release](https://github.com/wyserian/servd/actions/workflows/release.yml/badge.svg)](https://github.com/wyserian/servd/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/wyserian/servd?sort=semver&label=release)](https://github.com/wyserian/servd/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
![Platforms](https://img.shields.io/badge/platforms-Windows%20%7C%20Linux%20%7C%20macOS%20%7C%20Android-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)

## About

**servd is a portable local-network hub.** Run it on a computer or an Android phone, and every
device on the same Wi-Fi or hotspot can use it from a browser. Nothing needs installing on those
devices. A phone is a full hub, running the same stack as desktop (Windows, Linux, macOS). The served
dashboard is the UI everywhere; Android adds only a thin native control screen.

The host serves one HTTPS dashboard. It offers chat, file sharing, a shared host folder, and a
reverse proxy for a running web app, plus optional SSH/SFTP and FTPS servers. Each feature is a
tab. The tabs that configure the host appear only on the host itself.

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

- **HTTPS dashboard** on port `8443`, bound to your LAN or hotspot IP, with light and dark themes.
- **Drop files:** drag and drop files or whole folders and they appear on every device. A folder
  shows as one row (open it to see its files) and downloads as a zip with its structure kept.
  Uploads queue up (3 at a time) with progress, speed, time left and cancel. Download files one at
  a time or all as one zip, delete them, or clear them all. The hub never fills its disk, and the
  host can add a storage quota and auto-clear after 1 hour, 24 hours or 7 days.
- **Chat:** real-time chat over WSS, with a live roster of who's connected (joins and leaves).
  Enter sends; Ctrl+Enter adds a new line. Clearing the chat clears it for everyone.
- **Browse files** (optional): the host shares a real folder, read-only or with uploads. Clients
  can open subfolders, download single files, or download the current folder as a zip. The share
  is locked to the chosen folder.
- **Serve site** (optional): a reverse proxy that re-serves a running web app (Node/Vite dev
  server, static host, any HTTP/HTTPS service) to the LAN over servd's HTTPS, on its own port.
  WebSockets and HMR/live reload work.
- **SSH/SFTP and FTPS** (optional): FTPS uses explicit TLS. Both use a generated password and are
  limited to the shared files. There's no OS shell. Each tab shows a copyable connect command.
- **Easy joining:** a scan-to-join QR code, plus mDNS discovery (`_servd._tcp`, or run
  `servd discover`).
- **Join PIN** (optional): the host sets a 4-8 digit PIN, and other devices must enter it before
  they can use the hub, including the serve-site port. It's saved across restarts until turned
  off. Changing it signs everyone out.
- **Host-only tabs:** browse setup, serve site, ssh and ftp, plus a host card with the fingerprint.
  These are served only on loopback, each with a dot showing whether it's live.
- **Desktop CLI:** starts the hub, prints the fingerprint, and opens the dashboard.
- **Android host:** a foreground service keeps the hub alive in the background, and a native screen
  controls it.

## Getting Started

### Installation

Get a build from the [**latest release**](https://github.com/wyserian/servd/releases/latest).
Desktop builds bundle their own Java runtime, so nothing else is needed. To build it yourself, see
[Development](#development).

| Platform | Asset | Run |
|---|---|---|
| Windows | `servd-<version>-windows.zip` | unzip, run `servd\servd.exe` |
| Linux | `servd-<version>-x86_64.AppImage` | `chmod +x`, run it |
| Android | `servd-<version>-release.apk` | install it (allow unknown sources) |

### Connecting Devices

On start, servd prints:

```
servd v1.6.0 - local-network server tool

admin   : https://127.0.0.1:8443   (this machine only)
serving : https://10.205.210.12:8443   (share with other devices)
cert    : self-signed, SHA-256 fingerprint:
          12:3A:07:38:...:F5:CF
keystore: C:\Users\you\.servd\keystore.jks
```

The **admin** URL is the host's own view, where the host-only tabs appear. The **serving** URL is
the one you share.

1. Put the other devices on the **same Wi-Fi**, or on the host's **hotspot**.
2. On each device, open **`https://<serving-address>:8443`**, or scan the QR code in the dashboard.
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
| **chat** | everyone | real-time chat |
| **browse files** | host; clients once a folder is shared | left: browse and download the shared folder; right (host only): folder picker |
| **serve site** | host only | re-serve a running web app to the LAN |
| **ftp** / **ssh** | host only | turn the server on or off; shows the port, password and connect command |

The left column has the device roster and QR code. On the host, it also has a **This host** card
with the address, interface and fingerprint.

**Browse files:** on the host, pick a folder in the picker on the right; a whole drive works too.
Tick **allow uploads** for read + write, then click **share this folder**. Use **stop sharing** to
turn it off; you can switch folders anytime. Paths that try to leave the folder are refused.

**Serve site:**
1. Start your app as usual (e.g. `npm run dev` on `http://localhost:3000`).
2. In the **serve site** tab, enter that target and a port (default `8080`), then click
   **serve this site**.
3. Everyone opens `https://<serving-address>:8080/`.

The app is served at the root, so SPA routes and absolute asset paths keep working. servd adds TLS,
so even an HTTP-only or localhost-only app is reachable and encrypted across the LAN.

### Ports & Data Directory

| Port | Service |
|---|---|
| `8443` | dashboard, chat, files (always on) |
| `2222` | SSH/SFTP (off until enabled) |
| `2121` | FTPS (off until enabled) |
| `8080` | serve site (only while serving; configurable) |

All ports are non-privileged. SSH and FTP share one generated credential, username `servd`. Data
(keystore, shared files, credential, join PIN, storage limits) lives in `~/.servd` on desktop, and
in the app's private storage on Android. Drop files last until cleared: they don't survive a
restart, and the previous run's files are deleted on start.

**Storage limits:** uploads that would leave the host's disk with less than 1 GB or 5% free (whichever
is larger) are refused. That's always on. In the **This host** card (or with `--max-storage` /
`--expire`), the host can also cap drop files with a quota and auto-clear them after 1 hour,
24 hours or 7 days. Both are off by default and saved until changed. Everyone sees the space used and
free under the drop zone. A file that doesn't fit is refused before it uploads, or cut off mid-way
with nothing left behind. SFTP/FTP uploads don't count toward the quota.

**Join PIN:** set it in the **This host** card (or with `--pin`). The host itself never needs it.
Other devices see a PIN screen first, then stay signed in for 30 days, until the PIN changes or the
hub restarts. After 5 wrong tries a device is locked out for 30 s, and the wait doubles with each
further miss.

### Command-Line Options

```bash
servd [start] [flags]     # start the hub
servd discover            # find hubs on the network without typing an IP
```

| Flag | Meaning | Default |
|---|---|---|
| `--port N` | HTTPS port | `8443` |
| `--host IP` | bind address | detected LAN IP, else `127.0.0.1` |
| `--dir PATH` | data dir | `~/.servd` |
| `--no-open` | don't open the browser | opens |
| `--browse-dir PATH` | share a folder on start | off |
| `--browse-write` | allow uploads to that folder | read-only |
| `--proxy-target URL` | serve a running site on start | off |
| `--proxy-port N` | serve-site port | `8080` |
| `--pin DIGITS` / `--pin off` | set (and save) or clear the join PIN | saved PIN, else off |
| `--max-storage 10G` / `off` | drop-files quota (saved; `K`/`M`/`G` sizes) | saved, else none |
| `--expire 1h\|24h\|7d` / `off` | auto-clear drop files after that age (saved) | saved, else off |

## Security

servd is built for a **trusted LAN**, and these limits are deliberate:

- **Client routes** (dashboard, chat, files, roster) are bound to the LAN interface. By default
  they're open to the network, with no account: anyone who can reach the hub and accepts the
  certificate can use them.
- **Join PIN** (optional) gates every client route except the page itself and the PIN check, and
  the serve-site port too. A correct PIN gives a random session cookie (`Secure`, `HttpOnly`,
  `SameSite=Lax`), kept in memory, so a restart or a PIN change signs everyone out. Wrong guesses
  are rate-limited per device. It's a 4-8 digit PIN against casual access on shared networks, not a
  strong password.
- **Admin routes** (`/admin/*`: service toggles, host info, certificate, join PIN, folder and proxy
  setup) answer only on **loopback**. Only the host device can change settings.
- **Drop files** can't fill the host's disk: uploads stop at the free-space floor (1 GB or 5%),
  plus the host's optional quota. Limits are counted as bytes arrive, not from the size a client
  claims.
- **SSH/FTP** always require the password; there's no anonymous login. FTP uses explicit TLS
  (FTPS), and SFTP is encrypted by design. Both are limited to the shared files, with no OS shell.
- **Browse files** is off by default and set up only from the host. Clients can't go above the
  chosen folder, and uploads are refused unless the host allows them.
- **Serve site** is off by default and set up only from the host. While it's on, the target app is
  open to the LAN on its own port, behind the join PIN if one is set. Stopping it closes that port.
- **Encryption** covers the network hop, checked by fingerprint. It is **not end to end**: the
  host can see the traffic, as expected for a hub.

## Architecture

```
servd/
├─ core/            Kotlin Multiplatform engine shared by desktop + Android
│   ├─ commonMain     protocol models, PeerRegistry, LAN-address selection, Service model
│   ├─ jvmSharedMain  Ktor server, TLS, chat hub, file store, folder browser, proxy, QR, SSH, FTP
│   ├─ desktopMain    JmDNS discovery
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
- **Key abstractions** (`commonMain`):
  - `Service` is a startable/stoppable server with an id, port and state. Implementations:
    `HttpService` (always on), `SshService` and `FtpService`.
  - `ServiceManager` starts, stops and toggles services, one call at a time.
  - `ChatHub` tracks who's connected and broadcasts chat, join/leave and roster events.
  - `JoinPin` (`jvmSharedMain`) holds the optional PIN, the sessions and the per-device lockout.
  - `FileStore` + `DropLimits` (`jvmSharedMain`) store drop files and enforce the free-space
    floor, the quota and auto-clear (swept every minute).
- **Request flow**:

  | Area | Routes |
  |---|---|
  | Dashboard | `GET /` |
  | Join PIN | `GET /auth` state · `POST /auth` check PIN, set session cookie |
  | Host status | `GET /status`: host info + fingerprint |
  | Chat | WebSocket `/ws`: announce a name, then chat |
  | Drop files | `POST /files` upload (folder files add `path` + `batch`; 413 when full) · `GET /files` list · `GET /files/{id}` · `GET /files/zip` (`?batch=` for one folder) · `GET /files/usage` · `DELETE /files/batch/{batch}` · delete/clear |
  | Browse files | `GET /fs/list`, `/fs/file`, `/fs/zip` · `POST /fs/upload` when writable |
  | Live site | a separate HTTPS listener on its own port, forwarding requests and WebSocket upgrades |
  | Admin (loopback only) | `/admin/*`, incl. `/admin/pin`, `/admin/storage`, `/admin/browse*` and `/admin/proxy` |

- **Out of scope**: Bluetooth (Wi-Fi/hotspot only), a native Compose UI, end-to-end encryption,
  scanning the whole LAN (the roster is the hub's peers plus mDNS hub discovery), and an external
  database. State is kept in memory.

## Tech Stack

Exact versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

| Area | Component | Version | Role |
|---|---|---|---|
| Language | Kotlin Multiplatform | `2.1.0` | shared `core` for desktop JVM + Android |
| Build | Gradle (Kotlin DSL) | `8.11.1` | wrapper committed |
| Build | Android Gradle Plugin | `8.7.3` | Android build (`multiDexEnabled` for Netty) |
| Runtime | JDK | 17 target | Java 17 bytecode, built with JDK 21 |
| Android | SDK | compile/target 35, min 24 | |
| Server | Ktor + Netty | `3.0.3` | HTTPS + WebSocket; Netty is the engine on both platforms |
| Proxy | Ktor client (CIO) | `3.0.3` | outbound HTTP + WebSocket forwarding |
| TLS | JSSE / PKCS12 | JDK | self-signed keystore, SHA-256 fingerprint |
| Net | `java.net.NetworkInterface` | JDK | LAN/hotspot address detection |
| SSH | Apache MINA SSHD | `2.13.2` | SSH/SFTP server |
| FTP | Apache FtpServer | `1.2.0` | FTPS server |
| Discovery | JmDNS / `NsdManager` (Android) | `3.5.9` | mDNS/DNS-SD |
| QR | ZXing | `3.5.3` | scan-to-join QR |
| Data | kotlinx.serialization | `1.7.3` | JSON WebSocket protocol |
| Async | kotlinx.coroutines | `1.9.0` | async server + guarded broadcast |
| Logging | SLF4J + slf4j-simple | `2.0.16` | desktop logging |
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
.\run.ps1 :core:desktopTest            # unit tests: LAN-address selection, chat hub, protocol
.\run.ps1 :desktopHost:installDist     # desktop distribution
.\run.ps1 :androidApp:assembleDebug    # debug APK
```

Unit tests cover the pure logic with `kotlin-test`. Networked features are checked with a two-client
WSS harness and `curl` smoke checks. The Android build is checked on a Pixel emulator via `adb`.

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
git tag v1.6.0 && git push origin v1.6.0
```

[`release.yml`](.github/workflows/release.yml) checks that the tag matches the version. It then
builds the Windows `.zip` (`servd.exe` + JRE), the Linux `.AppImage` and the signed `.apk`, each on
its own OS runner. Finally it publishes a GitHub release with auto-generated notes.

### Troubleshooting

- **`Unable to establish loopback connection`**: JDK 21's NIO creates a socket file in the temp
  dir, and some Windows temp folders reject it (e.g. under some AV/EDR setups). `run.ps1` /
  `run.bat` already point `TMP`/`TEMP` at `.sock-tmp/`. If you call `gradlew` directly, set them
  yourself first:
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
