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

# Hide keeps the explicitly started service available; stop removes the view and service.
"$OVERLAY_API" Overlay --es action hide
"$OVERLAY_API" Overlay --es action stop
```

The service is internal to the app, runs in the foreground only after `start` or `show`, and is not
restarted automatically after it is stopped or killed.

## Ideas

- Wifi network search and connect.
- Add extra permissions to the app to (un)install apps, stop processes etc.
