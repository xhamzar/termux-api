# Termux API

[![Build status](https://github.com/termux/termux-api/workflows/Build/badge.svg)](https://github.com/termux/termux-api/actions)
[![Join the chat at https://gitter.im/termux/termux](https://badges.gitter.im/termux/termux.svg)](https://gitter.im/termux/termux)

This is an app exposing Android API to command line usage and scripts or programs.

When developing or packaging, note that this app needs to be signed with the same
key as the main Termux app for permissions to work (only the main Termux app are
allowed to call the API methods in this app).

## Installation

Latest version is `v0.53.0`.

Termux:API application can be obtained from [F-Droid](https://f-droid.org/en/packages/com.termux.api/).

Additionally we provide per-commit debug builds for those who want to try
out the latest features or test their pull request. This build can be obtained
from one of the workflow runs listed on [Github Actions](https://github.com/termux/termux-api/actions/workflows/github_action_build.yml?query=branch%3Amaster+event%3Apush)
page.

Signature keys of all offered builds are different. Before you switch the
installation source, you will have to uninstall the Termux application and
all currently installed plugins. Check https://github.com/termux/termux-app#Installation for more info.

## License

Released under the [GPLv3 license](http://www.gnu.org/licenses/gpl-3.0.en.html).

## How API calls are made through the termux-api helper binary

The [termux-api](https://github.com/termux/termux-api-package/blob/master/termux-api.c)
client binary in the `termux-api` package generates two linux anonymous namespace
sockets, and passes their address to the [TermuxApiReceiver broadcast receiver](https://github.com/termux/termux-api/blob/master/app/src/main/java/com/termux/api/TermuxApiReceiver.java)
as in:

```
/system/bin/am broadcast ${BROADCAST_RECEIVER} --es socket_input ${INPUT_SOCKET} --es socket_output ${OUTPUT_SOCKET}
```

The two sockets are used to forward stdin from `termux-api` to the relevant API
class and output from the API class to the stdout of `termux-api`.

## Client scripts

Client scripts which processes command line arguments before calling the
`termux-api` helper binary are available in the [termux-api package](https://github.com/termux/termux-api-package).

Panduan penggunaan dari nol untuk Touch API dan Floating Overlay tersedia di
[docs/TOUCH_AND_OVERLAY_API.md](docs/TOUCH_AND_OVERLAY_API.md).

## Universal camera provider

The new `Camera` API supports one-shot JPEG photos, video-only H.264/MP4 recording, and realtime
JPEG, PNG, RGB24, or I420 streams. Streams can be sent to stdout, numbered files, a loopback TCP
server, or a multi-client abstract Unix socket. A small latest-frame buffer drops stale frames when
a consumer is slow.

A reference `termux-camera` command is available in [`scripts/termux-camera`](scripts/termux-camera).
See [the complete Camera Provider guide](docs/CAMERA_PROVIDER_API.md) for installation, all options,
the framed binary protocol, filesystem Unix socket proxy, and Python/OpenCV/AI examples.

The Overlay can also consume the framed Unix stream directly and display AI text/bounding boxes.
See [Camera, Overlay, OpenCV, and AI Vision](docs/CAMERA_OVERLAY_VISION.md) for the
`termux-overlay camera` and `draw` commands, live camera controls, and reference Python consumers.

## Floating overlay API

The `Overlay` API displays a movable, tappable status panel over other apps. It uses the existing
Termux:API broadcast and local-socket IPC. Install the `termux-api` package, grant **Display over
other apps** to Termux:API, then call the package's internal helper:

```sh
OVERLAY_API="$PREFIX/libexec/termux-api"

# Open the Android permission screen and inspect current state.
"$OVERLAY_API" Overlay --es action permission
"$OVERLAY_API" Overlay --es action status

# Start or update the overlay. Coordinates and dimensions are screen pixels.
"$OVERLAY_API" Overlay --es action start --es text "Termux ready"
"$OVERLAY_API" Overlay --es action show --es text "Build complete"
"$OVERLAY_API" Overlay --es action move --ei x 40 --ei y 200
"$OVERLAY_API" Overlay --es action resize --ei width 700 --ei height 240

# Build an interactive panel with progress and application-defined buttons.
"$OVERLAY_API" Overlay --es action update --es text "Deploying" --ei progress 35 \
  --es buttons '[{"id":"cancel","label":"Cancel"},{"id":"details","label":"Details"}]'

# Customize colors, transparency, shape, and text. Colors use #RRGGBB or #AARRGGBB.
"$OVERLAY_API" Overlay --es action style --es background_color '#101820' \
  --ei background_opacity 65 --es text_color '#00FF88' --es border_color '#00FF88' \
  --es button_color '#355070' --es button_text_color '#FFFFFF' \
  --ei text_size 18 --ei corner_radius 20 --es text_align center --ez show_status false

# Create a transparent, click-through HUD; reset_style restores all defaults.
"$OVERLAY_API" Overlay --es action style --ei background_opacity 0 \
  --ei border_width 0 --ez touchable false --ez draggable false
"$OVERLAY_API" Overlay --es action reset_style

# Read queued tap/button/move events. Events are removed after reading by default.
"$OVERLAY_API" Overlay --es action events
"$OVERLAY_API" Overlay --es action events --ez clear false
"$OVERLAY_API" Overlay --es action clear_events

# Display a local image. Use an absolute path visible to Termux:API.
"$OVERLAY_API" Overlay --es action content --es type image \
  --es source "$HOME/storage/shared/Pictures/status.png"

# Display an HTTPS page. JavaScript is disabled unless explicitly enabled.
"$OVERLAY_API" Overlay --es action content --es type web \
  --es source "https://example.com/dashboard"
"$OVERLAY_API" Overlay --es action content --es type web \
  --es source "https://example.com/app" --ez javascript true --ez focusable true

# Play a local file or HTTPS video and control it from Termux.
"$OVERLAY_API" Overlay --es action content --es type video \
  --es source "$HOME/storage/shared/Movies/demo.mp4" --ez autoplay true
"$OVERLAY_API" Overlay --es action pause
"$OVERLAY_API" Overlay --es action seek --ei position_ms 30000
"$OVERLAY_API" Overlay --es action volume --ei volume 50
"$OVERLAY_API" Overlay --es action play
"$OVERLAY_API" Overlay --es action clear_content

# Hide keeps the explicitly started service available; stop removes the view and service.
"$OVERLAY_API" Overlay --es action hide
"$OVERLAY_API" Overlay --es action stop
```

The service is internal to the app, runs in the foreground only after `start` or `show`, and is not
restarted automatically after it is stopped or killed. Button definitions are limited to 8 entries,
and the in-memory event queue retains the latest 100 events. Set `progress` to `-1` and `buttons` to
`[]` to hide those controls. Image sources are limited to readable local files of at most 25 MiB;
large images are downsampled before display. Video accepts readable local files or HTTPS URLs. Web
content only accepts HTTPS, cannot access local files or `content://` providers, and has JavaScript
disabled by default. Enable `focusable` only when a web form needs keyboard input because a focusable
overlay temporarily receives input focus instead of the app underneath it. Media and WebView resources
are released by `clear_content`, when another content item replaces them, and by `stop`.

## Ideas

- Wifi network search and connect.
- Add extra permissions to the app to (un)install apps, stop processes etc.
