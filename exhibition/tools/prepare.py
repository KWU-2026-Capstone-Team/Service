"""Validate vendored runtime and seed only public/team game data; never copy sessions."""
import argparse
import hashlib
import json
import math
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def model_version():
    model = ROOT / 'model'
    spatial = sorted((model / 'models/spatial').glob('*.pt'))
    temporal = sorted((model / 'models/temporal').glob('*.pt'))
    if len(spatial) != 5 or len(temporal) != 6:
        raise RuntimeError('Exactly 5 spatial and 6 temporal model weights are required')
    files = [*spatial, *temporal, model / 'models/temporal/calibration.json', model / 'infer.py', model / 'xai.py']
    value = hashlib.sha256()
    for file in files:
        value.update(file.name.encode())
        with file.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                value.update(chunk)
    return 'dual-branch-' + value.hexdigest()[:16]


def runtime_check():
    import sys
    import torch
    import torchvision
    import timm
    import cv2
    import facenet_pytorch
    import fastapi
    import uvicorn
    from facenet_pytorch import MTCNN
    if sys.version_info[:2] != (3, 12) or torch.__version__.split('+')[0] != '2.2.2':
        raise RuntimeError('Unexpected Python/PyTorch version')
    # Face-detector weights must come from the vendored package, not a network cache.
    MTCNN(keep_all=False, device='cpu')
    print(f'Runtime OK: Python {sys.version.split()[0]}, torch {torch.__version__}, CPU, OpenCV {cv2.__version__}', flush=True)


def prepare():
    from app.config import Settings
    from app.db import connect, initialize
    from app.game import import_clips, initialize_game
    from app.import_deepgame import load_reference

    version = model_version()
    settings = Settings.from_env()
    expected_data = (ROOT / 'runtime-data').resolve()
    if settings.db_path.resolve() != expected_data / 'deeptector.sqlite3':
        raise RuntimeError('Refusing to seed a database outside this package')
    clips = load_reference(ROOT / 'game-reference')
    seed = json.loads((ROOT / 'game-evaluations.json').read_text(encoding='utf-8'))
    if seed['model_version'] != version:
        raise RuntimeError('Game AI scores do not match the bundled model')
    by_id = {clip['id']: clip for clip in clips}
    if len(seed['evaluations']) != len(clips) or {row['clip_id'] for row in seed['evaluations']} != set(by_id):
        raise RuntimeError('Incomplete/duplicate game evaluation seed')
    for row in seed['evaluations']:
        if digest(Path(by_id[row['clip_id']]['video_path'])) != row['video_sha256']:
            raise RuntimeError('Game clip hash mismatch: ' + row['clip_id'])
        for field in ('spatial', 'temporal'):
            if not math.isfinite(row[field]) or not 0 <= row[field] <= 1:
                raise RuntimeError('Invalid evaluation score')
    initialize(settings)
    initialize_game(settings)
    import_clips(settings, clips)
    with connect(settings) as db:
        db.executemany('''INSERT INTO game_clip_evaluations(clip_id,model_version,spatial,temporal,evaluated_at)
            VALUES(?,?,?,?,?) ON CONFLICT(clip_id,model_version) DO NOTHING''',
            [(r['clip_id'], version, r['spatial'], r['temporal'], r['evaluated_at']) for r in seed['evaluations']])
        count = db.execute('SELECT count(*) FROM game_clips WHERE enabled=1').fetchone()[0]
    print(f'Prepared {count} game clips and 24 previously measured real AI evaluations; model={version}', flush=True)


def verify_all():
    manifest = json.loads((ROOT / 'package-manifest.json').read_text(encoding='utf-8'))
    for row in manifest['files']:
        file = (ROOT / row['path']).resolve()
        if not file.is_relative_to(ROOT.resolve()) or not file.is_file() or file.stat().st_size != row['bytes'] or digest(file) != row['sha256']:
            raise RuntimeError('Package damaged: ' + row['path'])
    print(f'All {len(manifest["files"])} package checksums verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--verify-all', action='store_true')
    args = parser.parse_args()
    if args.verify_all:
        verify_all()
    else:
        runtime_check()
        if args.check_only:
            print('Model integrity: ' + model_version())
        else:
            prepare()
