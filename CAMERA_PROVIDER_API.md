# Universal Camera Provider API
 
`Camera` is a Camera2-based provider for one-shot photos, video recording, and realtime frame
streams. It exposes neutral byte/file/socket interfaces, so consumers are not tied to OpenCV or
any particular AI runtime.
 
The data pipeline is:
 
```text
Camera sensor -> ImageReader analyzer -> frame converter -> stream dispatcher -> clients
```
 
The analyzer keeps two camera buffers. Conversion and every network client keep only one pending
frame; when a consumer is slow, the older pending frame is dropped instead of increasing latency.
TCP and Unix servers accept up to 16 simultaneous clients.
 
## Command installation
 
This application implements the Android API method. A reference command wrapper is provided at
[`scripts/termux-camera`](../scripts/termux-camera); the production Termux package should install
the same wrapper through `termux-api-package`.
 
For development builds, copy it into Termux after installing the matching Termux:API APK:
 
```sh
install -m 755 scripts/termux-camera "$PREFIX/bin/termux-camera"
```
 
The wrapper invokes `$PREFIX/libexec/termux-api Camera`. Set `TERMUX_CAMERA_API_HELPER` only when
testing a helper at another path. Grant Camera permission to Termux:API when Android prompts. A
persistent stream or recording has an ongoing notification with a Stop action.
 ## Commands
 
### Camera information
 
List every camera and its Camera2 YUV sizes:
 
```sh
termux-camera info
termux-camera info --camera 0 --width 1280 --height 720 --fps 30 --format jpeg
```
 
The requested size and FPS are matched to the closest supported configuration. For example:
 
```json
{
  "success": true,
  "cameras": [{
    "camera_id": "0",
    "facing": "back",
    "sensor_orientation": 90,
    "width": 1280,
    "height": 720,
    "fps": 30,
    "fps_range": [15, 30],
    "format": "jpeg",
    "formats": ["jpeg", "png", "rgb", "yuv420"],
    "yuv_output_sizes": [{"width": 640, "height": 480}]
  }]
}
```
### Photo
 
Capture one JPEG. With no path, the wrapper creates a timestamped file in `~/camera`.
 
```sh
termux-camera photo
termux-camera photo --camera 1 --file "$HOME/camera/front.jpg"
```
 
Photo mode currently uses the existing full-resolution JPEG capture path. It does not overwrite an
existing destination.
 
### Video recording
 
Start a video-only H.264/MP4 recording in the background, then stop it cleanly:
 
```sh
termux-camera record --camera 0 --width 1920 --height 1080 --fps 30 \
  --bitrate 12000000 --file "$HOME/camera/video.mp4"
termux-camera status
termux-camera stop
```
 
No microphone permission is required because this mode does not record audio. Do not kill the
Termux:API process while recording; use `stop` so the MP4 trailer is finalized.
 
### Realtime stream
 
The default backend is stdout using the framed protocol:
 
```sh
termux-camera stream --camera 0 --width 1280 --height 720 --fps 30 \
  --format jpeg | program
```
 
Available frame formats are:
 
| Value | Payload |
|---|---|
| `jpeg` | Complete JPEG image |
| `png` | Complete PNG image |
| `rgb` | Packed RGB24, row-major, 8-bit `R,G,B` bytes per pixel |
| `yuv420` | Planar I420: full Y plane, then quarter-resolution U, then V |
 
Available output backends can be combined with a comma-separated list:
 
```sh
# Numbered files such as frame000001.jpg. Existing frame files are preserved.
termux-camera stream --output file --directory "$HOME/camera/frames" --format jpeg
 
# Framed loopback TCP server, suitable for a custom binary client.
termux-camera stream --output tcp --port 9000 --protocol framed --format rgb
 
# HTTP multipart MJPEG server. JPEG over TCP selects this protocol by default.
termux-camera stream --output tcp --port 9000 --protocol mjpeg --format jpeg
 
# Android abstract-namespace Unix socket.
termux-camera stream --output unix --socket-name termux.camera.frames --protocol framed
socat ABSTRACT-CONNECT:termux.camera.frames - > frames.bin
 # Filesystem Unix socket. The wrapper starts a loopback TCP server and a socat proxy.
termux-camera stream --socket "$HOME/camera.sock" --protocol framed
```
 
The filesystem `--socket` form requires `pkg install socat`. Android's public `LocalServerSocket`
API creates abstract Unix sockets, not pathname sockets, which is why the wrapper supplies the local
proxy. TCP binds only to the loopback interface. Stop all backends and the optional proxy with:
 
```sh
termux-camera status
termux-camera stop
```
 
The socket servers do not authenticate clients. Loopback prevents remote-network access, but other
software on the same Android device may still be able to connect; stop the provider when it is not
needed and do not expose its port through a forwarding tool.
 
Only one provider mode can own the camera at a time. Starting another stream or recording replaces
the current one. A pipe-only stream also stops automatically when its stdout consumer disconnects.
 
## Framed protocol
 
`framed` is the recommended protocol for JPEG, PNG, RGB, and YUV420. Every frame consists of a
36-byte big-endian header followed by exactly `payload_length` bytes:
 `framed` is the recommended protocol for JPEG, PNG, RGB, and YUV420. Every frame consists of a
36-byte big-endian header followed by exactly `payload_length` bytes:
 
| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `TCAM` |
| 4 | 1 | Protocol version, currently `1` |
| 5 | 1 | Format: `0` error, `1` JPEG, `2` PNG, `3` RGB24, `4` I420 |
| 6 | 2 | Header size, currently `36` |
| 8 | 4 | Width |
| 12 | 4 | Height |
| 16 | 8 | Monotonic frame sequence |
| 24 | 8 | Camera timestamp in nanoseconds |
| 32 | 4 | Payload length |
 
An error frame has format `0`, zero dimensions, and a UTF-8 JSON payload. Readers must use exact
reads because a pipe or socket read may return only part of a header or payload.
 
Minimal Python reader for stdin:
 
```python
import io
import struct
import sys
 
HEADER = struct.Struct(">4sBBHIIQQI")
 def read_exact(stream: io.BufferedReader, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = stream.read(size - len(data))
        if not chunk:
            raise EOFError
        data.extend(chunk)
    return bytes(data)
 
while True:
    magic, version, fmt, header_size, width, height, sequence, timestamp, length = \
        HEADER.unpack(read_exact(sys.stdin.buffer, HEADER.size))
    if magic != b"TCAM" or version != 1 or header_size != HEADER.size:
        raise RuntimeError("unsupported camera stream")
    payload = read_exact(sys.stdin.buffer, length)
    print(sequence, fmt, width, height, timestamp, len(payload), file=sys.stderr)
    # Decode or pass payload to the inference runtime here.
```
 
For TCP, replace `sys.stdin.buffer` with `socket.create_connection(("127.0.0.1", 9000)).makefile("rb")`.
JPEG and PNG can be decoded by Pillow/OpenCV; RGB can be reshaped to `(height, width, 3)` with
NumPy; I420 can be converted or supplied directly to a runtime that accepts YUV.
 
## MJPEG and OpenCV
`mjpeg` requires `--format jpeg`. TCP clients receive an HTTP
`multipart/x-mixed-replace` response, so common video readers can open it directly:
 
```sh
termux-camera stream --output tcp --port 9000 --format jpeg --protocol mjpeg
```
 
```python
import cv2
 
capture = cv2.VideoCapture("http://127.0.0.1:9000/")
while True:
    ok, frame = capture.read()
    if not ok:
        break
    # OpenCV, MediaPipe, YOLO, TFLite, or ONNX processing goes here.
```
 
Pipe and abstract-Unix `mjpeg` outputs are a raw concatenation of complete JPEG images, without HTTP
headers. Use `framed` when unambiguous boundaries and metadata are needed.
 
## Direct helper API
 
Scripts can bypass the wrapper and call the internal helper. String options use `--es`, integers
use `--ei`, and booleans use `--ez`:
 ```sh
API="$PREFIX/libexec/termux-api"
 
"$API" Camera --es action info --es camera 0 \
  --ei width 1280 --ei height 720 --ei fps 30 --es format jpeg
 
"$API" Camera --es action stream --es camera 0 \
  --ei width 1280 --ei height 720 --ei fps 30 --es format jpeg \
  --es output tcp,unix --ei port 9000 --es socket_name termux.camera.frames \
  --es protocol framed --ei quality 85
 
"$API" Camera --es action status
"$API" Camera --es action stop
```
 
Stream options:
 
| Option | Default | Notes |
|---|---:|---|
| `camera` | `0` | Camera2 ID, which is a string even when numeric |
| `width`, `height` | `1280`, `720` | Closest supported YUV size is selected |
| `fps` | `30` | Closest Camera2 AE FPS range is selected |
| `format` | `jpeg` | `jpeg`, `png`, `rgb`, or `yuv420` |
| `output` | `pipe` | Any combination of `pipe,file,tcp,unix` |
| `directory` | required for file | Existing writable absolute directory |
| `port` | `9000` | TCP loopback port |
| `socket_name` | `termux.camera.frames` | Abstract Unix name, without `/` |
| `protocol` | `framed` | `framed` or `mjpeg` |
| `quality` | `85` | JPEG quality from 1 to 100 |
 `status` reports the actual selected width, height, FPS, active outputs, client count, emitted frame
count, and dropped-frame count.
 
## AI integration and performance
 
The provider deliberately does not contain OpenCV-, TensorFlow-, MediaPipe-, YOLO-, or ONNX-specific
logic. A client reads a standard image or raw tensor-friendly pixel buffer, performs its own resize,
normalization and inference, then discards the frame. For low latency:
 
- prefer `yuv420` or `rgb` if the model can consume it without JPEG/PNG decoding;
- prefer JPEG over PNG when bandwidth matters;
- request only the resolution and FPS the model needs;
- perform inference on the latest frame and do not build an unbounded client-side queue;
- use one server stream for multiple consumers instead of reopening the camera.
 
Frames keep the camera sensor orientation; the provider does not rotate pixels. Use
`sensor_orientation` from `info` together with the device display rotation when upright output is
required.
 
On recent Android versions, camera access is subject to foreground-service and while-in-use privacy
rules. Start the provider while Termux/Termux:API is allowed to access the camera. The ongoing
notification keeps a successfully started stream or recording visible, but Android may still stop
it for privacy, battery, or process-management reasons.
 
