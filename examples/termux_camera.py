"""Latest-frame Python/OpenCV client for the Termux TCAM Unix-socket protocol."""

from __future__ import annotations

from dataclasses import dataclass
import socket
import struct
import threading
import time
from typing import Optional


HEADER = struct.Struct(">4sBBHIIQQI")
MAGIC = b"TCAM"
MAX_PAYLOAD = 64 * 1024 * 1024
FORMATS = {1: "jpeg", 2: "png", 3: "rgb", 4: "yuv420"}


@dataclass(frozen=True)
class CameraFrame:
    format: str
    width: int
    height: int
    sequence: int
    timestamp_ns: int
    payload: bytes

    def to_opencv(self):
        """Return a BGR NumPy array, the Python representation used by cv2.Mat."""
        import cv2
        import numpy as np

        if self.format in ("jpeg", "png"):
            encoded = np.frombuffer(self.payload, dtype=np.uint8)
            image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
        elif self.format == "rgb":
            expected = self.width * self.height * 3
            if len(self.payload) != expected:
                raise ValueError(f"invalid RGB24 payload: {len(self.payload)} != {expected}")
            rgb = np.frombuffer(self.payload, dtype=np.uint8).reshape(
                self.height, self.width, 3
            )
            image = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        elif self.format == "yuv420":
            if self.width % 2 or self.height % 2:
                raise ValueError("OpenCV I420 conversion requires even frame dimensions")
            expected = self.width * self.height * 3 // 2
            if len(self.payload) != expected:
                raise ValueError(f"invalid I420 payload: {len(self.payload)} != {expected}")
            i420 = np.frombuffer(self.payload, dtype=np.uint8).reshape(
                self.height * 3 // 2, self.width
            )
            image = cv2.cvtColor(i420, cv2.COLOR_YUV2BGR_I420)
        else:
            raise ValueError(f"unsupported frame format: {self.format}")
        if image is None:
            raise ValueError(f"OpenCV could not decode {self.format} frame")
        return image


class TermuxCamera:
    """Reconnect automatically and retain only the newest complete camera frame."""

    def __init__(self, socket_name: str = "termux.camera.frames") -> None:
        if not socket_name or "/" in socket_name or "\x00" in socket_name:
            raise ValueError("socket_name must be an Android abstract Unix socket name")
        self.socket_name = socket_name
        self._condition = threading.Condition()
        self._latest: Optional[CameraFrame] = None
        self._last_read_sequence: Optional[int] = None
        self._socket: Optional[socket.socket] = None
        self._closed = False
        self._connected = False
        self._error: Optional[str] = None
        self.received_frames = 0
        self.dropped_frames = 0
        self._thread = threading.Thread(
            target=self._reader_loop, name="termux-camera-reader", daemon=True
        )
        self._thread.start()

    @property
    def connected(self) -> bool:
        with self._condition:
            return self._connected

    @property
    def error(self) -> Optional[str]:
        with self._condition:
            return self._error

    def read_frame(self, timeout: Optional[float] = None) -> CameraFrame:
        """Wait for and return the newest frame not returned by this instance before."""
        deadline = None if timeout is None else time.monotonic() + timeout
        with self._condition:
            while not self._closed:
                if self._latest is not None and (
                    self._last_read_sequence is None
                    or self._latest.sequence != self._last_read_sequence
                ):
                    frame = self._latest
                    self._last_read_sequence = frame.sequence
                    return frame
                remaining = None if deadline is None else deadline - time.monotonic()
                if remaining is not None and remaining <= 0:
                    raise TimeoutError(self._error or "camera frame timed out")
                self._condition.wait(remaining)
        raise EOFError("camera client is closed")

    def read(self, timeout: Optional[float] = None):
        """Return the newest frame decoded as an OpenCV BGR matrix."""
        return self.read_frame(timeout).to_opencv()

    def _reader_loop(self) -> None:
        while not self._closed:
            connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            try:
                connection.connect("\x00" + self.socket_name)
                with self._condition:
                    self._socket = connection
                    self._connected = True
                    self._error = None
                    self._latest = None
                    self._last_read_sequence = None
                    self._condition.notify_all()
                self._read_connection(connection)
                raise EOFError("camera stream closed")
            except (OSError, EOFError, ValueError) as error:
                with self._condition:
                    if not self._closed:
                        self._error = str(error)
                    self._connected = False
                    self._condition.notify_all()
            finally:
                with self._condition:
                    if self._socket is connection:
                        self._socket = None
                connection.close()
            if not self._closed:
                time.sleep(0.5)

    def _read_connection(self, connection: socket.socket) -> None:
        previous_sequence: Optional[int] = None
        stream = connection.makefile("rb", buffering=64 * 1024)
        try:
            while not self._closed:
                header = self._read_exact(stream, HEADER.size)
                magic, version, fmt, header_size, width, height, sequence, timestamp, length = (
                    HEADER.unpack(header)
                )
                if magic != MAGIC or version != 1 or header_size < HEADER.size:
                    raise ValueError("unsupported TCAM frame header")
                if header_size > HEADER.size:
                    self._read_exact(stream, header_size - HEADER.size)
                if fmt not in range(5) or length > MAX_PAYLOAD:
                    raise ValueError("invalid TCAM frame metadata")
                payload = self._read_exact(stream, length)
                if fmt == 0:
                    raise ValueError(payload.decode("utf-8", errors="replace"))
                frame = CameraFrame(
                    FORMATS[fmt], width, height, sequence, timestamp, payload
                )
                with self._condition:
                    self.received_frames += 1
                    if previous_sequence is not None and sequence > previous_sequence + 1:
                        self.dropped_frames += sequence - previous_sequence - 1
                    if self._latest is not None and (
                        self._last_read_sequence is None
                        or self._latest.sequence != self._last_read_sequence
                    ):
                        self.dropped_frames += 1
                    self._latest = frame
                    previous_sequence = sequence
                    self._condition.notify_all()
        finally:
            stream.close()

    @staticmethod
    def _read_exact(stream, size: int) -> bytes:
        data = bytearray()
        while len(data) < size:
            chunk = stream.read(size - len(data))
            if not chunk:
                raise EOFError("incomplete camera frame")
            data.extend(chunk)
        return bytes(data)

    def close(self) -> None:
        with self._condition:
            self._closed = True
            connection = self._socket
            self._condition.notify_all()
        if connection is not None:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self._thread.join(timeout=2)

    def __enter__(self) -> "TermuxCamera":
        return self

    def __exit__(self, *_args) -> None:
        self.close()


if __name__ == "__main__":
    import cv2

    with TermuxCamera() as camera:
        while True:
            cv2.imshow("Termux Camera", camera.read(timeout=10))
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
