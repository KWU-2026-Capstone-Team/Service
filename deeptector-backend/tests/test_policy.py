import math

import pytest

from app.policy import classify


@pytest.mark.parametrize(
    "sp,tp,verdict,light",
    [
        (0.918, 0.528, "FAKE", "RED"),
        (0.61, 0.28, "REAL", "YELLOW"),
        (0.224, 0.148, "REAL", "GREEN"),
        (0.068, 0.929, "FAKE", "RED"),
        (0.85, 0, "FAKE", "RED"),
        (0.5, 0.5, "FAKE", "RED"),
        (0.49999, 0.49999, "REAL", "GREEN"),
        (0.5, 0, "REAL", "YELLOW"),
    ],
)
def test_boundaries(sp, tp, verdict, light):
    result = classify(sp, tp)
    assert result["verdict"] == verdict
    assert result["traffic_light"] == light


@pytest.mark.parametrize("score", [math.nan, math.inf, -0.01, 1.01])
def test_invalid_scores(score):
    with pytest.raises(ValueError):
        classify(score, 0.1)
