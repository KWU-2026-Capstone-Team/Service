"""Versioned presentation policy; thresholds are product heuristics, not calibration."""

import math

POLICY_VERSION = "traffic-v1-experimental"


def classify(spatial: float, temporal: float) -> dict:
    if not all(math.isfinite(v) and 0 <= v <= 1 for v in (spatial, temporal)):
        raise ValueError("Model scores must be finite and in [0, 1]")
    fused = (spatial + temporal) / 2
    fake = fused >= 0.5 or max(spatial, temporal) >= 0.85
    reason = (
        "FUSED_THRESHOLD"
        if fused >= 0.5
        else (
            "SPATIAL_THRESHOLD"
            if spatial >= 0.85
            else "TEMPORAL_THRESHOLD"
            if temporal >= 0.85
            else "BELOW_FAKE_THRESHOLDS"
        )
    )
    return {
        "verdict": "FAKE" if fake else "REAL",
        "traffic_light": "RED" if fake else "YELLOW" if max(spatial, temporal) >= 0.5 else "GREEN",
        "reason": reason,
        "primary_signal": "SPATIAL"
        if spatial > temporal
        else "TEMPORAL"
        if temporal > spatial
        else "BALANCED",
        "policy_version": POLICY_VERSION,
        "thresholds": {"fused_fake": 0.5, "branch_fake": 0.85, "branch_caution": 0.5},
        "signal_score": fused * 100,
        "score_interpretation": "Model signal, not a verified probability of forgery",
    }
