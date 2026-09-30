"""Adapter to the existing read-only model checkout. Heavy imports live in child process."""

import argparse
import base64
import hashlib
import json
import logging
import math
import subprocess
import sys
import tempfile
from pathlib import Path


class AnalysisError(Exception):
    def __init__(self, code, message, details=None):
        super().__init__(message)
        self.code, self.message, self.details = code, message, details or {}


def probe(path):
    import cv2

    cap = cv2.VideoCapture(str(path))
    try:
        if not cap.isOpened():
            raise AnalysisError("INVALID_VIDEO", "영상을 디코딩할 수 없습니다.")
        fps = float(cap.get(cv2.CAP_PROP_FPS))
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        width, height = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        if not math.isfinite(fps) or fps <= 0 or total <= 0 or width <= 0 or height <= 0:
            raise AnalysisError("INVALID_VIDEO", "영상 메타데이터가 유효하지 않습니다.")
        duration = total / fps
        if duration > 300 or total > 18000 or width * height > 3840 * 2160:
            raise AnalysisError("VIDEO_LIMIT_EXCEEDED", "최대 5분, 18,000프레임, 4K 픽셀 수까지 지원합니다.")
        ok, _ = cap.read()
        if not ok:
            raise AnalysisError("INVALID_VIDEO", "첫 프레임을 읽을 수 없습니다.")
        file_hash = hashlib.sha256()
        with Path(path).open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                file_hash.update(chunk)
        return {
            "fps": fps,
            "frame_count": total,
            "width": width,
            "height": height,
            "duration_seconds": duration,
            "size_bytes": Path(path).stat().st_size,
            "sha256": file_hash.hexdigest(),
        }
    finally:
        cap.release()


def real_analysis(path, model_dir):
    import cv2
    import torch
    from PIL import Image
    from facenet_pytorch import MTCNN

    model_dir = Path(model_dir).resolve()
    sys.path.insert(0, str(model_dir))
    import infer
    import xai

    spatial = sorted((model_dir / "models/spatial").glob("*.pt"))
    temporal = sorted((model_dir / "models/temporal").glob("*.pt"))
    calibration = model_dir / "models/temporal/calibration.json"
    if len(spatial) != 5 or len(temporal) != 6 or not calibration.is_file():
        raise AnalysisError("MODEL_UNAVAILABLE", "모델 5+6개와 calibration.json이 필요합니다.")
    digest = hashlib.sha256()
    for file in [*spatial, *temporal, calibration, model_dir / "infer.py", model_dir / "xai.py"]:
        digest.update(file.name.encode())
        with file.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    metadata = probe(path)
    mt = MTCNN(keep_all=False, device="cpu")
    cap = cv2.VideoCapture(str(path))
    faces, samples = [], []
    step = max(1, metadata["frame_count"] // 32)
    try:
        for fi in range(metadata["frame_count"]):
            ok, frame = cap.read()
            if not ok:
                break
            if fi % step:
                continue
            im = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            boxes, probabilities = mt.detect(im)
            if boxes is not None and len(boxes):
                x1, y1, x2, y2 = map(float, boxes[0])
                pad = 0.1 * (y2 - y1)
                box = (max(0, x1), max(0, y1 - pad), min(im.width, x2), min(im.height, y2 + pad))
                faces.append(im.crop(box).resize((224, 224)))
                samples.append(
                    {
                        "frame_index": fi,
                        "timestamp_seconds": fi / metadata["fps"],
                        "bbox": list(box),
                        "detection_confidence": float(probabilities[0]),
                    }
                )
                if len(faces) == infer.T:
                    break
    finally:
        cap.release()
    if len(faces) < 4:
        raise AnalysisError(
            "INSUFFICIENT_FACE_DATA", "얼굴 프레임이 부족합니다.", {"detected": len(faces), "required": 4}
        )
    dev = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    conv, temp = infer.load_models(dev)
    sp, tp, fused = infer.predict(faces, conv, temp, dev)
    explanation = xai.explain(faces, conv, temp, dev, sp, tp)
    artifacts, warnings = {}, []
    for kind in ("cam_spatial", "cam_temporal"):
        if kind in explanation:
            artifacts[kind] = explanation[kind].split(",", 1)[1]
        else:
            warnings.append(f"{kind.upper()}_UNAVAILABLE")
    # Preserve original representative crops alongside attention overlays.
    import io

    for kind, im in (
        ("spatial_original", faces[len(faces) // 2]),
        ("temporal_original", infer.mouth((faces + [faces[-1]] * (16 - len(faces)))[8])),
    ):
        out = io.BytesIO()
        im.save(out, format="PNG")
        artifacts[kind] = base64.b64encode(out.getvalue()).decode()
    metadata.update(
        {
            "samples": samples,
            "sampling_strategy": "legacy-first-16-at-total-div-32",
            "padded_frames": 16 - len(faces),
            "device": str(dev),
            "adapter_version": "adapter-v1",
            "calibration": json.loads(calibration.read_text(encoding="utf-8")),
            "coverage_note": "Sampled face crops; not exhaustive video or identity tracking",
        }
    )
    return {
        "spatial": sp,
        "temporal": tp,
        "fused": fused,
        "n_faces": len(faces),
        "family": explanation["family"],
        "faithfulness": explanation["faithfulness"],
        "metadata": metadata,
        "model_version": "dual-branch-" + digest.hexdigest()[:16],
        "calibration_version": hashlib.sha256(calibration.read_bytes()).hexdigest()[:16],
        "artifacts": artifacts,
        "warnings": warnings,
        "is_demo": False,
    }


class ModelAdapter:
    def __init__(self, config):
        self.config = config

    def analyze(self, path: Path, progress):
        progress("VALIDATING_VIDEO")
        if self.config.inference_mode == "demo":
            metadata = probe(path)
            progress("DEMO_INFERENCE")
            return {
                "spatial": 0.918,
                "temporal": 0.528,
                "fused": 0.723,
                "n_faces": 16,
                "family": "공간 분석 우세 (시연용 고정값)",
                "faithfulness": "시연 모드: 실제 모델 또는 얼굴 검출을 실행하지 않았습니다.",
                "metadata": metadata,
                "model_version": "demo-fixture-v1",
                "calibration_version": "not-applied-demo",
                "artifacts": {},
                "warnings": ["DEMO_NOT_REAL_INFERENCE"],
                "is_demo": True,
            }
        progress("MODEL_INFERENCE_AND_XAI")
        with tempfile.TemporaryDirectory(prefix="deeptector-inference-") as directory:
            output = Path(directory) / "result.json"
            try:
                process = subprocess.run(
                    [
                        sys.executable,
                        "-m",
                        "app.inference",
                        "--input",
                        str(path),
                        "--output",
                        str(output),
                        "--model-dir",
                        str(self.config.model_dir),
                    ],
                    capture_output=True,
                    timeout=self.config.job_timeout_seconds,
                    cwd=Path(__file__).resolve().parent.parent,
                )
            except subprocess.TimeoutExpired as exc:
                raise AnalysisError("ANALYSIS_TIMEOUT", "분석 제한 시간을 초과했습니다.") from exc
            if not output.exists():
                logging.getLogger(__name__).error(
                    "Inference exited %s: %s",
                    process.returncode,
                    process.stderr.decode(errors="replace")[-4000:],
                )
                raise AnalysisError(
                    "INFERENCE_FAILED",
                    "모델 프로세스가 결과를 생성하지 못했습니다.",
                    {"exit_code": process.returncode},
                )
            result = json.loads(output.read_text(encoding="utf-8"))
            if "error" in result:
                logging.getLogger(__name__).error(
                    "Inference subprocess error: %s", process.stderr.decode(errors="replace")[-4000:]
                )
                err = result["error"]
                raise AnalysisError(err["code"], err["message"], err.get("details"))
            result["artifacts"] = {
                k: base64.b64decode(v, validate=True) for k, v in result["artifacts"].items()
            }
            return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--model-dir", required=True)
    args = parser.parse_args()
    try:
        result = real_analysis(args.input, args.model_dir)
    except AnalysisError as exc:
        result = {"error": {"code": exc.code, "message": exc.message, "details": exc.details}}
    except ImportError:
        result = {
            "error": {
                "code": "MODEL_DEPENDENCIES_MISSING",
                "message": "실제 모델 실행 의존성을 설치해 주세요.",
            }
        }
    except BaseException:
        import traceback

        traceback.print_exc()
        result = {
            "error": {
                "code": "INFERENCE_FAILED",
                "message": "모델 실행에 실패했습니다. worker 로그를 확인하세요.",
            }
        }
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
