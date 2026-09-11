"""Reference AI vision pipeline built on the universal TermuxCamera client.

Optional engines are imported only when selected. Model preprocessing and output formats remain
owned by each engine; the camera provider itself stays engine-neutral.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
import subprocess
import time
from typing import Iterable, Protocol

from termux_camera import TermuxCamera


@dataclass(frozen=True)
class Detection:
    x: int
    y: int
    width: int
    height: int
    label: str
    confidence: int = -1


class VisionProcessor(Protocol):
    def process(self, frame) -> Iterable[Detection]: ...


class YoloProcessor:
    """Ultralytics adapter for native, ONNX, or TensorFlow Lite exported YOLO models."""

    def __init__(self, model_path: str) -> None:
        from ultralytics import YOLO

        self.model = YOLO(model_path)

    def process(self, frame) -> Iterable[Detection]:
        result = self.model.predict(frame, verbose=False)[0]
        names = result.names
        detections = []
        for box in result.boxes:
            left, top, right, bottom = (int(value) for value in box.xyxy[0].tolist())
            class_id = int(box.cls[0])
            detections.append(
                Detection(
                    left,
                    top,
                    max(1, right - left),
                    max(1, bottom - top),
                    str(names[class_id]),
                    round(float(box.conf[0]) * 100),
                )
            )
        return detections


class MediaPipeFaceProcessor:
    def __init__(self, confidence: float = 0.5) -> None:
        import mediapipe as mp

        self._detector = mp.solutions.face_detection.FaceDetection(
            model_selection=0, min_detection_confidence=confidence
        )

    def process(self, frame) -> Iterable[Detection]:
        import cv2

        height, width = frame.shape[:2]
        result = self._detector.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        detections = []
        for detected in result.detections or []:
            box = detected.location_data.relative_bounding_box
            detections.append(
                Detection(
                    max(0, round(box.xmin * width)),
                    max(0, round(box.ymin * height)),
                    max(1, round(box.width * width)),
                    max(1, round(box.height * height)),
                    "Face",
                    round(detected.score[0] * 100),
                )
            )
        return detections


class OcrProcessor:
    def __init__(self, minimum_confidence: int = 40) -> None:
        self.minimum_confidence = minimum_confidence

    def process(self, frame) -> Iterable[Detection]:
        import pytesseract

        data = pytesseract.image_to_data(
            frame, output_type=pytesseract.Output.DICT
        )
        detections = []
        for index, raw_text in enumerate(data["text"]):
            text = raw_text.strip()
            confidence = round(float(data["conf"][index]))
            if not text or confidence < self.minimum_confidence:
                continue
            detections.append(
                Detection(
                    int(data["left"][index]),
                    int(data["top"][index]),
                    max(1, int(data["width"][index])),
                    max(1, int(data["height"][index])),
                    text,
                    max(0, min(100, confidence)),
                )
            )
        return detections


class OpenCvTrackerProcessor:
    def __init__(self, roi: tuple[int, int, int, int]) -> None:
        import cv2

        constructor = getattr(cv2, "TrackerCSRT_create", None)
        if constructor is None and hasattr(cv2, "legacy"):
            constructor = getattr(cv2.legacy, "TrackerCSRT_create", None)
        if constructor is None:
            raise RuntimeError("OpenCV tracking module is not installed")
        self._tracker = constructor()
        self._roi = roi
        self._initialized = False

    def process(self, frame) -> Iterable[Detection]:
        if not self._initialized:
            self._tracker.init(frame, self._roi)
            self._initialized = True
            return [Detection(*self._roi, "Tracked")]
        ok, box = self._tracker.update(frame)
        if not ok:
            return []
        x, y, width, height = (round(value) for value in box)
        return [Detection(x, y, max(1, width), max(1, height), "Tracked")]


class OverlayRenderer:
    def __init__(self, executable: str = "termux-overlay") -> None:
        self.executable = executable

    def render(self, detections: Iterable[Detection]) -> None:
        subprocess.run(
            [self.executable, "draw", "clear"], check=True,
            stdout=subprocess.DEVNULL,
        )
        for detection in detections:
            command = [
                self.executable,
                "draw",
                "box",
                "--x",
                str(detection.x),
                "--y",
                str(detection.y),
                "--width",
                str(detection.width),
                "--height",
                str(detection.height),
                "--label",
                detection.label,
            ]
            if detection.confidence >= 0:
                command.extend(["--confidence", str(detection.confidence)])
            subprocess.run(command, check=True, stdout=subprocess.DEVNULL)


class VisionPipeline:
    def __init__(self, camera: TermuxCamera, processor: VisionProcessor,
                 renderer: OverlayRenderer | None, max_fps: float) -> None:
        self.camera = camera
        self.processor = processor
        self.renderer = renderer
        self.minimum_interval = 0 if max_fps <= 0 else 1 / max_fps

    def run(self) -> None:
        while True:
            started = time.monotonic()
            frame = self.camera.read(timeout=10)
            detections = list(self.processor.process(frame))
            if self.renderer is None:
                print(json.dumps([asdict(item) for item in detections]), flush=True)
            else:
                self.renderer.render(detections)
            remaining = self.minimum_interval - (time.monotonic() - started)
            if remaining > 0:
                time.sleep(remaining)


def parse_roi(value: str) -> tuple[int, int, int, int]:
    try:
        roi = tuple(int(item) for item in value.split(","))
    except ValueError as error:
        raise argparse.ArgumentTypeError("ROI must be x,y,width,height") from error
    if len(roi) != 4 or min(roi) < 0 or roi[2] < 1 or roi[3] < 1:
        raise argparse.ArgumentTypeError("ROI must be x,y,width,height")
    return roi


def main() -> None:
    parser = argparse.ArgumentParser(description="Termux Camera AI vision pipeline")
    parser.add_argument(
        "--engine", choices=("yolo", "onnx", "tflite", "mediapipe", "ocr", "tracking"),
        required=True,
    )
    parser.add_argument("--model", help="YOLO .pt, .onnx, or .tflite model")
    parser.add_argument("--roi", type=parse_roi, help="tracking ROI: x,y,width,height")
    parser.add_argument("--socket", default="termux.camera.frames")
    parser.add_argument("--max-fps", type=float, default=10)
    parser.add_argument("--no-overlay", action="store_true")
    arguments = parser.parse_args()

    if arguments.engine in ("yolo", "onnx", "tflite"):
        if not arguments.model:
            parser.error(f"--engine {arguments.engine} requires --model")
        processor: VisionProcessor = YoloProcessor(arguments.model)
    elif arguments.engine == "mediapipe":
        processor = MediaPipeFaceProcessor()
    elif arguments.engine == "ocr":
        processor = OcrProcessor()
    else:
        if arguments.roi is None:
            parser.error("--engine tracking requires --roi")
        processor = OpenCvTrackerProcessor(arguments.roi)

    renderer = None if arguments.no_overlay else OverlayRenderer()
    with TermuxCamera(arguments.socket) as camera:
        VisionPipeline(camera, processor, renderer, arguments.max_fps).run()


if __name__ == "__main__":
    main()
