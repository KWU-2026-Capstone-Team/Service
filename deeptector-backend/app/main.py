from __future__ import annotations

import hashlib
import asyncio
import json
import re
import secrets
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, Request, UploadFile, status
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.openapi.utils import get_openapi

from .config import Settings
from .db import connect, initialize
from .schemas import (
    AnalysisDetail,
    AnalysisListResponse,
    AnalysisSummary,
    ArtifactResponse,
    ErrorResponse,
    ModelInfoResponse,
    SessionResponse,
)


ALLOWED_VIDEO_SUFFIXES = {".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v"}
TERMINAL_STATUSES = {"COMPLETED", "FAILED"}
UPLOAD_CHUNK_BYTES = 1024 * 1024


class UploadBodyLimitMiddleware:
    """Bound analysis request bodies before Starlette's multipart parser consumes them."""

    def __init__(self, app: Any, max_upload_bytes: int) -> None:
        self.app = app
        # Multipart headers and boundaries are not part of the uploaded file limit.
        self.max_body_bytes = max_upload_bytes + UPLOAD_CHUNK_BYTES

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if (
            scope.get("type") != "http"
            or scope.get("method") != "POST"
            or scope.get("path") != "/api/v1/analyses"
        ):
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        try:
            declared = int(headers.get(b"content-length", b"0"))
        except ValueError:
            declared = 0
        if declared > self.max_body_bytes:
            await self._reject(send)
            return
        received = 0

        async def limited_receive() -> dict[str, Any]:
            nonlocal received
            message = await receive()
            if message.get("type") == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_body_bytes:
                    raise _error("UPLOAD_TOO_LARGE", "Upload exceeds the configured limit", 413)
            return message

        await self.app(scope, limited_receive, send)

    @staticmethod
    async def _reject(send: Any) -> None:
        body = json.dumps(
            {
                "error": {
                    "code": "UPLOAD_TOO_LARGE",
                    "message": "Upload exceeds the configured limit",
                    "details": {},
                }
            }
        ).encode("utf-8")
        await send(
            {
                "type": "http.response.start",
                "status": status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode()),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})


class UploadAdmissionMiddleware:
    """Reject unauthenticated/full-queue requests before multipart spooling.

    A per-process bound also limits simultaneous temporary upload files.
    Run one API process or enforce a shared limit at the reverse proxy.
    """

    def __init__(self, app, settings):
        self.app, self.settings = app, settings
        self.slots = asyncio.Semaphore(settings.max_concurrent_uploads)

    async def __call__(self, scope, receive, send):
        if (
            scope.get("type") != "http"
            or scope.get("method") != "POST"
            or scope.get("path") != "/api/v1/analyses"
        ):
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers", []))
        auth = headers.get(b"authorization", b"").decode("latin1")
        code, message, http_status = None, None, 503
        with connect(self.settings) as db:
            session = db.execute(
                "SELECT id FROM sessions WHERE token_hash=? AND expires_at>?",
                (
                    _token_hash(auth[7:].strip()) if auth.lower().startswith("bearer ") else "",
                    iso_utc(utc_now()),
                ),
            ).fetchone()
            if session is None:
                code, message, http_status = "AUTH_REQUIRED", "A valid bearer session token is required", 401
            elif (
                db.execute(
                    "SELECT count(*) FROM analyses a JOIN sessions s ON s.id=a.session_id WHERE a.status IN ('QUEUED','PROCESSING') AND a.expires_at>? AND s.expires_at>?",
                    (iso_utc(utc_now()), iso_utc(utc_now())),
                ).fetchone()[0]
                >= self.settings.max_active_global
            ):
                code, message = "GLOBAL_QUEUE_LIMIT", "Analysis capacity reached; try again later"
        if code is None and self.slots.locked():
            code, message = "UPLOAD_CAPACITY", "Too many simultaneous uploads; try again later"
        if code:
            return await JSONResponse(
                status_code=http_status, content={"error": {"code": code, "message": message, "details": {}}}
            )(scope, receive, send)
        async with self.slots:
            await self.app(scope, receive, send)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _error(code: str, message: str, http_status: int, details: dict[str, Any] | None = None) -> HTTPException:
    return HTTPException(
        status_code=http_status,
        detail={"code": code, "message": message, "details": details or {}},
    )


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def get_session(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> str:
    # game.owner calls this dependency directly, outside FastAPI's parameter injection.
    if not isinstance(authorization, str):
        authorization = request.headers.get("authorization")
    if not authorization or not authorization.lower().startswith("bearer "):
        raise _error("AUTH_REQUIRED", "A bearer session token is required", status.HTTP_401_UNAUTHORIZED)
    token = authorization[7:].strip()
    if not token:
        raise _error("AUTH_REQUIRED", "A bearer session token is required", status.HTTP_401_UNAUTHORIZED)
    with connect(request.app.state.settings) as connection:
        row = connection.execute(
            "SELECT id, expires_at FROM sessions WHERE token_hash = ?",
            (_token_hash(token),),
        ).fetchone()
    if row is None or row["expires_at"] <= iso_utc(utc_now()):
        raise _error(
            "INVALID_SESSION", "The session token is invalid or expired", status.HTTP_401_UNAUTHORIZED
        )
    return str(row["id"])


def _clean_filename(filename: str | None) -> str:
    leaf = Path((filename or "video").replace("\\", "/")).name
    leaf = re.sub(r"[^A-Za-z0-9._ -]", "_", leaf).strip(" .")
    return leaf[:200] or "video"


def _decode_json(value: str | None) -> dict[str, Any] | None:
    return json.loads(value) if value else None


def _artifact_response(analysis_id: str, row: Any) -> ArtifactResponse:
    return ArtifactResponse(
        kind=row["kind"],
        mime_type=row["mime_type"],
        url=f"/api/v1/analyses/{analysis_id}/artifacts/{row['kind']}",
    )


def _summary(row: Any) -> AnalysisSummary:
    return AnalysisSummary(
        id=row["id"],
        filename=row["filename"],
        status=row["status"],
        stage=row["stage"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
        expires_at=row["expires_at"],
    )


def _owned_analysis(connection: Any, analysis_id: str, session_id: str) -> Any:
    row = connection.execute(
        "SELECT * FROM analyses WHERE id = ? AND session_id = ? AND expires_at > ?",
        (analysis_id, session_id, iso_utc(utc_now())),
    ).fetchone()
    if row is None:
        raise _error("ANALYSIS_NOT_FOUND", "Analysis not found", status.HTTP_404_NOT_FOUND)
    return row


def _safe_stored_path(settings: Settings, raw_path: str) -> Path:
    root = settings.storage_dir.resolve()
    path = Path(raw_path).resolve()
    if path != root and root not in path.parents:
        raise _error("ARTIFACT_UNAVAILABLE", "Artifact path is invalid", status.HTTP_404_NOT_FOUND)
    return path


def create_app(settings: Settings | None = None) -> FastAPI:
    configured = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        initialize(configured)
        from .game import initialize_game

        initialize_game(configured)
        yield

    application = FastAPI(title="Deeptector API", version="1.0.0", lifespan=lifespan)
    application.state.settings = configured
    application.state.model_loaded = False
    application.add_middleware(UploadBodyLimitMiddleware, max_upload_bytes=configured.max_upload_bytes)
    application.add_middleware(UploadAdmissionMiddleware, settings=configured)
    application.add_middleware(
        CORSMiddleware,
        allow_origins=list(configured.cors_origins),
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type"],
    )

    @application.exception_handler(HTTPException)
    async def http_error_handler(_: Request, exc: HTTPException) -> JSONResponse:
        if isinstance(exc.detail, dict) and {"code", "message"} <= exc.detail.keys():
            body = {**exc.detail, "details": exc.detail.get("details", {})}
        else:
            body = {"code": "HTTP_ERROR", "message": str(exc.detail), "details": {}}
        return JSONResponse(status_code=exc.status_code, content={"error": body}, headers=exc.headers)

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={
                "error": {
                    "code": "VALIDATION_ERROR",
                    "message": "Request validation failed",
                    "details": {"errors": exc.errors()},
                }
            },
        )

    @application.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "ok"}

    @application.get("/health/ready")
    async def ready() -> JSONResponse:
        checks = {"database": False, "storage": False}
        try:
            with connect(configured) as connection:
                connection.execute("SELECT 1").fetchone()
            checks["database"] = True
        except Exception:
            pass
        try:
            configured.storage_dir.mkdir(parents=True, exist_ok=True)
            checks["storage"] = configured.storage_dir.is_dir()
        except OSError:
            pass
        ready_status = all(checks.values())
        return JSONResponse(
            status_code=status.HTTP_200_OK if ready_status else status.HTTP_503_SERVICE_UNAVAILABLE,
            content={"status": "ready" if ready_status else "not_ready", "checks": checks},
        )

    @application.get("/api/v1/model-info", response_model=ModelInfoResponse)
    async def model_info() -> ModelInfoResponse:
        return ModelInfoResponse(
            inference_mode=configured.inference_mode,
            configured_model_dir=str(configured.model_dir),
            model_dir_exists=configured.model_dir.is_dir(),
            loaded=bool(application.state.model_loaded),
        )

    @application.post(
        "/api/v1/sessions",
        response_model=SessionResponse,
        status_code=status.HTTP_201_CREATED,
        responses={422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}},
    )
    async def create_session() -> SessionResponse:
        session_id = str(uuid.uuid4())
        token = secrets.token_urlsafe(32)
        now = utc_now()
        expires = now + timedelta(hours=configured.session_ttl_hours)
        with connect(configured) as connection:
            connection.execute("BEGIN IMMEDIATE")
            if (
                connection.execute(
                    "SELECT count(*) FROM sessions WHERE expires_at>?", (iso_utc(now),)
                ).fetchone()[0]
                >= configured.max_sessions
            ):
                raise _error("SESSION_CAPACITY", "Session capacity reached", 503)
            connection.execute(
                "INSERT INTO sessions(id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
                (session_id, _token_hash(token), iso_utc(now), iso_utc(expires)),
            )
        return SessionResponse(session_id=session_id, token=token, expires_at=iso_utc(expires))

    @application.post(
        "/api/v1/analyses",
        response_model=AnalysisDetail,
        status_code=status.HTTP_202_ACCEPTED,
        responses={
            503: {"model": ErrorResponse},
            400: {"model": ErrorResponse},
            401: {"model": ErrorResponse},
            413: {"model": ErrorResponse},
            429: {"model": ErrorResponse},
        },
    )
    async def queue_analysis(
        request: Request,
        video: Annotated[UploadFile, File(description="Video to analyze")],
        session_id: Annotated[str, Depends(get_session)],
    ) -> AnalysisDetail:
        filename = _clean_filename(video.filename)
        suffix = Path(filename).suffix.lower()
        if suffix not in ALLOWED_VIDEO_SUFFIXES:
            raise _error(
                "UNSUPPORTED_VIDEO_TYPE",
                "Unsupported video filename extension",
                status.HTTP_400_BAD_REQUEST,
                {"allowed_extensions": sorted(ALLOWED_VIDEO_SUFFIXES)},
            )
        content_length = request.headers.get("content-length")
        if content_length:
            try:
                if int(content_length) > configured.max_upload_bytes + UPLOAD_CHUNK_BYTES:
                    raise _error(
                        "UPLOAD_TOO_LARGE",
                        "Upload exceeds the configured limit",
                        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                    )
            except ValueError:
                pass
        with connect(configured) as connection:
            active = connection.execute(
                "SELECT COUNT(*) AS count FROM analyses WHERE session_id = ? AND status IN ('QUEUED', 'PROCESSING') AND expires_at>?",
                (session_id, iso_utc(utc_now())),
            ).fetchone()["count"]
        if active >= configured.max_queued_per_session:
            raise _error(
                "QUEUE_LIMIT_REACHED",
                "Too many active analyses for this session",
                status.HTTP_429_TOO_MANY_REQUESTS,
            )

        analysis_id = str(uuid.uuid4())
        source_dir = configured.storage_dir / "sources"
        source_dir.mkdir(parents=True, exist_ok=True)
        source_path = source_dir / f"{uuid.uuid4().hex}{suffix}"
        size = 0
        try:
            with source_path.open("xb") as target:
                while chunk := await video.read(UPLOAD_CHUNK_BYTES):
                    size += len(chunk)
                    if size > configured.max_upload_bytes:
                        raise _error(
                            "UPLOAD_TOO_LARGE",
                            "Upload exceeds the configured limit",
                            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        )
                    target.write(chunk)
        except Exception:
            source_path.unlink(missing_ok=True)
            raise
        finally:
            await video.close()
        if size == 0:
            source_path.unlink(missing_ok=True)
            raise _error("EMPTY_UPLOAD", "Uploaded video is empty", status.HTTP_400_BAD_REQUEST)

        now = utc_now()
        expires = now + timedelta(hours=configured.retention_hours)
        try:
            with connect(configured) as connection:
                connection.execute("BEGIN IMMEDIATE")
                active = connection.execute(
                    "SELECT count(*) FROM analyses WHERE session_id=? AND status IN ('QUEUED','PROCESSING') AND expires_at>?",
                    (session_id, iso_utc(now)),
                ).fetchone()[0]
                if active >= configured.max_queued_per_session:
                    raise _error("QUEUE_LIMIT_REACHED", "Too many active analyses for this session", 429)
                global_active = connection.execute(
                    "SELECT count(*) FROM analyses a JOIN sessions s ON s.id=a.session_id WHERE a.status IN ('QUEUED','PROCESSING') AND a.expires_at>? AND s.expires_at>?",
                    (iso_utc(now), iso_utc(now)),
                ).fetchone()[0]
                if global_active >= configured.max_active_global:
                    raise _error("GLOBAL_QUEUE_LIMIT", "Analysis capacity reached; try again later", 503)
                connection.execute(
                    """INSERT INTO analyses(
                           id, session_id, filename, source_path, status, stage,
                           created_at, updated_at, expires_at, result_json, error_json
                       ) VALUES (?, ?, ?, ?, 'QUEUED', 'QUEUED', ?, ?, ?, NULL, NULL)""",
                    (
                        analysis_id,
                        session_id,
                        filename,
                        str(source_path),
                        iso_utc(now),
                        iso_utc(now),
                        iso_utc(expires),
                    ),
                )
                row = connection.execute("SELECT * FROM analyses WHERE id = ?", (analysis_id,)).fetchone()
        except Exception:
            source_path.unlink(missing_ok=True)
            raise
        return AnalysisDetail(**_summary(row).model_dump())

    @application.get("/api/v1/analyses", response_model=AnalysisListResponse)
    async def list_analyses(
        session_id: Annotated[str, Depends(get_session)],
        limit: Annotated[int, Query(ge=1, le=100)] = 50,
        offset: Annotated[int, Query(ge=0)] = 0,
    ) -> AnalysisListResponse:
        with connect(configured) as connection:
            rows = connection.execute(
                """SELECT * FROM analyses
                   WHERE session_id = ? AND expires_at > ?
                   ORDER BY created_at DESC LIMIT ? OFFSET ?""",
                (session_id, iso_utc(utc_now()), limit, offset),
            ).fetchall()
        return AnalysisListResponse(items=[_summary(row) for row in rows])

    @application.get("/api/v1/analyses/{analysis_id}", response_model=AnalysisDetail)
    async def get_analysis(
        analysis_id: str, session_id: Annotated[str, Depends(get_session)]
    ) -> AnalysisDetail:
        with connect(configured) as connection:
            row = _owned_analysis(connection, analysis_id, session_id)
            artifacts = connection.execute(
                "SELECT kind, mime_type FROM artifacts WHERE analysis_id = ? ORDER BY kind",
                (analysis_id,),
            ).fetchall()
        return AnalysisDetail(
            **_summary(row).model_dump(),
            result=_decode_json(row["result_json"]),
            error=_decode_json(row["error_json"]),
            artifacts=[_artifact_response(analysis_id, artifact) for artifact in artifacts],
        )

    @application.delete("/api/v1/analyses/{analysis_id}", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_analysis(analysis_id: str, session_id: Annotated[str, Depends(get_session)]) -> Response:
        with connect(configured) as connection:
            row = _owned_analysis(connection, analysis_id, session_id)
            if row["status"] not in TERMINAL_STATUSES:
                raise _error(
                    "ANALYSIS_NOT_TERMINAL", "Only terminal analyses can be deleted", status.HTTP_409_CONFLICT
                )
            artifact_rows = connection.execute(
                "SELECT path FROM artifacts WHERE analysis_id = ?", (analysis_id,)
            ).fetchall()
            paths = [row["source_path"], *(artifact["path"] for artifact in artifact_rows)]
            connection.execute("DELETE FROM analyses WHERE id = ?", (analysis_id,))
        for raw_path in paths:
            try:
                _safe_stored_path(configured, raw_path).unlink(missing_ok=True)
            except (OSError, HTTPException):
                pass
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @application.get("/api/v1/analyses/{analysis_id}/artifacts/{kind}")
    async def get_artifact(
        analysis_id: str,
        kind: str,
        session_id: Annotated[str, Depends(get_session)],
    ) -> FileResponse:
        with connect(configured) as connection:
            _owned_analysis(connection, analysis_id, session_id)
            row = connection.execute(
                "SELECT path, mime_type FROM artifacts WHERE analysis_id = ? AND kind = ?",
                (analysis_id, kind),
            ).fetchone()
        if row is None:
            raise _error("ARTIFACT_NOT_FOUND", "Artifact not found", status.HTTP_404_NOT_FOUND)
        path = _safe_stored_path(configured, row["path"])
        if not path.is_file():
            raise _error("ARTIFACT_UNAVAILABLE", "Artifact file is unavailable", status.HTTP_404_NOT_FOUND)
        return FileResponse(
            path,
            media_type=row["mime_type"],
            filename=f"{kind}{path.suffix}",
            headers={"Cache-Control": "private, no-store"},
        )

    from .game import router as game_router

    application.include_router(game_router)

    def openapi_contract():
        if application.openapi_schema:
            return application.openapi_schema
        schema = get_openapi(title=application.title, version=application.version, routes=application.routes)
        schema.setdefault("components", {}).setdefault("securitySchemes", {})["SessionBearer"] = {
            "type": "http",
            "scheme": "bearer",
            "description": "POST /api/v1/sessions response token",
        }
        for path, operations in schema["paths"].items():
            if path.startswith(("/api/v1/analyses", "/api/v1/games")):
                for operation in operations.values():
                    if isinstance(operation, dict):
                        operation["security"] = [{"SessionBearer": []}]
                        operation["parameters"] = [
                            p for p in operation.get("parameters", []) if p.get("name") != "authorization"
                        ]
        application.openapi_schema = schema
        return schema

    application.openapi = openapi_contract
    return application


app = create_app()
