<p align="center">
  <img src="icon.png" width="144" alt="InfoMemories icon">
</p>

<h1 align="center">InfoMemories</h1>

A CPU-Z-style hardware reader built specifically to **not crash the Sony
A6300** (and other PlayMemories/Android-subsystem Sony cameras).

Shows its data automatically on launch (no buttons to hunt for); the
camera's **MENU key exits** the app. Launcher display name and the
72×96 portrait icon (Sony's PlayMemories launcher format) come from app
resources, so it sits alongside the camera's own apps.

## Why CPU-Z / AIDA64 don't survive on this camera

A full camera restart (not just an app force-close) means something below
the app layer died. The usual suspects in CPU-Z/AIDA64:

- **GL context probing** — both create an EGL context to query
  `glGetString()` for GPU info; on this camera the GPU belongs to the
  imaging pipeline.
- **Binder service / sensor enumeration** — `ServiceManager`,
  `SensorManager`, battery/thermal HALs that don't exist or aren't safe to
  poke here.
- **Aggressive sysfs polling** — fast cpufreq/thermal sampling can block or
  fault Sony's kernel.

## What this app does instead

Only static, read-only sources, each under a hard timeout:

| Section   | Sources |
|-----------|---------|
| System    | `Build.*`, `/proc/version`, `/proc/uptime` |
| SoC/Board | `/proc/device-tree/{model,compatible}` (and `/sys/firmware/devicetree/base/`), `ro.board.platform` & friends from `build.prop` files, `/sys/devices/soc0/soc_id` |
| CPU       | `/proc/cpuinfo`, `/sys/devices/system/cpu/{online,cpufreq}` (one-shot reads, no polling loop) |
| Memory    | `/proc/meminfo` |
| Storage   | `StatFs` on `/`, `/data`, `/cache` |
| Display   | `DisplayMetrics` (in-process, no binder) |

Safety rules enforced in code:

- **Zero permissions** (verified via `aapt2 dump badging`).
- No OpenGL/EGL, no `SensorManager`, no `ServiceManager`, no
  `Runtime.exec()`, no root/busybox probing.
- Every file read runs on a worker thread with a **1.5 s timeout**; whole
  sections have a **5 s timeout**. A blocking node is abandoned and
  reported as `(timeout)` instead of hanging the UI or the system.
- `hardwareAccelerated=false` in the manifest — software rendering only.

## Build

Requires JDK 17+ and an Android SDK with `platforms;android-34` and
`build-tools;34.0.0`:

```sh
export ANDROID_HOME=/opt/android-sdk
./build.sh
# → build/camhwinfo.apk
```

## Install

Sideload `build/camhwinfo.apk` with your usual OpenMemories method.

## Version history

- **1.0** — first working builds; manual refresh buttons, then
  auto-read on launch + MENU=exit, renamed to InfoMemories.
- **1.1** — launcher name from a string resource (how OpenMemories-Tweak
  does it), first icon.
- **1.2** — 72×96 portrait icon in `drawable-nodpi` (square launcher
  icons get stretched by the PlayMemories launcher).
- **1.3** — clean rebuild: resource label
  "InfoMemories", 72×96 icon letterboxed from the launcher art, MENU
  exit, auto-read on launch, zero permissions.
- **1.4** *(current, versionCode 5)* — new launcher icon (orange
  question-mark + camera artwork supplied by the author); icon shown at
  the top of this README.

## Project layout

```
sony-hwinfo/
├── build.sh                     aapt2 → javac → d8 → zipalign → apksigner
├── keystore.jks                 generated debug key on first build (not in git)
└── app/src/main/
    ├── AndroidManifest.xml
    ├── res/values/strings.xml   app_name = "InfoMemories"
    ├── res/drawable-nodpi/ic_launcher.png   72x96 Sony launcher icon
    └── java/dev/camsysinfo/camhwinfo/MainActivity.java   (all UI programmatic)
```
