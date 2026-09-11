# Camera, Overlay, OpenCV, and AI Vision

This extension keeps one universal Camera2 provider and attaches independent consumers to its
framed stream:

```text
Camera Provider -> abstract Unix socket -> Overlay preview
                                  +-----> Python/OpenCV -> AI engine -> draw commands
                                  +-----> another application
```

The provider supports multiple socket clients. Every consumer retains only its latest pending
frame, reconnects after the provider restarts, preserves frame sequence/timestamp metadata, and
drops stale frames instead of growing latency.

## Install the reference commands

The Android application implements the `Camera` and `Overlay` API methods. Development wrappers
are in `scripts/` and should be installed by `termux-api-package` for production:

```sh
install -m 755 scripts/termux-camera scripts/termux-overlay "$PREFIX/bin/"
termux-camera --help
termux-overlay camera --help
```

## Camera to floating overlay

Start one framed Unix stream, then connect the Overlay client:

```sh
termux-camera stream --camera 0 --width 1280 --height 720 --fps 30 \
  --format jpeg --output unix --socket-name termux.camera.frames --protocol framed

termux-overlay camera start --socket termux.camera.frames \
  --x 20 --y 120 --width 640 --height 420
termux-overlay camera status
termux-overlay camera resize --width 480 --height 340
termux-overlay camera position --x 30 --y 200
termux-overlay camera stop
```

The Overlay consumer decodes JPEG, PNG, packed RGB24, and planar I420/YUV420. Android performs
normal hardware-accelerated composition of the `ImageView` and drawing canvas when available;
compressed/raw frame decoding remains CPU work. JPEG generally gives the best balance for a 720p
preview, while raw formats avoid codec latency when the consumer already needs raw pixels.

`camera status` reports connection/reconnect state, source format and dimensions, sequence and
timestamp, decoded/received/dropped counters, and the number of active drawing marks.

## AI results on the preview

Coordinates use source-frame pixels and are scaled into the preview with its aspect ratio:

```sh
termux-overlay draw text --text 'Objects: 1' --x 24 --y 40 --color '#FFFFFF'
termux-overlay draw box --x 210 --y 100 --width 260 --height 480 \
  --label Person --confidence 98 --color '#00FF00' --stroke-width 3
termux-overlay draw clear
```

The canvas retains at most 100 text/box marks. A typical inference loop clears the previous result
before drawing the current detections.

## Python and OpenCV consumer

`examples/termux_camera.py` exposes `TermuxCamera.read_frame()` for raw metadata/payload and
`TermuxCamera.read()` for a decoded OpenCV BGR NumPy array (`cv2.Mat` in Python):

```python
import cv2
from termux_camera import TermuxCamera

with TermuxCamera("termux.camera.frames") as camera:
    while True:
        frame = camera.read(timeout=10)
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
```

Run it with the examples directory on Python's import path:

```sh
PYTHONPATH=examples python your_program.py
```

The reader handles exact framed-protocol reads, JPEG/PNG decode, RGB-to-BGR conversion, I420-to-BGR
conversion, automatic reconnect, and latest-frame synchronization. OpenCV and NumPy are optional
consumer dependencies and are not embedded in Termux:API.

## AI pipeline adapters

`examples/vision_pipeline.py` supplies a neutral `VisionProcessor` interface plus reference
processors for:

- YOLO object detection through Ultralytics native/exported models;
- ONNX and TensorFlow Lite YOLO exports through their model paths;
- MediaPipe face detection;
- OCR through pytesseract;
- OpenCV CSRT tracking from an initial ROI.

Examples:

```sh
# Native or exported YOLO model (.pt, .onnx, or .tflite).
PYTHONPATH=examples python examples/vision_pipeline.py \
  --engine yolo --model model.onnx --max-fps 10

# MediaPipe face boxes.
PYTHONPATH=examples python examples/vision_pipeline.py --engine mediapipe

# OCR boxes and recognized text.
PYTHONPATH=examples python examples/vision_pipeline.py --engine ocr

# Track an initial x,y,width,height region.
PYTHONPATH=examples python examples/vision_pipeline.py \
  --engine tracking --roi 200,100,240,360
```

Install only the Python packages needed by the selected engine. Model licenses, input shapes,
quantization, acceleration delegates, and engine-specific preprocessing/postprocessing remain the
client's responsibility. Use `--no-overlay` to emit JSON detections instead of draw commands.

This is intentionally not an OpenCV-only camera: any language or runtime can consume the documented
TCAM framing from the same socket. See `CAMERA_PROVIDER_API.md` for the binary header definition.

## Live Camera2 controls

Controls apply to the active provider:

```sh
termux-camera control --zoom 2.0
termux-camera control --autofocus auto
termux-camera control --flash torch
termux-camera control --exposure -1

# Switch camera or reconfigure capture. The provider reconnects existing Unix clients.
termux-camera control --camera 1 --width 1280 --height 720 --fps 30
```

Supported autofocus modes are `continuous`, `auto`, and `off`; flash modes are `off` and `torch`.
`status` returns current and maximum zoom plus active focus, flash, and exposure values. Unsupported
camera capabilities return an error instead of silently applying an invalid request.

Camera/resolution/FPS changes restart a persistent `unix`, `tcp`, or `file` stream. They are rejected
for recordings and pipe streams because a separate control command cannot replace the original
stdout pipe. Stop/restart those modes explicitly. Dynamic zoom/focus/flash/exposure controls do not
restart the stream.

## Performance behavior

For a 720p/30 FPS source while AI runs concurrently:

- use one Unix provider with separate Overlay and AI clients;
- keep Camera at 30 FPS, but cap inference to the model's sustainable rate;
- prefer JPEG for preview/network bandwidth or I420/RGB when inference avoids conversion;
- keep inference and drawing off the socket-reader thread;
- process the newest frame and never append frames to an unbounded queue;
- use an NNAPI/GPU/delegate supported by the selected AI runtime when appropriate.

Actual FPS depends on camera hardware, thermal limits, encoder cost, and model runtime. The provider
uses a two-image Camera2 buffer, one pending conversion, and one pending frame per client.

## Permissions

- Camera capture/control requires Android `CAMERA` permission for Termux:API.
- Floating preview requires **Display over other apps**. Run `termux-overlay permission` to open its
  settings screen.
- Accessibility permission is not required for camera preview or drawing. It is only relevant to the
  separate Touch API.

Overlay status includes `permission_granted`, `camera_permission_granted`, and
`accessibility_required`. Recent Android releases can still restrict camera foreground-service
startup; begin capture while Termux/Termux:API is allowed to use the camera.
