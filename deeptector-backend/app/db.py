from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from .config import Settings


SCHEMA_VERSION = 1


@contextmanager
def connect(settings: Settings) -> Iterator[sqlite3.Connection]:
    settings.db_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(settings.db_path, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA busy_timeout = 30000")
    try:
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def initialize(settings: Settings) -> None:
    settings.storage_dir.mkdir(parents=True, exist_ok=True)
    migration = Path(__file__).resolve().parents[1] / "migrations" / "schema.sql"
    sql = migration.read_text(encoding="utf-8")
    with connect(settings) as connection:
        connection.executescript(sql)
        row = connection.execute("SELECT version FROM schema_version").fetchone()
        if row is None:
            connection.execute("INSERT INTO schema_version(version) VALUES (?)", (SCHEMA_VERSION,))
        elif row["version"] != SCHEMA_VERSION:
            raise RuntimeError(
                f"unsupported database schema version {row['version']}; expected {SCHEMA_VERSION}"
            )
