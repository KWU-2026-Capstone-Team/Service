# 모델 연동과 정책

## 원본 코드와의 대응

기준: `AI-Generated-Media-Detection/deepfake_detector/{infer.py,xai.py}`.

| 원본 output | 서비스 저장/응답 | 의미 |
|---|---|---|
| `spatial` | `result.spatial` | 프레임별 sigmoid를 평균한 ConvNeXt 5개 앙상블 점수 |
| `temporal` | `result.temporal` | MC3-18 6개 평균 후 Platt 보정 |
| `fused` | `result.fused` | 두 점수 평균 |
| `verdict` | `result.policy.verdict` | 기존 임계값으로 계산 |
| `reason` | `result.policy.reason` | 기계 판독 가능한 임계값 원인 코드 |
| `n_faces` | `result.n_faces` | 사람 수가 아니라 사용한 얼굴 프레임 수 |
| `family` | `result.family` | 브랜치 반응 설명, 조작 기법 분류가 아님 |
| `faithfulness` | `result.faithfulness` | 전체 실험의 XAI 안내문, 해당 업로드의 신뢰도 아님 |
| `cam_spatial` | artifact `cam_spatial` | 대표 얼굴 한 장의 주목 영역 |
| `cam_temporal` | artifact `cam_temporal` | 시간축 max로 집약한 입 영역 주목 이미지 |
| `cam_*_err` | `result.warnings` | 공간/시간 XAI 생성 실패 코드. 판정은 유지 |
| `error` | 분석 `error` | 실패 코드/설명/추가 정보 |

추가 산출물: `spatial_original`, `temporal_original` PNG, 입력 영상 메타데이터, 실제 sample index/시간/bbox/검출 점수, 반복 패딩 수, 장치, 모델 및 보정 식별자. DB에는 이미지 URL 대신 내부 파일 경로를 저장하고 API에서 인증 경로를 생성합니다.

`model_version`은 모델 코드·모든 가중치·calibration 내용의 SHA-256 기반 식별자입니다. 원본의 웹 응답 반올림을 거치지 않고 원점수로 정책을 적용합니다.

## 신호등 v1 (시범 정책)

1. `fused >= 0.5 OR max(spatial, temporal) >= 0.85`: FAKE / RED.
2. 위 조건이 아니고 `max(spatial, temporal) >= 0.5`: REAL / YELLOW.
3. 나머지: REAL / GREEN.

노랑 임계값 0.5는 prototype의 (0.61,0.28) 사례를 충족하는 **서비스 초기 규칙**입니다. 데이터셋에서 검증된 최적값은 아닙니다. 학습·운영 검증셋으로 위양성/위음성 기준을 정한 뒤 새 `policy_version`으로 변경해야 합니다. 점수 ×100은 `signal_score`이며 실제 딥페이크 확률이나 confidence가 아닙니다.

`reason=FUSED_THRESHOLD`와 `primary_signal=SPATIAL`은 공존할 수 있습니다. 전자는 판정을 만든 임계값, 후자는 더 높은 브랜치입니다. 현재 RED 시안이 이 둘을 혼용하므로 frontend 라벨을 구분하세요.

## 분석 범위

최대 16개 얼굴 crop, 4개 미만은 `INSUFFICIENT_FACE_DATA`. 부족한 4~15개 입력은 마지막 crop을 복제해 temporal의 16개 입력을 맞춥니다. MTCNN 첫 bbox를 프레임마다 선택하며 동일 인물 추적은 하지 않습니다.

원본과 같은 `step=max(1,total_frames//32)` 후 얼굴 16개를 얻으면 중지하는 정책입니다. 항상 얼굴이 보이면 영상 후반을 검사하지 않을 수 있습니다. timestamp와 sample 목록을 제공하되 전체 구간 타임라인 탐지로 표현하면 안 됩니다. prototype GREEN의 18개 프레임 문구는 실제 모델 한도를 초과하므로 실제 UI는 반환값을 사용해야 합니다.

영상 자체는 최대 5분/18,000프레임/3840×2160 픽셀 수로 제한합니다. 오디오 분석·사람 식별·조작 기법 확정·구간별 위험 확률은 지원하지 않습니다. XAI는 법적 증거나 조작 마스크가 아니고, `faithfulness`의 0.52/0.71은 저장소에 기재된 실험 수치입니다.

정상 추론에는 torch/torchvision/timm/facenet-pytorch 등 모델 의존성이 필요합니다. 실제 모드와 demo 모드는 응답 `is_demo` 및 `model_version`으로 구분됩니다.
