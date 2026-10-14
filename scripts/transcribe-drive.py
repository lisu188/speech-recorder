#!/usr/bin/env python3
"""Private, resumable Google Drive transcription using local faster-whisper.

Only Google OAuth/Drive receive network requests from this program. Model weights
are downloaded by faster-whisper if they are not in the local model cache. Audio,
OAuth material, checkpoints and reports must live outside the Git checkout.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import email.utils
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import signal
import sys
import tempfile
import threading
import time
from typing import Any, Iterator

DRIVE_API = "https://www.googleapis.com/drive/v3/files"
UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
FOLDER_MIME = "application/vnd.google-apps.folder"
DOC_MIME = "application/vnd.google-apps.document"
FILE_FIELDS = "id,name,mimeType,parents,size,md5Checksum,modifiedTime,appProperties,trashed"
FULL_RE = re.compile(r"^speech_[a-zA-Z0-9_-]+\.wav$", re.IGNORECASE)
LIVE_RE = re.compile(r"^__sr_live_(speech_[a-zA-Z0-9_-]+)_part\d{4}\.wav$", re.IGNORECASE)
STAMP_RE = re.compile(r"\[(\d{2,}:\d{2}:\d{2}(?:[.,]\d{1,3})?)\s*(?:-->|[-–])\s*(\d{2,}:\d{2}:\d{2}(?:[.,]\d{1,3})?)\]\s*(\S.*)")
EMPTY_RE = re.compile(
    r"(?:no\s+(?:recogniz(?:ed|able)|detect(?:ed|able))?\s*speech(?:\s+(?:was\s+)?(?:detected|recognized|found))?"
    r"|no\s+(?:audio|transcript(?:ion)?|words)\s+(?:detected|available|found)"
    r"|(?:brak|nie\s+(?:wykryto|rozpoznano))\s+(?:(?:wykrytej|rozpoznanej|żadnej|zadnej|czytelnej|zrozumiałej|wyraźnej|wyraznej)\s+)?mowy"
    r"|brak\s+(?:(?:czytelnych|rozpoznanych|zrozumiałych|wyraźnych|wyraznych)\s+)?wypowiedzi"
    r"|brak\s+(?:(?:czytelnej|zrozumiałej)\s+)?transkrypcji|transkrypcja\s+(?:jest\s+)?pusta|unable\s+to\s+transcribe|speech\s+(?:not|could\s+not\s+be)\s+(?:detected|recognized))",
    re.IGNORECASE,
)
HEADER_RE = re.compile(r"^(?:nagranie|recording|język|jezyk|language|czas trwania|duration|model|status|źródło|zrodlo|source|generated|utworzono|transkrypcja|transcription)\s*:", re.IGNORECASE)
STOP = threading.Event()


@contextlib.contextmanager
def target_lock(recording: dict[str, Any]) -> Iterator[None]:
    """Serialize destination names across local processes using different states."""
    import fcntl
    directory = Path(tempfile.gettempdir()) / f"speech-recorder-drive-locks-{os.getuid()}"
    directory.mkdir(mode=0o700, exist_ok=True)
    identity = json.dumps([sorted(recording.get("parents", [])), expected_transcript_name(recording["name"]).casefold()])
    path = directory / (hashlib.sha256(identity.encode()).hexdigest() + ".lock")
    with path.open("a+") as handle:
        os.chmod(path, 0o600)
        while True:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if STOP.wait(1):
                    raise TranscriptionError("Interrupted while waiting for the transcript destination lock")
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class TranscriptionError(RuntimeError):
    """Expected failure with an explanation safe to put in a progress report."""


class DriveError(TranscriptionError):
    def __init__(self, status: int, reason: str = "") -> None:
        self.status = status
        self.reason = reason
        super().__init__(f"Drive HTTP {status}" + (f" ({reason})" if reason else ""))


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def is_recording_name(name: str) -> bool:
    return bool(FULL_RE.fullmatch(name))


def recording_basename(name: str) -> str:
    if not (FULL_RE.fullmatch(name) or LIVE_RE.fullmatch(name)):
        raise ValueError("Not a Speech Recorder WAV filename")
    return name[:-4]


def expected_transcript_name(name: str) -> str:
    return recording_basename(name) + ".transkrypcja.txt"


def recording_key(name: str) -> str | None:
    match = LIVE_RE.fullmatch(name)
    if match:
        return match.group(1).lower()
    return name[:-4].lower() if FULL_RE.fullmatch(name) else None


def _parents(file: dict[str, Any]) -> set[str]:
    if file.get("_recording_parent"):
        return {str(file["_recording_parent"])}
    return set(file.get("parents", []))


def select_recordings(files: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    wavs = [f for f in files if not f.get("trashed") and f.get("mimeType") not in (DOC_MIME, FOLDER_MIME)
            and not f.get("mimeType", "").startswith("text/")]
    full = [f for f in wavs if is_recording_name(f.get("name", ""))]
    live = [f for f in wavs if LIVE_RE.fullmatch(f.get("name", ""))]
    full_keys = {(parent, recording_key(f["name"])) for f in full for parent in _parents(f)}
    ignored, orphan = [], []
    for file in live:
        paired = any((parent, recording_key(file["name"])) in full_keys for parent in _parents(file))
        (ignored if paired else orphan).append(file)
    return {"recordings": sorted(full + orphan, key=lambda f: (f["name"], f["id"])),
            "full": full, "ignored_live": ignored, "orphan_live": orphan}


def source_fingerprint(file: dict[str, Any]) -> str:
    identity = {key: str(file.get(key, "")) for key in ("id", "name", "size", "md5Checksum", "modifiedTime")}
    identity["trashed"] = bool(file.get("trashed", False))
    identity["parents"] = sorted(file.get("parents", []))
    return hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()


def classify_transcript(text: str) -> str:
    """Inspect content, including legacy Descript exports; never trust a title alone."""
    text = text.lstrip("\ufeff").strip()
    if not text:
        return "empty"
    if re.search(r"^status\s*:\s*(?:error|failed|partial|pending|processing|błąd|niekompletna|częściow[ao]|czesciow[ao])", text, re.I | re.M):
        return "uncertain"
    body = [line.strip() for line in text.splitlines() if line.strip() and not HEADER_RE.match(line.strip())]
    if not body:
        return "empty"
    # An absence marker in a header does not suppress real timestamped speech.
    speech = [match.group(3) for line in body if (match := STAMP_RE.search(line))]
    if speech:
        return "complete"
    # Legacy exports may put the no-speech result in Status and follow it with
    # explanatory prose. That prose is not recognized speech.
    if any(EMPTY_RE.match(match.group(1)) for match in re.finditer(r"^status\s*:\s*(.+)$", text, re.I | re.M)):
        return "empty"
    useful = speech or body
    useful = [line for line in useful if not EMPTY_RE.match(line) and not re.fullmatch(r"[\W_]+", line)]
    if not useful:
        return "empty"
    if re.search(r"(?:transcription failed|authentication|processing error|transkrypcja nieudana|błąd transkrypcji)", text, re.I):
        return "uncertain"
    # Legacy plain-text documents can contain valid speech without our headers.
    return "complete" if len(re.findall(r"[^\W\d_]+", " ".join(useful), re.UNICODE)) >= 3 else "uncertain"


def matching_transcripts(recording: dict[str, Any], files: list[dict[str, Any]]) -> list[dict[str, Any]]:
    stem = recording_basename(recording["name"]).casefold()
    names = {stem, stem + ".wav", stem + ".txt", stem + ".transkrypcja", stem + ".transkrypcja.txt",
             stem + ".transcription", stem + ".transcription.txt", stem + " transkrypcja",
             stem + " transcription", stem + ".transcript", stem + ".transcript.txt",
             stem + ".wav.transkrypcja", stem + ".wav.transcription",
             stem + ".wav.transkrypcja.txt", stem + ".wav.txt"}
    matches = []
    for file in files:
        if file.get("trashed") or file.get("mimeType") == FOLDER_MIME or file["id"] == recording["id"]:
            continue
        if not (set(file.get("parents", [])) & set(recording.get("parents", []))):
            continue
        mime = file.get("mimeType", "")
        if mime.startswith("audio/") or ((is_recording_name(file.get("name", "")) or LIVE_RE.fullmatch(file.get("name", ""))) and not (mime == DOC_MIME or mime.startswith("text/"))):
            continue
        props = file.get("appProperties", {})
        if props.get("srArchived") == "1":
            continue
        if props.get("srSourceId"):
            if props["srSourceId"] == recording["id"]:
                matches.append(file)
        elif file.get("name", "").casefold() in names:
            matches.append(file)
    return matches


def select_existing(recording: dict[str, Any], candidates: list[dict[str, Any]],
                    content_by_id: dict[str, str], retry_empty: bool = False) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = {"complete": [], "empty": [], "uncertain": []}
    for file in matching_transcripts(recording, candidates):
        props = file.get("appProperties", {})
        status = classify_transcript(content_by_id.get(file["id"], "")) if file["id"] in content_by_id else "uncertain"
        if props.get("srSourceFingerprint") and props["srSourceFingerprint"] != source_fingerprint(recording):
            status = "uncertain"
        if props.get("srStatus") == "partial":
            status = "uncertain"
        groups[status].append(file)
    decision = {"action": "process", "reason": "missing", **groups}
    if groups["complete"]:
        decision.update(action="skip", reason="complete")
    elif groups["empty"] and not retry_empty:
        decision.update(action="skip", reason="retry_empty_required")
    else:
        exact = [f for f in sum(groups.values(), []) if f["name"] == expected_transcript_name(recording["name"])]
        if len(exact) > 1:
            decision.update(action="conflict", reason="duplicate_target_names")
        elif exact and exact[0] in groups["uncertain"] and not exact[0].get("appProperties", {}).get("srSourceId"):
            decision.update(action="conflict", reason="uncertain_target_preserved")
        elif groups["empty"]:
            decision["reason"] = "retry_empty"
        elif groups["uncertain"]:
            decision["reason"] = "legacy_document_preserved"
    return decision


def atomic_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(prefix="." + path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            os.fchmod(out.fileno(), 0o600)
            json.dump(data, out, ensure_ascii=False, indent=2, allow_nan=False)
            out.write("\n")
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
        with contextlib.suppress(OSError):
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(name)


class CheckpointStore:
    """Atomic file-ID keyed state with a process lock; corrupt state fails closed."""
    def __init__(self, state_dir: str | Path) -> None:
        self.directory = Path(state_dir)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.directory / "checkpoint.json"
        self._mutex = threading.RLock()
        self._lock_file: Any = None
        self.data: dict[str, Any] = {"version": 1, "files": {}}
        self.load()

    def load(self) -> dict[str, Any]:
        with self._mutex:
            if self.path.exists():
                try:
                    data = json.loads(self.path.read_text(encoding="utf-8"))
                    if data.get("version") != 1 or not isinstance(data.get("files"), dict):
                        raise ValueError("Unsupported checkpoint format")
                    self.data = data
                except (ValueError, TypeError, OSError) as exc:
                    raise TranscriptionError("Checkpoint cannot be read; preserve it and choose a different state directory") from exc
            return self.data

    def acquire(self) -> None:
        import fcntl
        self._lock_file = (self.directory / "run.lock").open("a+")
        os.chmod(self.directory / "run.lock", 0o600)
        try:
            fcntl.flock(self._lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.load()  # State might have changed between construction and lock acquisition.
        except BlockingIOError as exc:
            self._lock_file.close()
            self._lock_file = None
            raise TranscriptionError("Another process is using this state directory") from exc

    def release(self) -> None:
        if self._lock_file:
            self._lock_file.close()
            self._lock_file = None

    def get(self, file_id: str) -> dict[str, Any] | None:
        with self._mutex:
            record = self.data["files"].get(file_id)
            return json.loads(json.dumps(record)) if record is not None else None

    def set(self, file_id: str, record: dict[str, Any]) -> None:
        with self._mutex:
            previous = self.data["files"].get(file_id)
            # Workers keep adding nested chunk dictionaries after set(). The
            # snapshot must not share those mutable objects with the writer.
            self.data["files"][file_id] = json.loads(json.dumps(dict(record, updated_at=utc_now()), allow_nan=False))
            try:
                self.save()
            except BaseException:
                if previous is None:
                    self.data["files"].pop(file_id, None)
                else:
                    self.data["files"][file_id] = previous
                raise

    def save(self) -> None:
        with self._mutex:
            atomic_json(self.path, self.data)

    def __enter__(self) -> "CheckpointStore":
        self.acquire()
        return self

    def __exit__(self, *args: Any) -> None:
        self.release()


def path_outside_checkout(path: Path, checkout: Path) -> Path:
    resolved = path.expanduser().resolve()
    if resolved == checkout or checkout in resolved.parents:
        raise TranscriptionError(f"Private setup/data paths must be outside the repository: {path}")
    return resolved


def _safe_error(exc: BaseException) -> str:
    if isinstance(exc, (TranscriptionError, ValueError)):
        return str(exc)[:500]
    # Third-party errors can contain request URLs, client secrets or arbitrary data.
    return type(exc).__name__ + "; consult private local runtime logs or retry after fixing the prerequisite"


def create_session(args: argparse.Namespace) -> Any:
    try:
        from google.oauth2.credentials import Credentials
        from google.auth.transport.requests import AuthorizedSession, Request
    except ImportError as exc:
        raise TranscriptionError("Install scripts/requirements-transcription.txt in an external virtual environment") from exc
    env_keys = ("GDRIVE_OAUTH_CLIENT_ID", "GDRIVE_OAUTH_CLIENT_SECRET", "GDRIVE_OAUTH_REFRESH_TOKEN")
    supplied = [bool(os.environ.get(key)) for key in env_keys]
    if all(supplied):
        credentials = Credentials(None, refresh_token=os.environ[env_keys[2]],
                                  token_uri="https://oauth2.googleapis.com/token",
                                  client_id=os.environ[env_keys[0]], client_secret=os.environ[env_keys[1]],
                                  scopes=[DRIVE_SCOPE])
    elif args.token.exists():
        try:
            credentials = Credentials.from_authorized_user_file(str(args.token), scopes=[DRIVE_SCOPE])
        except (ValueError, OSError) as exc:
            raise TranscriptionError("OAuth token JSON is unusable; configure an authorized-user OAuth token outside the repository") from exc
    elif args.authorize:
        if not args.credentials.exists():
            raise TranscriptionError("OAuth desktop client JSON is missing; use --credentials outside the repository")
        try:
            from google_auth_oauthlib.flow import InstalledAppFlow
            flow = InstalledAppFlow.from_client_secrets_file(str(args.credentials), scopes=[DRIVE_SCOPE])
            credentials = flow.run_local_server(host="127.0.0.1", port=args.oauth_port, open_browser=True,
                                               authorization_prompt_message="Open the displayed Google OAuth authorization URL in your browser: {url}",
                                               success_message="Authorization completed. You can close this tab.",
                                               access_type="offline", prompt="consent")
        except Exception as exc:
            raise TranscriptionError("OAuth authorization failed; use a desktop loopback authorization locally, then configure its token securely") from exc
        atomic_json(args.token, json.loads(credentials.to_json()))
    else:
        suffix = " (the three GDRIVE_OAUTH_* variables are only partially configured)" if any(supplied) else ""
        raise TranscriptionError("Google Drive OAuth is unavailable. Configure GDRIVE_OAUTH_CLIENT_ID, GDRIVE_OAUTH_CLIENT_SECRET and GDRIVE_OAUTH_REFRESH_TOKEN, or provide --token authorized-user JSON" + suffix)
    try:
        if not credentials.valid:
            if not credentials.refresh_token:
                raise TranscriptionError("OAuth token has no refresh token; authorize with offline access")
            credentials.refresh(Request())
        if not all(supplied):
            atomic_json(args.token, json.loads(credentials.to_json()))
    except TranscriptionError:
        raise
    except Exception as exc:
        raise TranscriptionError("Google OAuth refresh failed; check the secure OAuth bindings, consent scopes, revocation and oauth2.googleapis.com access") from exc
    return AuthorizedSession(credentials)


class DriveClient:
    def __init__(self, session: Any, retries: int = 7) -> None:
        self.session = session
        self.retries = retries
        self._mutex = threading.RLock()

    @staticmethod
    def _reason(response: Any) -> str:
        try:
            errors = response.json().get("error", {}).get("errors", [])
            return errors[0].get("reason", "") if errors else ""
        except (ValueError, AttributeError, IndexError):
            return ""

    def request(self, method: str, url: str, **kwargs: Any) -> Any:
        import requests
        for attempt in range(self.retries + 1):
            if STOP.is_set():
                raise TranscriptionError("Interrupted; checkpoint and local copies were preserved")
            response = None
            try:
                with self._mutex:
                    response = self.session.request(method, url, timeout=(30, 180), **kwargs)
                if 200 <= response.status_code < 300:
                    return response
                reason = self._reason(response)
                retryable = response.status_code in (408, 429, 500, 502, 503, 504) or (
                    response.status_code == 403 and reason in ("rateLimitExceeded", "userRateLimitExceeded", "backendError"))
                if not retryable or attempt >= self.retries:
                    response.close()
                    raise DriveError(response.status_code, reason)
            except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as exc:
                if attempt >= self.retries:
                    raise TranscriptionError("Drive network retry limit reached") from exc
            delay = min(60.0, (2 ** attempt) + random.random())
            if response is not None:
                retry_after = response.headers.get("Retry-After", "")
                with contextlib.suppress(ValueError, TypeError):
                    if retry_after.isdigit():
                        delay = min(300.0, max(delay, float(retry_after)))
                    elif retry_after:
                        parsed = email.utils.parsedate_to_datetime(retry_after)
                        delay = min(300.0, max(delay, parsed.timestamp() - time.time()))
                response.close()
            # Interruptible waits never hide progress for more than a minute.
            print(f"Drive limit/network retry {attempt + 1}/{self.retries}; waiting {delay:.1f}s", file=sys.stderr, flush=True)
            if STOP.wait(delay):
                raise TranscriptionError("Interrupted; checkpoint and local copies were preserved")
        raise TranscriptionError("Drive request did not complete")

    def list_files(self, query: str) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        token = None
        seen_tokens: set[str] = set()
        while True:
            params: dict[str, Any] = {"q": query, "pageSize": 1000,
                                      "fields": f"nextPageToken,incompleteSearch,files({FILE_FIELDS})",
                                      "supportsAllDrives": "true", "includeItemsFromAllDrives": "true"}
            if token:
                params["pageToken"] = token
            response = self.request("GET", DRIVE_API, params=params)
            try:
                payload = response.json()
            finally:
                response.close()
            if payload.get("incompleteSearch"):
                raise TranscriptionError("Drive returned an incomplete search; specify --folder-id to avoid claiming a complete inventory")
            result.extend(payload.get("files", []))
            token = payload.get("nextPageToken")
            if not token:
                return result
            if token in seen_tokens:
                raise TranscriptionError("Drive pagination repeated a page token")
            seen_tokens.add(token)

    def get_file(self, file_id: str) -> dict[str, Any]:
        response = self.request("GET", f"{DRIVE_API}/{file_id}",
                                params={"fields": FILE_FIELDS, "supportsAllDrives": "true"})
        try:
            metadata = response.json()
            if response.headers.get("ETag"):
                metadata["_etag"] = response.headers["ETag"]
            return metadata
        finally:
            response.close()

    def contents(self, folder_id: str) -> list[dict[str, Any]]:
        return self.list_files(f"'{escape_query(folder_id)}' in parents and trashed = false")

    def read_text(self, file: dict[str, Any]) -> str:
        if file.get("mimeType") == DOC_MIME:
            response = self.request("GET", f"{DRIVE_API}/{file['id']}/export", params={"mimeType": "text/plain"})
        elif file.get("mimeType", "").startswith("text/") or file.get("name", "").lower().endswith(".txt"):
            if int(file.get("size", 0)) > 32 * 1024 * 1024:
                raise TranscriptionError("Existing transcript is too large to validate automatically")
            response = self.request("GET", f"{DRIVE_API}/{file['id']}", params={"alt": "media", "supportsAllDrives": "true"})
        else:
            raise TranscriptionError("Existing transcript format cannot be safely inspected as text")
        try:
            if len(response.content) > 32 * 1024 * 1024:
                raise TranscriptionError("Existing transcript is too large to validate automatically")
            return response.content.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise TranscriptionError("Existing transcript is not UTF-8; preserving it") from exc
        finally:
            response.close()

    def download(self, file: dict[str, Any], path: Path) -> None:
        expected_size = int(file.get("size", 0))
        expected_md5 = file.get("md5Checksum")
        if path.exists() and expected_size and path.stat().st_size == expected_size:
            if expected_md5 and file_md5(path) == expected_md5:
                return
        import requests
        part = path.with_suffix(path.suffix + ".part")
        for attempt in range(self.retries + 1):
            digest = hashlib.md5()
            count = 0
            response = self.request("GET", f"{DRIVE_API}/{file['id']}",
                                    params={"alt": "media", "supportsAllDrives": "true"}, stream=True)
            try:
                with part.open("wb") as out:
                    os.chmod(part, 0o600)
                    for block in response.iter_content(chunk_size=1024 * 1024):
                        if STOP.is_set():
                            raise TranscriptionError("Interrupted during download; local partial copy preserved")
                        if block:
                            out.write(block)
                            digest.update(block)
                            count += len(block)
                    out.flush()
                    os.fsync(out.fileno())
                if expected_size and count != expected_size:
                    raise TranscriptionError("Downloaded WAV size does not match Drive metadata")
                if expected_md5 and digest.hexdigest() != expected_md5:
                    raise TranscriptionError("Downloaded WAV MD5 does not match Drive metadata")
                os.replace(part, path)
                return
            except (requests.exceptions.RequestException, TranscriptionError):
                if attempt >= self.retries or STOP.is_set():
                    raise
                print(f"Retrying verified WAV download ({attempt + 1}/{self.retries})", file=sys.stderr, flush=True)
                if STOP.wait(min(60, 2 ** attempt)):
                    raise TranscriptionError("Interrupted during download; local partial copy preserved")
            finally:
                response.close()

    def generate_id(self) -> str:
        response = self.request("GET", DRIVE_API + "/generateIds", params={"count": 1, "space": "drive", "type": "files"})
        try:
            return response.json()["ids"][0]
        finally:
            response.close()

    def archive_existing(self, file: dict[str, Any]) -> dict[str, Any]:
        """Copy before any update. Existing backups are revalidated, never overwritten."""
        before = self.get_file(file["id"])
        text_before = self.read_text(before)
        content_hash = hashlib.sha256(text_before.encode("utf-8")).hexdigest()
        backup_name = before["name"] + ".backup-" + content_hash[:16] + ".txt"
        parent = before.get("parents", [None])[0]
        if not parent:
            raise TranscriptionError("Cannot archive a transcript without its parent folder")
        matching = [f for f in self.contents(parent) if f["name"] == backup_name]
        for backup in matching:
            if self.read_text(backup) == text_before:
                return backup
        if matching:
            raise TranscriptionError("An existing backup name contains different content; preserving both documents")
        metadata = {"name": backup_name, "parents": [parent], "appProperties": {"srArchived": "1", "srBackupOf": before["id"], "srBackupHash": content_hash}}
        # Copy POST is not inherently idempotent. Avoid retries on ambiguous errors;
        # next run finds and verifies the deterministic backup name.
        try:
            response = self.session.request("POST", f"{DRIVE_API}/{before['id']}/copy",
                                            params={"fields": FILE_FIELDS, "supportsAllDrives": "true"},
                                            json=metadata, timeout=(30, 180))
        except Exception as exc:
            raise TranscriptionError("Archive copy outcome is uncertain; rerun to verify the backup before any update") from exc
        try:
            if not 200 <= response.status_code < 300:
                raise DriveError(response.status_code, self._reason(response))
            backup = response.json()
        finally:
            response.close()
        if self.read_text(backup) != text_before:
            raise TranscriptionError("Drive transcript backup did not verify; original preserved")
        return backup

    def verify_uploaded(self, file_id: str, text: str, recording: dict[str, Any]) -> dict[str, Any]:
        fresh_source = self.get_file(recording["id"])
        if fresh_source.get("trashed") or source_fingerprint(fresh_source) != source_fingerprint(recording):
            raise TranscriptionError("Original WAV changed before upload verification; local WAV preserved")
        meta = self.get_file(file_id)
        if meta.get("trashed") or meta["name"] != expected_transcript_name(recording["name"]):
            raise TranscriptionError("Uploaded transcript name or trash state did not verify")
        if not (set(meta.get("parents", [])) & set(recording.get("parents", []))):
            raise TranscriptionError("Uploaded transcript is not in the recording's folder")
        props = meta.get("appProperties", {})
        if props.get("srSourceId") != recording["id"] or props.get("srSourceFingerprint") != source_fingerprint(recording):
            raise TranscriptionError("Uploaded transcript source provenance did not verify")
        if self.read_text(meta) != text:
            raise TranscriptionError("Uploaded transcript content did not verify; local WAV preserved")
        return meta

    def publish_text(self, recording: dict[str, Any], text: str, existing: list[dict[str, Any]],
                     checkpoint: CheckpointStore) -> dict[str, Any]:
        with target_lock(recording):
            return self._publish_text_locked(recording, text, checkpoint)

    def _publish_text_locked(self, recording: dict[str, Any], text: str, checkpoint: CheckpointStore) -> dict[str, Any]:
        parent = recording.get("parents", [None])[0]
        if not parent:
            raise TranscriptionError("Recording has no accessible parent folder")
        fresh_source = self.get_file(recording["id"])
        if source_fingerprint(fresh_source) != source_fingerprint(recording):
            raise TranscriptionError("Original WAV changed during processing; transcript not published")
        # Re-list immediately before upload: recover uploads whose checkpoint write
        # was interrupted, and notice documents created since the initial inventory.
        fresh = self.contents(parent)
        same_source_names = [f for f in fresh if f.get("name", "").casefold() == recording["name"].casefold()
                             and (is_recording_name(f["name"]) or LIVE_RE.fullmatch(f["name"]))
                             and f.get("mimeType") != DOC_MIME and not f.get("mimeType", "").startswith("text/")]
        if len(same_source_names) > 1:
            raise TranscriptionError("Several original WAVs share this name and folder; preserve them and resolve the output-name ambiguity")
        matches = matching_transcripts(recording, fresh)
        exact = [f for f in fresh if f["name"] == expected_transcript_name(recording["name"])]
        if len(exact) > 1:
            raise TranscriptionError("Duplicate transcript target names; preserving them for manual resolution")
        for candidate in matches:
            props = candidate.get("appProperties", {})
            if props.get("srSourceFingerprint") == source_fingerprint(recording) and self.read_text(candidate) == text:
                return self.verify_uploaded(candidate["id"], text, recording)
        target = exact[0] if exact else None
        if target and target.get("mimeType") == DOC_MIME:
            props = target.get("appProperties", {})
            if props.get("srSourceId") and props["srSourceId"] != recording["id"]:
                raise TranscriptionError("Canonical document belongs to another recording; preserving it")
            old_text = self.read_text(target)
            if classify_transcript(old_text) != "empty":
                raise TranscriptionError("Nonempty canonical Google document preserved; manual review required")
            self.archive_existing(target)
            checked = self.get_file(target["id"])
            if source_fingerprint(checked) != source_fingerprint(target) or self.read_text(checked) != old_text:
                raise TranscriptionError("Google document changed during archival; preserving its newest content")
            if not checked.get("_etag"):
                raise TranscriptionError("Drive did not provide an ETag for safe Google document archival; document preserved")
            # Preserve the existing document's ID, body and permissions, while
            # freeing the canonical TXT name. A crash after this rename is safe:
            # the archived document is excluded from active matching on resume.
            archived_name = target["name"] + ".legacy-" + target["id"]
            response = self.request("PATCH", f"{DRIVE_API}/{target['id']}",
                                    params={"fields": FILE_FIELDS, "supportsAllDrives": "true"},
                                    headers={"If-Match": checked["_etag"]},
                                    json={"name": archived_name, "appProperties": dict(props, srArchived="1")})
            response.close()
            archived = self.get_file(target["id"])
            if (archived.get("name") != archived_name or archived.get("appProperties", {}).get("srArchived") != "1"
                    or set(archived.get("parents", [])) != set(target.get("parents", []))
                    or self.read_text(archived) != old_text):
                raise TranscriptionError("Google document archival did not verify; local audio preserved")
            target = None
        if target:
            if target.get("mimeType") != "text/plain":
                raise TranscriptionError("Target name belongs to a non-TXT document; preserving it for manual resolution")
            props = target.get("appProperties", {})
            if props.get("srSourceId") and props["srSourceId"] != recording["id"]:
                raise TranscriptionError("Target name belongs to a different Drive recording; preserving it")
            target_text = self.read_text(target)
            if classify_transcript(target_text) == "complete" and props.get("srSourceFingerprint", source_fingerprint(recording)) == source_fingerprint(recording):
                raise TranscriptionError("A complete transcript appeared during processing; preserving it, rerun to skip")
            if classify_transcript(target_text) == "uncertain" and not props.get("srSourceId"):
                raise TranscriptionError("Uncertain existing transcript target preserved; manual review required")
            self.archive_existing(target)
            # Detect an edit between copying and overwriting, including same-size edits.
            rechecked = self.get_file(target["id"])
            if source_fingerprint(rechecked) != source_fingerprint(target) or self.read_text(rechecked) != target_text:
                raise TranscriptionError("Existing transcript changed during archival; preserving its newest content")
            if not rechecked.get("_etag"):
                raise TranscriptionError("Drive did not provide an ETag for safe conditional transcript replacement; existing document preserved")
            file_id = target["id"]
            method, url = "PATCH", f"{UPLOAD_API}/{file_id}"
        else:
            record = checkpoint.get(recording["id"]) or {}
            file_id = record.get("upload_id")
            if file_id:
                try:
                    return self.verify_uploaded(file_id, text, recording)
                except DriveError as exc:
                    if exc.status != 404:
                        raise
            else:
                file_id = self.generate_id()
                record.update(upload_id=file_id)
                checkpoint.set(recording["id"], record)
            method, url = "POST", UPLOAD_API
        properties = {"srSourceId": recording["id"], "srSourceFingerprint": source_fingerprint(recording),
                      "srStatus": "empty" if classify_transcript(text) == "empty" else "complete", "srEngine": "faster-whisper"}
        metadata: dict[str, Any] = {"name": expected_transcript_name(recording["name"]), "mimeType": "text/plain", "appProperties": properties}
        if not target:
            metadata.update(id=file_id, parents=[parent])
        boundary = "speech_recorder_" + hashlib.sha256((file_id + text).encode()).hexdigest()[:24]
        body = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                json.dumps(metadata, ensure_ascii=False) + f"\r\n--{boundary}\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" +
                text + f"\r\n--{boundary}--\r\n").encode("utf-8")
        headers = {"Content-Type": f"multipart/related; boundary={boundary}"}
        if target:
            headers["If-Match"] = rechecked["_etag"]
        if source_fingerprint(self.get_file(recording["id"])) != source_fingerprint(recording):
            raise TranscriptionError("Original WAV changed immediately before upload; local WAV preserved")
        try:
            response = self.request(method, url, params={"uploadType": "multipart", "fields": FILE_FIELDS, "supportsAllDrives": "true"},
                                    headers=headers, data=body)
            response.close()
        except DriveError as exc:
            if not (method == "POST" and exc.status == 409):
                raise
            # A timed-out create may have succeeded; its reserved ID is reusable
            # only after exact source, parent and content verification.
        return self.verify_uploaded(file_id, text, recording)


def escape_query(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def file_md5(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def discover_folder(drive: DriveClient, folder_id: str | None) -> dict[str, Any]:
    if folder_id:
        folder = drive.get_file(folder_id)
        if folder.get("mimeType") != FOLDER_MIME or folder.get("trashed"):
            raise TranscriptionError("--folder-id is not an accessible, active Drive folder")
        return folder
    folders = drive.list_files(f"name = 'SpeechRecorder' and mimeType = '{FOLDER_MIME}' and trashed = false")
    occupied = []
    for folder in folders:
        files = drive.list_files(f"'{escape_query(folder['id'])}' in parents and trashed = false and (name contains 'speech_' or name = '__sr_live')")
        if any(is_recording_name(f.get("name", "")) or f.get("name") == "__sr_live" for f in files):
            occupied.append(folder)
    if len(occupied) == 1:
        return occupied[0]
    if len(occupied) > 1:
        raise TranscriptionError("Several SpeechRecorder folders contain audio; use --folder-id to choose the intended folder")
    # Older/manual exports may have renamed the folder. Search names, not audio.
    files = drive.list_files("trashed = false and name contains 'speech_' and mimeType != 'application/vnd.google-apps.folder'")
    parents = {parent for f in files if is_recording_name(f.get("name", "")) for parent in f.get("parents", [])}
    if len(parents) == 1:
        return drive.get_file(parents.pop())
    if len(parents) > 1:
        raise TranscriptionError("Speech Recorder WAVs occur in several folders; use --folder-id to choose the intended folder")
    if len(folders) == 1:
        return folders[0]
    raise TranscriptionError("No accessible Speech Recorder folder was found; verify the OAuth account/scopes or provide --folder-id")


def inventory(drive: DriveClient, folder: dict[str, Any]) -> dict[str, Any]:
    files = drive.contents(folder["id"])
    for child in list(files):
        if child.get("mimeType") == FOLDER_MIME and child.get("name") == "__sr_live":
            for live in drive.contents(child["id"]):
                live["_recording_parent"] = folder["id"]
                files.append(live)
    selection = select_recordings(files)
    destination_ids: dict[tuple[str, str], list[str]] = {}
    for recording in selection["recordings"]:
        for parent in recording.get("parents", []):
            destination_ids.setdefault((parent, expected_transcript_name(recording["name"]).casefold()), []).append(recording["id"])
    ambiguous_source_ids = {file_id for ids in destination_ids.values() if len(ids) > 1 for file_id in ids}
    transcripts = {f["id"]: f for recording in selection["recordings"] for f in matching_transcripts(recording, files)}
    return {"folder": folder, "files": files, **selection, "transcripts": list(transcripts.values()),
            "ambiguous_source_ids": ambiguous_source_ids,
            "counts": {"original_full_wav": len(selection["full"]), "orphan_live_wav": len(selection["orphan_live"]),
                       "ignored_live_wav": len(selection["ignored_live"]), "transcript_documents": len(transcripts),
                       "txt_transcripts": sum(f["name"].lower().endswith(".transkrypcja.txt") for f in transcripts.values())}}


def timestamp(seconds: float) -> str:
    millis = max(0, round(seconds * 1000))
    hours, rest = divmod(millis, 3600000)
    minutes, rest = divmod(rest, 60000)
    secs, millis = divmod(rest, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}.{millis:03d}"


format_timestamp = timestamp


def iter_audio_chunks(path: Path, chunk_seconds: int) -> Iterator[tuple[int, float, Any]]:
    """Decode incrementally with two-second context across chunk boundaries."""
    import av
    import numpy as np
    chunk_samples = chunk_seconds * 16000
    parts: list[Any] = []
    samples = 0
    index = 0
    consumed_samples = 0
    previous_tail: Any = None
    resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
    with av.open(str(path), mode="r") as container:
        if not container.streams.audio:
            raise TranscriptionError("WAV contains no audio stream")
        stream = container.streams.audio[0]

        def append_frame(frame: Any) -> Iterator[tuple[int, float, Any]]:
            nonlocal parts, samples, index, consumed_samples, previous_tail
            value = frame.to_ndarray().reshape(-1).astype(np.float32) / 32768.0
            start = 0
            while start < len(value):
                take = min(chunk_samples - samples, len(value) - start)
                parts.append(value[start:start + take])
                samples += take
                start += take
                if samples == chunk_samples:
                    raw = np.concatenate(parts)
                    context_length = len(previous_tail) if previous_tail is not None else 0
                    value_with_context = np.concatenate([previous_tail, raw]) if previous_tail is not None else raw
                    yield index, (consumed_samples - context_length) / 16000, value_with_context
                    consumed_samples += len(raw)
                    previous_tail = raw[-32000:].copy()
                    index += 1
                    parts, samples = [], 0

        for frame in container.decode(stream):
            for converted in resampler.resample(frame):
                yield from append_frame(converted)
        for converted in resampler.resample(None):
            yield from append_frame(converted)
        if samples:
            raw = np.concatenate(parts)
            context_length = len(previous_tail) if previous_tail is not None else 0
            yield index, (consumed_samples - context_length) / 16000, np.concatenate([previous_tail, raw]) if previous_tail is not None else raw


def merge_chunk_segments(previous: list[dict[str, Any]], current: list[dict[str, Any]],
                         seam: float | None) -> list[dict[str, Any]]:
    """Pick words on each side of the overlap midpoint, preserving spoken text."""
    if seam is None:
        return previous + current
    if not current or not previous:
        return previous + current
    if min(segment["start"] for segment in current) >= max(segment["end"] for segment in previous):
        return previous + current
    if not any(segment["end"] > seam for segment in current):
        return previous

    def trim(segment: dict[str, Any], left: bool) -> dict[str, Any] | None:
        if left and segment["end"] <= seam or not left and segment["start"] >= seam:
            return segment
        if left and segment["start"] >= seam or not left and segment["end"] <= seam:
            return None
        words = [word for word in segment.get("words", [])
                 if ((word["start"] + word["end"]) / 2 < seam) == left]
        if not words and segment.get("words"):
            return None
        if not words:
            # Trusted older checkpoint formats may lack word timestamps. Keep
            # the whole segment only on the side containing its midpoint.
            return segment if ((segment["start"] + segment["end"]) / 2 < seam) == left else None
        result = dict(segment)
        result.update(start=words[0]["start"], end=words[-1]["end"],
                      text="".join(word["text"] for word in words).strip(), words=words)
        return result if result["text"] else None

    return [trimmed for segment in previous if (trimmed := trim(segment, True))] + [
        trimmed for segment in current if (trimmed := trim(segment, False))]


def available_memory() -> int:
    available = 0
    with contextlib.suppress(OSError, ValueError):
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                available = int(line.split()[1]) * 1024
    for root, limit_name, used_name in (("/sys/fs/cgroup", "memory.max", "memory.current"),
                                       ("/sys/fs/cgroup/memory", "memory.limit_in_bytes", "memory.usage_in_bytes")):
        with contextlib.suppress(OSError, ValueError):
            limit = int((Path(root) / limit_name).read_text())
            used = int((Path(root) / used_name).read_text())
            if limit < 1 << 60:
                available = min(available, max(0, limit - used)) if available else max(0, limit - used)
    return available


def device_config(requested: str, model: str, workers: int) -> tuple[str, str, int]:
    try:
        import ctranslate2
        gpu_count = ctranslate2.get_cuda_device_count()
    except (ImportError, RuntimeError):
        gpu_count = 0
    device = "cuda" if requested == "auto" and gpu_count else ("cpu" if requested == "auto" else requested)
    if device == "cuda" and not gpu_count:
        raise TranscriptionError("CUDA was requested but no usable CUDA device is available")
    memory = available_memory()
    required = 5 * 1024 ** 3 if "large" in model else 2 * 1024 ** 3
    if memory and memory < required:
        raise TranscriptionError(f"Insufficient available RAM for {model}: {memory / 1024**3:.1f} GiB; choose --model medium/small or increase memory")
    # One inference stream is deliberately the safe default; model instances are
    # shared across workers, but each inference still needs additional workspace.
    effective = 1
    if device == "cpu" and memory:
        effective = max(1, min(workers, int(memory // required), (os.cpu_count() or 1) // 2 or 1))
    # CUDA concurrency is one until memory can be established from nvidia-smi.
    if device == "cuda":
        import subprocess
        with contextlib.suppress(OSError, subprocess.SubprocessError, ValueError):
            output = subprocess.check_output(["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"], text=True, timeout=10)
            free_vram = int(output.splitlines()[0]) * 1024 ** 2
            effective = max(1, min(workers, int(free_vram // (6 * 1024 ** 3)), int(memory // required) if memory else 1))
    return device, "float16" if device == "cuda" else "int8", effective


class LocalTranscriber:
    def __init__(self, args: argparse.Namespace) -> None:
        try:
            from faster_whisper import WhisperModel
        except ImportError as exc:
            raise TranscriptionError("faster-whisper is unavailable; install scripts/requirements-transcription.txt") from exc
        self.args = args
        self.device, self.compute_type, self.workers = device_config(args.device, args.model, args.workers)
        print(f"Local model: {args.model}; device={self.device}; compute={self.compute_type}; workers={self.workers}; chunk={args.chunk_seconds}s", flush=True)
        config = {"cpu_threads": max(1, min(4, (os.cpu_count() or 1) // self.workers)),
                  "num_workers": self.workers, "download_root": str(args.model_cache), "local_files_only": args.offline_model}
        try:
            self.model = WhisperModel(args.model, device=self.device, compute_type=self.compute_type, **config)
        except (RuntimeError, ValueError) as exc:
            if args.device != "auto" or self.device != "cuda":
                raise
            print("CUDA initialization failed; falling back to verified local CPU inference", file=sys.stderr, flush=True)
            self.device, self.compute_type, self.workers = device_config("cpu", args.model, args.workers)
            config["num_workers"] = self.workers
            self.model = WhisperModel(args.model, device=self.device, compute_type=self.compute_type, **config)

    def transcribe(self, recording: dict[str, Any], audio: Path, checkpoint: CheckpointStore) -> dict[str, Any]:
        fingerprint = source_fingerprint(recording)
        record = checkpoint.get(recording["id"]) or {}
        settings = {"model": self.args.model, "language": self.args.language, "chunk_seconds": self.args.chunk_seconds,
                    "engine_version": 3, "vad": "silero-500-250", "overlap_seconds": 2}
        settings_hash = hashlib.sha256(json.dumps(settings, sort_keys=True).encode()).hexdigest()
        if record.get("fingerprint") != fingerprint or record.get("settings_hash") != settings_hash:
            record = {"fingerprint": fingerprint, "settings_hash": settings_hash, "settings": settings,
                      "chunks": {}, "name": recording["name"],
                      **({"upload_id": record["upload_id"]} if record.get("upload_id") else {})}
        if not self.args.resume:
            record["chunks"] = {}
        elif self.args.retry_empty:
            record["chunks"] = {key: chunk for key, chunk in record.get("chunks", {}).items() if chunk.get("segments")}
        record["status"] = "processing"
        checkpoint.set(recording["id"], record)
        all_segments: list[dict[str, Any]] = []
        languages: dict[str, float] = {}
        rejected = 0
        duration = 0.0
        for index, offset, chunk in iter_audio_chunks(audio, self.args.chunk_seconds):
            if STOP.is_set():
                raise TranscriptionError("Interrupted; completed chunk checkpoints and local WAV preserved")
            duration = offset + len(chunk) / 16000
            key = str(index)
            cached = record["chunks"].get(key)
            if cached:
                if abs(cached["duration"] - len(chunk) / 16000) > 0.002:
                    raise TranscriptionError("Chunk length differs from checkpoint; use a fresh state directory")
                accepted = cached["segments"]
                language = cached["language"]
                rejected += cached.get("rejected", 0)
            else:
                iterator, info = self.model.transcribe(
                    chunk, language=self.args.language, task="transcribe", beam_size=5, best_of=5,
                    temperature=0.0, condition_on_previous_text=False, word_timestamps=True,
                    vad_filter=True, vad_parameters={"threshold": 0.6, "min_speech_duration_ms": 250,
                                                     "min_silence_duration_ms": 500, "speech_pad_ms": 250},
                    no_speech_threshold=0.6, log_prob_threshold=-1.0, compression_ratio_threshold=2.4,
                    hallucination_silence_threshold=2.0,
                )
                accepted = []
                chunk_rejected = 0
                language = info.language
                for segment in iterator:
                    text = segment.text.strip()
                    words = getattr(segment, "words", None) or []
                    probabilities = [word.probability for word in words if getattr(word, "probability", None) is not None]
                    low_words = probabilities and sum(probabilities) / len(probabilities) < 0.35
                    # faster-whisper already combines no_speech_threshold with
                    # log_prob_threshold. High no-speech probability alone must
                    # not discard speech the decoder recognized confidently.
                    if not text or segment.avg_logprob < -1.0 or segment.compression_ratio > 2.4 or low_words:
                        chunk_rejected += bool(text)
                        continue
                    start, end = offset + segment.start, min(duration, offset + segment.end)
                    if end <= start or not math.isfinite(start + end):
                        chunk_rejected += 1
                        continue
                    accepted.append({"start": round(start, 3), "end": round(end, 3), "text": text})
                    aligned_words = [{"start": round(offset + word.start, 3), "end": round(min(duration, offset + word.end), 3),
                                      "text": word.word} for word in words if hasattr(word, "start") and hasattr(word, "end") and hasattr(word, "word")]
                    if aligned_words:
                        accepted[-1]["words"] = aligned_words
                rejected += chunk_rejected
                cached = {"duration": len(chunk) / 16000, "segments": accepted, "language": language,
                          "rejected": chunk_rejected, "completed_at": utc_now()}
                record["chunks"][key] = cached
                checkpoint.set(recording["id"], record)
            overlap = max(0.0, index * self.args.chunk_seconds - offset)
            all_segments = merge_chunk_segments(all_segments, accepted, offset + overlap / 2 if overlap else None)
            if accepted:
                languages[language] = languages.get(language, 0) + sum(s["end"] - s["start"] for s in accepted)
            print(f"{recording['name']}: chunk {index + 1}, {timestamp(duration)}, accepted={len(accepted)}, rejected={cached.get('rejected', 0)}", flush=True)
        if duration <= 0:
            raise TranscriptionError("WAV has zero decoded duration")
        language = max(languages, key=languages.get) if languages else self.args.language or "und"
        result = {"segments": all_segments, "language": language, "languages": sorted(languages),
                  "duration": round(duration, 3), "rejected_segments": rejected,
                  "status": "success" if all_segments else "empty"}
        record.update(status="transcribed", result=result)
        checkpoint.set(recording["id"], record)
        return result


def render_transcript(recording: dict[str, Any], result: dict[str, Any], model: str) -> str:
    languages = result.get("languages", [])
    language = result["language"]
    if len(languages) > 1:
        language += " (detected per chunk: " + ", ".join(languages) + ")"
    lines = [f"Nagranie: {recording['name']}", f"Język: {language}",
             f"Czas trwania: {timestamp(result['duration'])}", f"Model: faster-whisper {model}",
             "Status: " + ("complete" if result["segments"] else "empty"),
             f"Źródło: Google Drive file ID {recording['id']}",
             "", "Transkrypcja:"]
    if result["segments"]:
        lines.extend(f"[{timestamp(s['start'])} --> {timestamp(s['end'])}] {s['text']}" for s in result["segments"])
    else:
        lines.append("Brak rozpoznanej mowy. Wymaga sprawdzenia lub ponownego przetworzenia; nie jest uznawane za udaną transkrypcję.")
    return "\n".join(lines) + "\n"


def finish_local_success(audio: Path) -> None:
    """Called only after remote source/parent/provenance/content verification."""
    with contextlib.suppress(FileNotFoundError):
        audio.unlink()
    with contextlib.suppress(FileNotFoundError):
        audio.with_suffix(audio.suffix + ".part").unlink()


def inspect_existing(drive: DriveClient, recording: dict[str, Any], files: list[dict[str, Any]],
                     retry_empty: bool) -> dict[str, Any]:
    candidates = matching_transcripts(recording, files)
    content: dict[str, str] = {}
    issues: dict[str, str] = {}
    for candidate in candidates:
        try:
            content[candidate["id"]] = drive.read_text(candidate)
        except Exception as exc:
            issues[candidate["id"]] = _safe_error(exc)
    decision = select_existing(recording, candidates, content, retry_empty)
    if issues and decision["action"] != "skip":
        decision.update(action="conflict", reason="existing_document_unreadable", content_errors=issues)
    return decision


def process_one(drive: DriveClient, transcriber: LocalTranscriber, recording: dict[str, Any],
                args: argparse.Namespace, checkpoint: CheckpointStore) -> dict[str, Any]:
    item: dict[str, Any] = {"id": recording["id"], "name": recording["name"], "status": "error"}
    safe_id = hashlib.sha256(recording["id"].encode()).hexdigest()
    audio_dir = args.state_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    audio = audio_dir / (safe_id + ".wav")
    try:
        # One file may change after inventory; refuse to reuse old metadata/audio.
        fresh = drive.get_file(recording["id"])
        if source_fingerprint(fresh) != source_fingerprint(recording):
            raise TranscriptionError("Original WAV changed after inventory; rerun to process its current version")
        prior = checkpoint.get(recording["id"]) or {}
        if prior.get("fingerprint") and prior["fingerprint"] != source_fingerprint(recording) and audio.exists():
            preserved = audio.with_name(safe_id + ".previous-" + prior["fingerprint"][:16] + ".wav")
            if preserved.exists():
                raise TranscriptionError("Changed recording has an existing preserved local copy; choose a new state directory")
            audio.rename(preserved)
        drive.download(recording, audio)
        result = transcriber.transcribe(recording, audio, checkpoint)
        text = render_transcript(recording, result, args.model)
        output_dir = args.state_dir / "transcripts"
        output_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        output = output_dir / (safe_id + ".txt")
        with output.open("w", encoding="utf-8") as handle:
            os.chmod(output, 0o600)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        uploaded = drive.publish_text(recording, text, [], checkpoint)
        record = checkpoint.get(recording["id"]) or {}
        record.update(status=result["status"], transcript_id=uploaded["id"], fingerprint=source_fingerprint(recording),
                      transcript_sha256=hashlib.sha256(text.encode()).hexdigest(), local_audio=None)
        checkpoint.set(recording["id"], record)
        finish_local_success(audio)
        item.update(status=result["status"], transcript_id=uploaded["id"], language=result["language"],
                    duration=result["duration"], rejected_segments=result["rejected_segments"],
                    requires_retry=result["status"] == "empty")
    except Exception as exc:
        item.update(error=_safe_error(exc), requires_retry=True, local_audio_retained=audio.exists() or audio.with_suffix(".wav.part").exists())
        record = checkpoint.get(recording["id"]) or {}
        record.update(status="error", error=item["error"], local_audio=str(audio) if audio.exists() else None)
        checkpoint.set(recording["id"], record)
    return item


def build_parser() -> argparse.ArgumentParser:
    default = Path(os.environ.get("SPEECH_TRANSCRIPTION_STATE", "/workspace/.speech-recorder-transcription"))
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--folder-id", help="Recording folder Drive ID; otherwise discover the unique SpeechRecorder folder")
    parser.add_argument("--state-dir", type=Path, default=default, help="Private checkpoints/audio/transcripts outside checkout")
    parser.add_argument("--credentials", type=Path, help="OAuth desktop client JSON outside checkout")
    parser.add_argument("--token", type=Path, help="Authorized-user OAuth token JSON outside checkout")
    parser.add_argument("--authorize", action="store_true", help="Run loopback OAuth (usually locally, then configure token securely)")
    parser.add_argument("--oauth-port", type=int, default=8765)
    parser.add_argument("--dry-run", action="store_true", help="List and inspect existing transcripts; do not download WAVs or write Drive")
    parser.add_argument("--resume", action="store_true", help="Reuse verified chunk checkpoints with matching source/settings")
    parser.add_argument("--retry-empty", action="store_true", help="Retry empty/Descript no-speech documents; keep originals/backups")
    parser.add_argument("--limit", type=int, help="Maximum eligible recordings to process (scan and report still cover the folder)")
    parser.add_argument("--model", default="large-v3", help="faster-whisper model name or trusted local model directory")
    parser.add_argument("--model-cache", type=Path, help="Private local model cache outside checkout")
    parser.add_argument("--offline-model", action="store_true", help="Use cached/local model weights without model-download network requests")
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--language", choices=("pl", "en"), help="Default: detect each chunk's language automatically")
    parser.add_argument("--chunk-seconds", type=int, default=600, help="Bounded-memory checkpoint interval, 30..1800 seconds")
    parser.add_argument("--batch-size", type=int, default=8, help="Report/checkpoint processing batch size")
    parser.add_argument("--workers", type=int, default=1, help="Requested workers; capped by measured CPU/RAM/GPU capacity")
    parser.add_argument("--report", type=Path, help="JSON report outside checkout; default STATE/report.json")
    return parser


def prepare_args(parser: argparse.ArgumentParser, argv: list[str] | None) -> argparse.Namespace:
    args = parser.parse_args(argv)
    checkout = Path(__file__).resolve().parents[1]
    args.state_dir = path_outside_checkout(args.state_dir, checkout)
    args.credentials = path_outside_checkout(args.credentials or args.state_dir / "oauth-client.json", checkout)
    args.token = path_outside_checkout(args.token or args.state_dir / "oauth-token.json", checkout)
    args.report = path_outside_checkout(args.report or args.state_dir / "report.json", checkout)
    args.model_cache = path_outside_checkout(args.model_cache or args.state_dir / "models", checkout)
    if Path(args.model).exists():
        args.model = str(path_outside_checkout(Path(args.model), checkout))
    if args.limit is not None and args.limit < 0:
        parser.error("--limit must be nonnegative")
    if not 30 <= args.chunk_seconds <= 1800 or args.batch_size < 1 or args.workers < 1:
        parser.error("--chunk-seconds must be 30..1800; --batch-size and --workers must be positive")
    args.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(args.state_dir, 0o700)
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["DO_NOT_TRACK"] = "1"
    # Supported verified HTTPS model downloads avoid additional Xet CAS hosts.
    os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
    # Do not let default library caches put model/auth data into the checkout.
    os.environ["HF_HOME"] = str(args.model_cache)
    return args


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    report: dict[str, Any] = {"started_at": utc_now(), "status": "starting", "files": [],
                              "inventory_before": None, "inventory_after": None,
                              "counts": {"success": 0, "empty": 0, "skipped": 0, "error": 0, "pending": 0}}
    args = None
    try:
        args = prepare_args(parser, argv)
        signal.signal(signal.SIGTERM, lambda *_: STOP.set())
        signal.signal(signal.SIGINT, lambda *_: STOP.set())
        with CheckpointStore(args.state_dir) as checkpoint:
            drive = DriveClient(create_session(args))
            folder = discover_folder(drive, args.folder_id)
            current = inventory(drive, folder)
            report.update(folder={"id": folder["id"], "name": folder["name"]}, dry_run=args.dry_run,
                          inventory_before=current["counts"], model=args.model)
            print(json.dumps({"folder": report["folder"], "inventory": current["counts"]}, ensure_ascii=False), flush=True)
            eligible = []
            for recording in current["recordings"]:
                if recording["id"] in current["ambiguous_source_ids"]:
                    report["files"].append({"id": recording["id"], "name": recording["name"], "status": "error",
                                            "error": "duplicate_original_names_same_folder", "requires_retry": True})
                    continue
                decision = inspect_existing(drive, recording, current["files"], args.retry_empty)
                item = {"id": recording["id"], "name": recording["name"], "reason": decision["reason"]}
                if decision["action"] == "skip":
                    item.update(status="skipped", requires_retry=decision["reason"] != "complete",
                                existing_status="complete" if decision["complete"] else "empty")
                    report["files"].append(item)
                elif decision["action"] == "conflict":
                    item.update(status="error", error=decision["reason"], requires_retry=True)
                    report["files"].append(item)
                else:
                    eligible.append(recording)
            # Sort by known WAV byte size so a limited smoke run exercises short
            # recordings first without downloading the full collection.
            eligible.sort(key=lambda f: (int(f.get("size", 1 << 62)), f["name"], f["id"]))
            selected = eligible if args.limit is None else eligible[:args.limit]
            deferred = eligible[len(selected):]
            report["eligible_recordings"] = len(eligible)
            report["selected_recordings"] = len(selected)
            for recording in deferred:
                report["files"].append({"id": recording["id"], "name": recording["name"], "status": "pending", "reason": "limit", "requires_retry": True})
            if args.dry_run:
                for recording in selected:
                    report["files"].append({"id": recording["id"], "name": recording["name"], "status": "pending", "reason": "dry_run", "requires_retry": True})
                report.update(status="dry_run", inventory_after=current["counts"])
            elif selected:
                report["files"].extend({"id": file["id"], "name": file["name"], "status": "pending",
                                         "reason": "not_processed", "requires_retry": True} for file in selected)
                report["status"] = "processing"
                _update_report(report)
                atomic_json(args.report, report)
                transcriber = LocalTranscriber(args)
                from concurrent.futures import ThreadPoolExecutor, as_completed
                for start in range(0, len(selected), args.batch_size):
                    if STOP.is_set():
                        break
                    batch = selected[start:start + args.batch_size]
                    with ThreadPoolExecutor(max_workers=transcriber.workers) as pool:
                        futures = {pool.submit(process_one, drive, transcriber, file, args, checkpoint): file for file in batch}
                        for future in as_completed(futures):
                            item = future.result()
                            report["files"] = [prior for prior in report["files"] if prior["id"] != item["id"]]
                            report["files"].append(item)
                            print(f"{item['name']}: {item['status']}" + (f" ({item['error']})" if item.get("error") else ""), flush=True)
                            _update_report(report)
                            atomic_json(args.report, report)
                    print(f"Batch completed: {min(start + len(batch), len(selected))}/{len(selected)}", flush=True)
                report["inventory_after"] = inventory(drive, folder)["counts"] if not STOP.is_set() else None
                reported = {item["id"] for item in report["files"]}
                for recording in selected:
                    if recording["id"] not in reported:
                        report["files"].append({"id": recording["id"], "name": recording["name"], "status": "pending", "reason": "interrupted", "requires_retry": True})
                report["status"] = "interrupted" if STOP.is_set() else "finished"
            else:
                report.update(status="finished", inventory_after=current["counts"])
            # Final content inventory verifies counts and identifies every recording
            # still missing a recognized complete transcript, including old empties.
            if not args.dry_run and not STOP.is_set():
                final = inventory(drive, folder)
                report["inventory_after"] = final["counts"]
                report["remaining_recordings"] = []
                for recording in final["recordings"]:
                    if recording["id"] in final["ambiguous_source_ids"]:
                        report["remaining_recordings"].append({"id": recording["id"], "name": recording["name"], "reason": "duplicate_original_names_same_folder"})
                        continue
                    decision = inspect_existing(drive, recording, final["files"], True)
                    if not decision["complete"]:
                        report["remaining_recordings"].append({"id": recording["id"], "name": recording["name"], "reason": decision["reason"]})
            _update_report(report)
            report["finished_at"] = utc_now()
            atomic_json(args.report, report)
            print(json.dumps({"status": report["status"], "counts": report["counts"],
                              "remaining": len(report.get("remaining_recordings", report["requires_retry"])),
                              "report": str(args.report)}, ensure_ascii=False), flush=True)
            if args.dry_run:
                return 2 if report["counts"]["error"] else 0
            return 2 if report["counts"]["error"] or report["counts"]["empty"] or report["counts"]["pending"] or report.get("remaining_recordings") or STOP.is_set() else 0
    except Exception as exc:
        report.update(status="blocked", error=_safe_error(exc), finished_at=utc_now())
        _update_report(report)
        if args:
            with contextlib.suppress(OSError):
                atomic_json(args.report, report)
        print("Blocked: " + report["error"], file=sys.stderr, flush=True)
        return 1


def _update_report(report: dict[str, Any]) -> None:
    report["counts"] = {key: sum(item.get("status") == key for item in report["files"])
                        for key in ("success", "empty", "skipped", "error", "pending")}
    report["requires_retry"] = [{"id": item["id"], "name": item["name"],
                                  "reason": item.get("error") or item.get("reason") or item["status"]}
                                 for item in report["files"] if item.get("requires_retry")]


if __name__ == "__main__":
    raise SystemExit(main())
