#!/usr/bin/env python3
"""Credential-free regression tests for the resumable Drive transcription CLI.

Run with: python3 scripts/test-transcribe-drive.py
All Drive requests and model inference are fake; fixtures stay in temporary dirs.
"""

import importlib.util
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).with_name("transcribe-drive.py")
SPEC = importlib.util.spec_from_file_location("transcribe_drive", SCRIPT)
cli = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = cli
SPEC.loader.exec_module(cli)


NAME = "speech_20261012_183218"
SOURCE = {
    "id": "wav-1", "name": NAME + ".wav", "parents": ["folder-1"],
    "mimeType": "audio/wav", "md5Checksum": "abc123", "size": "320044",
    "modifiedTime": "2026-10-12T18:32:30Z",
}
COMPLETE = "[00:00:00.000 --> 00:00:03.000] Cześć. This is a test.\n"


def file_meta(file_id, name, parent="folder-1", **extra):
    result = {"id": file_id, "name": name, "parents": [parent]}
    result.update(extra)
    return result


class NameMatchingTests(unittest.TestCase):
    def test_full_recordings_include_android_uuid_names(self):
        for name in (NAME + ".wav", NAME + "_e20a2da7-91ca-4f90-8888-d8dbe6156c48.wav"):
            with self.subTest(name=name):
                self.assertTrue(cli.is_recording_name(name))

    def test_other_extensions_and_non_recordings_are_rejected(self):
        for name in ("song.wav", NAME + ".wav.zip", NAME + ".transkrypcja.txt", NAME + ".wav.tmp", "speech_.wav"):
            with self.subTest(name=name):
                self.assertFalse(cli.is_recording_name(name))

    def test_output_name_preserves_recording_basename(self):
        self.assertEqual(cli.expected_transcript_name(SOURCE["name"]), NAME + ".transkrypcja.txt")

    def test_technical_fragments_are_paired_with_the_full_recording(self):
        self.assertEqual(cli.recording_key("__sr_live_" + NAME + "_part0001.wav"), cli.recording_key(NAME + ".wav"))

    def test_full_recording_suppresses_only_its_technical_fragments(self):
        full = dict(SOURCE)
        live = file_meta("live-1", "__sr_live_" + NAME + "_part0001.wav")
        orphan = file_meta("live-2", "__sr_live_speech_20261012_185900_part0001.wav")
        selected = cli.select_recordings([full, live, orphan])
        self.assertEqual({item["id"] for item in selected["recordings"]}, {"wav-1", "live-2"})
        self.assertEqual([item["id"] for item in selected["ignored_live"]], ["live-1"])
        self.assertEqual([item["id"] for item in selected["orphan_live"]], ["live-2"])

    def test_identical_names_in_other_folders_do_not_suppress_orphans(self):
        live = file_meta("live-1", "__sr_live_" + NAME + "_part0001.wav", parent="folder-2")
        selected = cli.select_recordings([dict(SOURCE), live])
        self.assertEqual({item["id"] for item in selected["recordings"]}, {"wav-1", "live-1"})

    def test_inventory_pairs_live_subfolder_fragments_with_parent_full_wavs(self):
        folder = file_meta("folder-1", "SpeechRecorder", mimeType=cli.FOLDER_MIME)
        live_folder = file_meta("live-folder", "__sr_live", mimeType=cli.FOLDER_MIME)
        fragment = file_meta("live-1", "__sr_live_" + NAME + "_part0001.wav", parent="live-folder")
        drive = Mock()
        drive.contents.side_effect = [[dict(SOURCE), live_folder], [fragment]]
        result = cli.inventory(drive, folder)
        self.assertEqual([file["id"] for file in result["recordings"]], [SOURCE["id"]])
        self.assertEqual([file["id"] for file in result["ignored_live"]], ["live-1"])
        self.assertEqual(result["counts"]["original_full_wav"], 1)


class TranscriptClassificationTests(unittest.TestCase):
    def test_descript_empty_results_are_retryable(self):
        for text in ("", "  \n", "No speech detected", "No speech was detected in this recording.", "Brak rozpoznanej mowy", "Nie wykryto mowy.", "Nie wykryto żadnej mowy w nagraniu."):
            with self.subTest(text=text):
                self.assertEqual(cli.classify_transcript(text), "empty")

    def test_headers_without_a_body_are_empty(self):
        self.assertEqual(cli.classify_transcript("Nagranie: " + SOURCE["name"] + "\nJęzyk: pl\nCzas trwania: 00:00:10.000\nTranskrypcja:\n"), "empty")

    def test_timestamped_speech_is_complete(self):
        self.assertEqual(cli.classify_transcript(COMPLETE), "complete")

    def test_error_and_partial_markers_are_not_complete(self):
        for text in ("Error: transcription failed", "Status: partial\n" + COMPLETE, "Status: error\n" + COMPLETE,
                     "Status: Częściowo rozpoznana mowa\n" + COMPLETE,
                     "Status: Częściowo rozpoznana mowa\nPrzykładowa urwana wypowiedź."):
            with self.subTest(text=text):
                self.assertEqual(cli.classify_transcript(text), "uncertain")

    def test_empty_phrase_inside_actual_speech_does_not_erase_speech(self):
        self.assertEqual(cli.classify_transcript("[00:00:00.000 --> 00:00:03.000] Powiedziałem: brak rozpoznanej mowy.\n"), "complete")

    def test_legacy_no_speech_status_does_not_count_explanatory_prose_as_speech(self):
        for status in ("Nie rozpoznano czytelnej mowy w tym nagraniu", "Brak czytelnej transkrypcji z narzędzia",
                       "Brak rozpoznanych wypowiedzi"):
            with self.subTest(status=status):
                text = ("Transkrypcja z narzędzia\nNagranie: speech_test.wav\n"
                        "Status: " + status + "\n\n"
                        "Wynik wymaga ponownego przetworzenia w innym narzędziu.\n"
                        "Ten dokument zawiera informację o wyniku rozpoznawania.\n")
                self.assertEqual(cli.classify_transcript(text), "empty")

    def test_timestamped_speech_overrides_an_old_no_speech_status(self):
        text = "Status: Nie rozpoznano czytelnej mowy\n" + COMPLETE
        self.assertEqual(cli.classify_transcript(text), "complete")

    def test_legacy_speech_status_preserves_untimestamped_transcript(self):
        text = ("Nagranie: speech_test.wav\nStatus: Rozpoznana mowa, transkrypcja poniżej\n\n"
                "To jest przykładowa pełna wypowiedź z nagrania.\n")
        self.assertEqual(cli.classify_transcript(text), "complete")


class SourceMatchingTests(unittest.TestCase):
    def test_fingerprint_is_stable_for_copied_metadata(self):
        self.assertEqual(cli.source_fingerprint(SOURCE), cli.source_fingerprint(dict(SOURCE)))

    def test_source_changes_invalidate_the_fingerprint(self):
        changed = dict(SOURCE, md5Checksum="changed")
        self.assertNotEqual(cli.source_fingerprint(SOURCE), cli.source_fingerprint(changed))

    def test_transcript_name_must_match_exactly(self):
        correct = file_meta("txt-1", NAME + ".transkrypcja.txt")
        unrelated = file_meta("txt-2", NAME + "_extra.transkrypcja.txt")
        self.assertEqual([item["id"] for item in cli.matching_transcripts(SOURCE, [correct, unrelated])], ["txt-1"])

    def test_transcript_in_another_parent_is_not_a_match(self):
        wrong_folder = file_meta("txt-1", NAME + ".transkrypcja.txt", parent="folder-2")
        self.assertEqual(cli.matching_transcripts(SOURCE, [wrong_folder]), [])

    def test_legacy_google_docs_keep_wav_extension_before_transcription_suffix(self):
        for suffix in (".transkrypcja", ".transcription"):
            with self.subTest(suffix=suffix):
                transcript = file_meta("doc-1", SOURCE["name"] + suffix, mimeType=cli.DOC_MIME)
                selected = cli.select_existing(SOURCE, [transcript], {"doc-1": COMPLETE})
                self.assertEqual(selected["action"], "skip")
                self.assertEqual(selected["reason"], "complete")

    def test_legacy_wav_transcription_names_do_not_match_other_sources(self):
        for suffix in (".transkrypcja", ".transcription"):
            with self.subTest(suffix=suffix):
                unrelated = file_meta("doc-1", NAME + "_extra.wav" + suffix, mimeType=cli.DOC_MIME)
                wrong_folder = file_meta("doc-2", SOURCE["name"] + suffix, parent="folder-2", mimeType=cli.DOC_MIME)
                wrong_source_id = file_meta("doc-3", SOURCE["name"] + suffix, mimeType=cli.DOC_MIME,
                                            appProperties={"srSourceId": "wav-other"})
                self.assertEqual(cli.matching_transcripts(SOURCE, [unrelated, wrong_folder, wrong_source_id]), [])

    def test_empty_legacy_wav_transcription_document_is_retryable(self):
        transcript = file_meta("doc-1", SOURCE["name"] + ".transkrypcja", mimeType=cli.DOC_MIME)
        content = {"doc-1": "Brak rozpoznanej mowy"}
        self.assertEqual(cli.select_existing(SOURCE, [transcript], content)["reason"], "retry_empty_required")
        selected = cli.select_existing(SOURCE, [transcript], content, retry_empty=True)
        self.assertEqual(selected["action"], "process")
        self.assertEqual(selected["empty"], [transcript])

    def test_source_id_matches_a_renamed_document_in_same_folder(self):
        renamed = file_meta("txt-1", "Previous Descript document", appProperties={"srSourceId": SOURCE["id"]})
        self.assertEqual([item["id"] for item in cli.matching_transcripts(SOURCE, [renamed])], ["txt-1"])

    def test_source_id_never_overrides_the_source_parent(self):
        renamed = file_meta("txt-1", "Previous Descript document", parent="folder-2", appProperties={"srSourceId": SOURCE["id"]})
        self.assertEqual(cli.matching_transcripts(SOURCE, [renamed]), [])

    def test_archived_backup_is_not_an_active_transcript(self):
        backup = file_meta("backup-1", NAME + ".transkrypcja.txt.backup-old.txt", appProperties={"srSourceId": SOURCE["id"], "srArchived": "1"})
        self.assertEqual(cli.matching_transcripts(SOURCE, [backup]), [])

    def test_complete_existing_transcript_skips_model_work(self):
        transcript = file_meta("txt-1", NAME + ".transkrypcja.txt")
        selected = cli.select_existing(SOURCE, [transcript], {"txt-1": COMPLETE})
        self.assertEqual(selected["action"], "skip")

    def test_retry_empty_reprocesses_descript_empty_output(self):
        transcript = file_meta("txt-1", NAME + ".transkrypcja.txt")
        selected = cli.select_existing(SOURCE, [transcript], {"txt-1": "Brak rozpoznanej mowy"}, retry_empty=True)
        self.assertEqual(selected["action"], "process")

    def test_one_complete_duplicate_prevents_a_new_output(self):
        first = file_meta("txt-1", NAME + ".transkrypcja.txt")
        second = file_meta("txt-2", NAME + ".transkrypcja.txt")
        selected = cli.select_existing(SOURCE, [first, second], {"txt-1": "Brak rozpoznanej mowy", "txt-2": COMPLETE}, retry_empty=True)
        self.assertEqual(selected["action"], "skip")

    def test_changed_source_fingerprint_is_not_skipped(self):
        transcript = file_meta("txt-1", NAME + ".transkrypcja.txt", appProperties={"srSourceId": SOURCE["id"], "srSourceFingerprint": "stale"})
        selected = cli.select_existing(SOURCE, [transcript], {"txt-1": COMPLETE})
        self.assertNotEqual(selected["action"], "skip")

    def test_matching_fingerprint_and_complete_transcript_are_skipped(self):
        transcript = file_meta("txt-1", NAME + ".transkrypcja.txt", appProperties={"srSourceId": SOURCE["id"], "srSourceFingerprint": cli.source_fingerprint(SOURCE)})
        selected = cli.select_existing(SOURCE, [transcript], {"txt-1": COMPLETE})
        self.assertEqual(selected["action"], "skip")

    def test_multiple_empty_output_names_are_reported_as_conflicts(self):
        first = file_meta("txt-1", NAME + ".transkrypcja.txt")
        second = file_meta("txt-2", NAME + ".transkrypcja.txt")
        selected = cli.select_existing(SOURCE, [first, second], {"txt-1": "No speech detected", "txt-2": ""}, retry_empty=True)
        self.assertEqual(selected["action"], "conflict")

    def test_uncertain_existing_body_is_never_silently_overwritten(self):
        transcript = file_meta("txt-1", NAME + ".transkrypcja.txt")
        selected = cli.select_existing(SOURCE, [transcript], {"txt-1": "Status: partial\n" + COMPLETE}, retry_empty=True)
        self.assertEqual(selected["action"], "conflict")


class CheckpointTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.state_dir = Path(self.work.name)

    def test_set_persists_by_drive_file_id_for_resume(self):
        store = cli.CheckpointStore(self.state_dir)
        record = {"status": "success", "fingerprint": cli.source_fingerprint(SOURCE), "transcript_id": "txt-1"}
        store.set(SOURCE["id"], record)
        reloaded = cli.CheckpointStore(self.state_dir)
        saved = reloaded.get(SOURCE["id"])
        self.assertEqual({key: saved[key] for key in record}, record)
        self.assertIn("updated_at", saved)
        self.assertIsNone(reloaded.get("wav-other"))

    def test_atomic_save_never_replaces_valid_checkpoint_on_failure(self):
        store = cli.CheckpointStore(self.state_dir)
        first = {"status": "success"}
        store.set("wav-1", first)
        before = [(path, path.read_bytes()) for path in self.state_dir.glob("*.json")]
        self.assertTrue(before, "checkpoint must be persisted as JSON")
        with patch.object(cli.os, "replace", side_effect=OSError("simulated disk failure")):
            with self.assertRaises(OSError):
                store.set("wav-2", {"status": "pending"})
        for path, contents in before:
            self.assertEqual(path.read_bytes(), contents)
            json.loads(path.read_text(encoding="utf-8"))
        reloaded = cli.CheckpointStore(self.state_dir)
        self.assertEqual(reloaded.get("wav-1")["status"], first["status"])
        self.assertIsNone(reloaded.get("wav-2"))

    def test_only_one_process_can_own_a_checkpoint(self):
        first = cli.CheckpointStore(self.state_dir)
        second = cli.CheckpointStore(self.state_dir)
        first.acquire()
        self.addCleanup(first.release)
        with self.assertRaises((RuntimeError, OSError)):
            second.acquire()

    def test_released_checkpoint_can_be_resumed(self):
        first = cli.CheckpointStore(self.state_dir)
        first.acquire()
        first.set("wav-1", {"status": "downloaded"})
        first.release()
        second = cli.CheckpointStore(self.state_dir)
        second.acquire()
        self.addCleanup(second.release)
        self.assertEqual(second.get("wav-1")["status"], "downloaded")

    def test_corrupt_checkpoint_is_preserved_and_fails_closed(self):
        path = self.state_dir / "checkpoint.json"
        path.write_text("{incomplete", encoding="utf-8")
        with self.assertRaises(cli.TranscriptionError):
            cli.CheckpointStore(self.state_dir)
        self.assertEqual(path.read_text(encoding="utf-8"), "{incomplete")

    def test_returned_records_cannot_mutate_stored_state(self):
        store = cli.CheckpointStore(self.state_dir)
        store.set("wav-1", {"chunks": {"0": {"segments": []}}})
        record = store.get("wav-1")
        record["chunks"]["0"]["segments"].append("uncommitted")
        self.assertEqual(store.get("wav-1")["chunks"]["0"]["segments"], [])


class Response:
    def __init__(self, status=200, payload=None, content=b"", headers=None):
        self.status_code = status
        self.payload = payload or {}
        self.content = content
        self.headers = headers or {}
        self.close = Mock()

    def json(self):
        return self.payload

    def iter_content(self, chunk_size):
        yield self.content


class FakeRequestException(Exception):
    pass


FAKE_REQUESTS = ModuleType("requests")
FAKE_REQUESTS.exceptions = SimpleNamespace(
    RequestException=FakeRequestException,
    Timeout=type("FakeTimeout", (FakeRequestException,), {}),
    ConnectionError=type("FakeConnectionError", (FakeRequestException,), {}),
)


class DriveApiTests(unittest.TestCase):
    def setUp(self):
        cli.STOP.clear()
        self.addCleanup(cli.STOP.clear)
        self.session = Mock()
        self.drive = cli.DriveClient(self.session, retries=2)
        self.modules = patch.dict(sys.modules, {"requests": FAKE_REQUESTS})
        self.modules.start()
        self.addCleanup(self.modules.stop)

    def test_all_drive_pages_are_included(self):
        first = Response(payload={"files": [dict(SOURCE)], "nextPageToken": "next-1"})
        second = Response(payload={"files": [file_meta("wav-2", "speech_2.wav")]})
        self.session.request.side_effect = [first, second]
        files = self.drive.list_files("trashed = false")
        self.assertEqual([file["id"] for file in files], ["wav-1", "wav-2"])
        self.assertNotIn("pageToken", self.session.request.call_args_list[0].kwargs["params"])
        self.assertEqual(self.session.request.call_args_list[1].kwargs["params"]["pageToken"], "next-1")
        first.close.assert_called_once()
        second.close.assert_called_once()

    def test_repeated_page_token_is_an_error(self):
        self.session.request.side_effect = [Response(payload={"nextPageToken": "same"}), Response(payload={"nextPageToken": "same"})]
        with self.assertRaisesRegex(cli.TranscriptionError, "pagination"):
            self.drive.list_files("trashed = false")

    def test_incomplete_inventory_never_claims_success(self):
        self.session.request.return_value = Response(payload={"files": [dict(SOURCE)], "incompleteSearch": True})
        with self.assertRaisesRegex(cli.TranscriptionError, "incomplete"):
            self.drive.list_files("trashed = false")

    def test_rate_limit_retries_and_honors_retry_after(self):
        limited = Response(status=429, headers={"Retry-After": "3"})
        success = Response()
        self.session.request.side_effect = [limited, success]
        with patch.object(cli.STOP, "wait", return_value=False) as wait:
            self.assertIs(self.drive.request("GET", cli.DRIVE_API), success)
        self.assertGreaterEqual(wait.call_args.args[0], 3)
        self.assertEqual(self.session.request.call_count, 2)
        limited.close.assert_called_once()

    def test_drive_403_rate_limit_retries_but_permission_error_does_not(self):
        limited = Response(status=403, payload={"error": {"errors": [{"reason": "userRateLimitExceeded"}]}})
        self.session.request.side_effect = [limited, Response()]
        with patch.object(cli.STOP, "wait", return_value=False):
            self.drive.request("GET", cli.DRIVE_API)
        self.session.request.reset_mock()
        self.session.request.side_effect = None
        self.session.request.return_value = Response(status=403, payload={"error": {"errors": [{"reason": "insufficientPermissions"}]}})
        with patch.object(cli.STOP, "wait", return_value=False) as wait:
            with self.assertRaises(cli.DriveError):
                self.drive.request("GET", cli.DRIVE_API)
        self.session.request.assert_called_once()
        wait.assert_not_called()

    def test_retry_limit_is_bounded(self):
        self.session.request.side_effect = [Response(status=503) for _ in range(3)]
        with patch.object(cli.STOP, "wait", return_value=False):
            with self.assertRaises(cli.DriveError):
                self.drive.request("GET", cli.DRIVE_API)
        self.assertEqual(self.session.request.call_count, 3)

    def test_network_timeout_retries_without_changing_request(self):
        self.session.request.side_effect = [FAKE_REQUESTS.exceptions.Timeout(), Response()]
        with patch.object(cli.STOP, "wait", return_value=False):
            self.drive.request("GET", cli.DRIVE_API, params={"q": "private"})
        self.assertEqual(self.session.request.call_args_list[0], self.session.request.call_args_list[1])

    def test_google_document_is_exported_as_plain_text(self):
        self.session.request.return_value = Response(content=COMPLETE.encode())
        self.assertEqual(self.drive.read_text(file_meta("doc-1", NAME, mimeType=cli.DOC_MIME)), COMPLETE)
        args, kwargs = self.session.request.call_args
        self.assertTrue(args[1].endswith("/doc-1/export"))
        self.assertEqual(kwargs["params"]["mimeType"], "text/plain")

    def test_file_etag_is_retained_for_conditional_updates(self):
        self.session.request.return_value = Response(payload=dict(SOURCE), headers={"ETag": '"revision-1"'})
        self.assertEqual(self.drive.get_file(SOURCE["id"])["_etag"], '"revision-1"')

    def test_download_checksum_failure_retains_the_partial_copy(self):
        self.drive.retries = 0
        self.session.request.return_value = Response(content=b"tampered audio")
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "audio.wav"
            with self.assertRaises(cli.TranscriptionError):
                self.drive.download(dict(SOURCE, size="14", md5Checksum="incorrect"), path)
            self.assertFalse(path.exists())
            self.assertTrue(path.with_suffix(".wav.part").exists())


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.checkpoint = cli.CheckpointStore(Path(self.work.name))
        self.session = Mock()
        self.drive = cli.DriveClient(self.session)
        self.target = file_meta("txt-1", NAME + ".transkrypcja.txt", mimeType="text/plain", _etag='"version-1"')
        self.drive.get_file = Mock(side_effect=lambda file_id: dict(SOURCE) if file_id == SOURCE["id"] else dict(self.target))
        self.drive.contents = Mock(return_value=[dict(self.target)])
        self.drive.read_text = Mock(return_value="Brak rozpoznanej mowy")
        self.drive.verify_uploaded = Mock(return_value=dict(self.target))
        self.drive.request = Mock(return_value=Response())

    def test_original_transcript_is_archived_before_patch(self):
        events = []
        self.drive.archive_existing = Mock(side_effect=lambda target: events.append("archive"))
        self.drive.request.side_effect = lambda *args, **kwargs: (events.append("upload"), Response())[1]
        self.drive.publish_text(SOURCE, COMPLETE, [self.target], self.checkpoint)
        self.assertEqual(events, ["archive", "upload"])
        self.assertEqual(self.drive.request.call_args.args[0], "PATCH")
        self.assertIn("/txt-1", self.drive.request.call_args.args[1])
        self.assertEqual(self.drive.request.call_args.kwargs["headers"]["If-Match"], '"version-1"')

    def test_failed_archival_leaves_original_untouched(self):
        self.drive.archive_existing = Mock(side_effect=cli.TranscriptionError("backup failed"))
        with self.assertRaises(cli.TranscriptionError):
            self.drive.publish_text(SOURCE, COMPLETE, [self.target], self.checkpoint)
        self.drive.request.assert_not_called()

    def test_complete_transcript_appearing_during_processing_is_preserved(self):
        self.drive.read_text.return_value = COMPLETE
        self.drive.archive_existing = Mock()
        with self.assertRaises(cli.TranscriptionError):
            self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.archive_existing.assert_not_called()
        self.drive.request.assert_not_called()

    def test_changed_source_is_not_published(self):
        self.drive.get_file.side_effect = None
        self.drive.get_file.return_value = dict(SOURCE, md5Checksum="changed")
        with self.assertRaises(cli.TranscriptionError):
            self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.request.assert_not_called()

    def test_renamed_moved_or_trashed_source_is_not_published(self):
        for changed in (dict(SOURCE, name="speech_renamed.wav"), dict(SOURCE, parents=["moved-folder"]), dict(SOURCE, trashed=True)):
            with self.subTest(metadata=changed):
                self.drive.get_file.side_effect = None
                self.drive.get_file.return_value = changed
                with self.assertRaises(cli.TranscriptionError):
                    self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
                self.drive.request.assert_not_called()

    def test_concurrent_target_duplicates_are_preserved(self):
        self.drive.contents.return_value.append(dict(self.target, id="txt-2"))
        with self.assertRaises(cli.TranscriptionError):
            self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.request.assert_not_called()

    def test_etag_conflict_keeps_the_archived_original_and_aborts_update(self):
        self.drive.archive_existing = Mock()
        self.drive.request.side_effect = cli.DriveError(412, "conditionNotMet")
        with self.assertRaises(cli.DriveError):
            self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.archive_existing.assert_called_once()
        self.drive.verify_uploaded.assert_not_called()

    def test_reserved_upload_id_is_saved_before_create(self):
        self.drive.contents.return_value = []
        self.drive.generate_id = Mock(return_value="reserved-id")
        observed_ids = []
        def upload(*args, **kwargs):
            observed_ids.append(self.checkpoint.get(SOURCE["id"])["upload_id"])
            return Response()
        self.drive.request.side_effect = upload
        self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.assertEqual(observed_ids, ["reserved-id"])
        self.assertEqual(self.drive.request.call_args.args[0], "POST")

    def test_retry_uses_the_saved_reserved_id_instead_of_creating_another(self):
        self.checkpoint.set(SOURCE["id"], {"upload_id": "reserved-id"})
        self.drive.contents.return_value = []
        self.drive.generate_id = Mock()
        self.drive.verify_uploaded.side_effect = [cli.DriveError(404), dict(self.target)]
        self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.generate_id.assert_not_called()
        self.assertEqual(self.drive.verify_uploaded.call_args_list[0].args[0], "reserved-id")
        self.assertEqual(self.drive.verify_uploaded.call_args_list[1].args[0], "reserved-id")

    def test_ambiguous_completed_create_is_recovered_by_reserved_id(self):
        self.drive.contents.return_value = []
        self.drive.generate_id = Mock(return_value="reserved-id")
        self.drive.request.side_effect = cli.DriveError(409)
        self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.verify_uploaded.assert_called_once_with("reserved-id", COMPLETE, SOURCE)

    def test_archive_copy_content_is_verified_before_returning(self):
        drive = cli.DriveClient(self.session)
        backup = file_meta("backup-1", "backup.txt", mimeType="text/plain")
        drive.get_file = Mock(return_value=dict(self.target))
        drive.contents = Mock(return_value=[])
        drive.read_text = Mock(side_effect=["original text", "original text"])
        self.session.request.return_value = Response(payload=backup)
        self.assertEqual(drive.archive_existing(self.target), backup)
        self.assertEqual(self.session.request.call_args.args[0], "POST")
        self.assertTrue(self.session.request.call_args.args[1].endswith("/txt-1/copy"))
        self.assertEqual(self.session.request.call_args.kwargs["json"]["parents"], ["folder-1"])

    def test_backup_content_mismatch_prevents_replacement(self):
        drive = cli.DriveClient(self.session)
        drive.get_file = Mock(return_value=dict(self.target))
        drive.contents = Mock(return_value=[])
        drive.read_text = Mock(side_effect=["original text", "different backup content"])
        self.session.request.return_value = Response(payload=file_meta("backup-1", "backup.txt"))
        with self.assertRaises(cli.TranscriptionError):
            drive.archive_existing(self.target)

    def test_uploaded_text_requires_matching_parent_and_provenance(self):
        drive = cli.DriveClient(self.session)
        properties = {"srSourceId": SOURCE["id"], "srSourceFingerprint": cli.source_fingerprint(SOURCE)}
        drive.read_text = Mock(return_value=COMPLETE)
        for wrong in (dict(self.target, parents=["other-folder"], appProperties=properties), dict(self.target, appProperties={"srSourceId": "other-wav"})):
            with self.subTest(metadata=wrong):
                drive.get_file = Mock(side_effect=lambda file_id: dict(SOURCE) if file_id == SOURCE["id"] else wrong)
                with self.assertRaises(cli.TranscriptionError):
                    drive.verify_uploaded("txt-1", COMPLETE, SOURCE)

    def configure_google_document(self):
        remote = dict(self.target, mimeType=cli.DOC_MIME)
        self.drive.get_file = Mock(side_effect=lambda file_id: dict(SOURCE) if file_id == SOURCE["id"] else dict(remote))
        self.drive.contents = Mock(side_effect=lambda folder: [dict(remote)])
        self.drive.archive_existing = Mock()
        self.drive.generate_id = Mock(return_value="new-txt-id")
        self.drive.verify_uploaded.return_value = dict(self.target, id="new-txt-id")
        return remote

    def test_empty_canonical_google_doc_is_preserved_before_new_txt(self):
        remote = self.configure_google_document()
        def request(method, url, **kwargs):
            if method == "PATCH":
                remote.update(kwargs["json"])
            return Response()
        self.drive.request.side_effect = request
        self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        calls = self.drive.request.call_args_list
        self.assertEqual([call.args[0] for call in calls], ["PATCH", "POST"])
        self.assertEqual(calls[0].kwargs["headers"]["If-Match"], '"version-1"')
        self.assertEqual(remote["appProperties"]["srArchived"], "1")
        self.assertEqual(remote["parents"], SOURCE["parents"])
        self.drive.archive_existing.assert_called_once()
        self.drive.verify_uploaded.assert_called_once_with("new-txt-id", COMPLETE, SOURCE)

    def test_google_doc_rename_conflict_never_creates_a_new_txt(self):
        self.configure_google_document()
        self.drive.request.side_effect = cli.DriveError(412)
        with self.assertRaises(cli.DriveError):
            self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.generate_id.assert_not_called()
        self.assertEqual(self.drive.request.call_count, 1)
        self.assertEqual(self.drive.request.call_args.args[0], "PATCH")

    def test_resume_after_google_doc_rename_reuses_reserved_txt_id(self):
        remote = self.configure_google_document()
        def interrupted(method, url, **kwargs):
            if method == "PATCH":
                remote.update(kwargs["json"])
                return Response()
            raise cli.TranscriptionError("upload interrupted")
        self.drive.request.side_effect = interrupted
        with self.assertRaises(cli.TranscriptionError):
            self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.assertEqual(cli.matching_transcripts(SOURCE, [remote]), [])
        self.assertEqual(self.checkpoint.get(SOURCE["id"])["upload_id"], "new-txt-id")
        self.drive.request.reset_mock()
        self.drive.generate_id.reset_mock()
        self.drive.request.side_effect = None
        self.drive.request.return_value = Response()
        self.drive.verify_uploaded.side_effect = [cli.DriveError(404), dict(self.target, id="new-txt-id")]
        self.drive.publish_text(SOURCE, COMPLETE, [], self.checkpoint)
        self.drive.generate_id.assert_not_called()
        self.drive.request.assert_called_once()
        self.assertEqual(self.drive.request.call_args.args[0], "POST")
        self.drive.archive_existing.assert_called_once()


class MetadataAndCleanupTests(unittest.TestCase):
    def setUp(self):
        self.result = {"language": "pl", "languages": ["pl"], "duration": 12.345,
                       "segments": [{"start": 0.125, "end": 3.5, "text": "Kurwa, zachowaj oryginalną treść."}],
                       "status": "success", "rejected_segments": 0}

    def test_text_contains_source_language_duration_and_exact_timestamps(self):
        text = cli.render_transcript(SOURCE, self.result, "large-v3")
        self.assertIn("Nagranie: " + SOURCE["name"], text)
        self.assertIn("Język: pl", text)
        self.assertIn("Czas trwania: 00:00:12.345", text)
        self.assertIn("[00:00:00.125 --> 00:00:03.500] Kurwa, zachowaj oryginalną treść.", text)
        self.assertEqual(cli.classify_transcript(text), "complete")

    def test_hour_long_timestamps_do_not_wrap(self):
        self.assertEqual(cli.timestamp(3661.125), "01:01:01.125")

    def test_empty_result_is_not_presented_as_success(self):
        text = cli.render_transcript(SOURCE, dict(self.result, segments=[], status="empty"), "large-v3")
        self.assertIn("Status: empty", text)
        self.assertEqual(cli.classify_transcript(text), "empty")

    def test_failed_upload_retains_local_audio_and_reports_error(self):
        with tempfile.TemporaryDirectory() as work:
            args = SimpleNamespace(state_dir=Path(work), model="large-v3")
            checkpoint = cli.CheckpointStore(args.state_dir)
            drive = Mock()
            drive.get_file.return_value = dict(SOURCE)
            drive.download.side_effect = lambda recording, path: path.write_bytes(b"audio fixture")
            drive.publish_text.side_effect = cli.TranscriptionError("upload failed")
            transcriber = Mock()
            transcriber.transcribe.return_value = self.result
            item = cli.process_one(drive, transcriber, SOURCE, args, checkpoint)
            self.assertEqual(item["status"], "error")
            self.assertTrue(item["local_audio_retained"])
            self.assertTrue(item["requires_retry"])
            self.assertEqual(len(list((args.state_dir / "audio").glob("*.wav"))), 1)
            self.assertEqual(checkpoint.get(SOURCE["id"])["status"], "error")

    def test_audio_is_removed_only_after_verified_upload(self):
        with tempfile.TemporaryDirectory() as work:
            args = SimpleNamespace(state_dir=Path(work), model="large-v3")
            checkpoint = cli.CheckpointStore(args.state_dir)
            drive = Mock()
            drive.get_file.return_value = dict(SOURCE)
            drive.download.side_effect = lambda recording, path: path.write_bytes(b"audio fixture")
            retained_during_upload = []
            def publish(*unused):
                retained_during_upload.append(bool(list((args.state_dir / "audio").glob("*.wav"))))
                return {"id": "txt-1"}
            drive.publish_text.side_effect = publish
            transcriber = Mock()
            transcriber.transcribe.return_value = self.result
            item = cli.process_one(drive, transcriber, SOURCE, args, checkpoint)
            self.assertEqual(retained_during_upload, [True])
            self.assertEqual(item["status"], "success")
            self.assertEqual(list((args.state_dir / "audio").glob("*.wav")), [])
            self.assertEqual(checkpoint.get(SOURCE["id"])["transcript_id"], "txt-1")

    def test_checkpoint_failure_after_remote_success_retains_audio_and_resumes_without_upload(self):
        with tempfile.TemporaryDirectory() as work:
            args = SimpleNamespace(state_dir=Path(work), model="large-v3")
            checkpoint = cli.CheckpointStore(args.state_dir)
            remote = file_meta("txt-1", NAME + ".transkrypcja.txt", mimeType="text/plain", appProperties={"srSourceId": SOURCE["id"], "srSourceFingerprint": cli.source_fingerprint(SOURCE)})
            text = cli.render_transcript(SOURCE, self.result, args.model)
            drive = cli.DriveClient(Mock())
            drive.get_file = Mock(side_effect=lambda file_id: dict(SOURCE) if file_id == SOURCE["id"] else dict(remote))
            drive.contents = Mock(return_value=[remote])
            drive.read_text = Mock(return_value=text)
            drive.download = Mock(side_effect=lambda recording, path: path.write_bytes(b"audio fixture"))
            drive.request = Mock()
            drive.generate_id = Mock()
            transcriber = Mock()
            transcriber.transcribe.return_value = self.result
            save = checkpoint.set
            attempts = 0
            def fail_first_save(*values):
                nonlocal attempts
                attempts += 1
                if attempts == 1:
                    raise OSError("checkpoint storage unavailable")
                return save(*values)
            with patch.object(checkpoint, "set", side_effect=fail_first_save):
                failed = cli.process_one(drive, transcriber, SOURCE, args, checkpoint)
            self.assertEqual(failed["status"], "error")
            self.assertTrue(failed["local_audio_retained"])
            resumed = cli.process_one(drive, transcriber, SOURCE, args, checkpoint)
            self.assertEqual(resumed["status"], "success")
            self.assertEqual(list((args.state_dir / "audio").glob("*.wav")), [])
            drive.request.assert_not_called()
            drive.generate_id.assert_not_called()


class SizedChunk:
    def __init__(self, samples):
        self.samples = samples

    def __len__(self):
        return self.samples


def model_segment(text="Cześć i hello.", start=0.2, end=0.8, **extra):
    fields = dict(text=text, start=start, end=end, no_speech_prob=0.1,
                  avg_logprob=-0.1, compression_ratio=1.0, words=[])
    fields.update(extra)
    return SimpleNamespace(**fields)


class LocalModelTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.checkpoint = cli.CheckpointStore(Path(self.work.name))
        self.transcriber = object.__new__(cli.LocalTranscriber)
        self.transcriber.args = SimpleNamespace(model="large-v3", language=None, chunk_seconds=30, resume=True, retry_empty=False)
        self.transcriber.model = Mock()
        self.chunks = [(0, 0, SizedChunk(30 * 16000)), (1, 30, SizedChunk(16000))]
        self.audio = Path(self.work.name) / "fixture.wav"

    def transcribe(self, recording=SOURCE):
        with patch.object(cli, "iter_audio_chunks", return_value=iter(self.chunks)):
            return self.transcriber.transcribe(recording, self.audio, self.checkpoint)

    def test_chunk_offsets_and_decoded_duration_are_preserved(self):
        self.transcriber.model.transcribe.side_effect = [(iter([model_segment()]), SimpleNamespace(language="pl")), (iter([model_segment()]), SimpleNamespace(language="en"))]
        result = self.transcribe()
        self.assertEqual(result["duration"], 31.0)
        self.assertEqual(result["segments"][1]["start"], 30.2)
        self.assertEqual(result["segments"][1]["end"], 30.8)
        self.assertEqual(result["languages"], ["en", "pl"])
        self.assertIsNone(self.transcriber.model.transcribe.call_args.kwargs["language"])
        self.assertTrue(self.transcriber.model.transcribe.call_args.kwargs["vad_filter"])
        self.assertEqual(self.transcriber.model.transcribe.call_args.kwargs["no_speech_threshold"], 0.6)
        self.assertEqual(self.transcriber.model.transcribe.call_args.kwargs["log_prob_threshold"], -1.0)
        self.assertFalse(self.transcriber.model.transcribe.call_args.kwargs["condition_on_previous_text"])

    def test_resume_does_not_repeat_completed_model_chunks(self):
        self.transcriber.model.transcribe.side_effect = [(iter([model_segment()]), SimpleNamespace(language="pl")), (iter([model_segment()]), SimpleNamespace(language="pl"))]
        first = self.transcribe()
        self.transcriber.model.transcribe.reset_mock()
        second = self.transcribe()
        self.transcriber.model.transcribe.assert_not_called()
        self.assertEqual(first, second)

    def test_interrupted_inference_resumes_after_the_last_complete_chunk(self):
        self.transcriber.model.transcribe.side_effect = [(iter([model_segment()]), SimpleNamespace(language="pl")), RuntimeError("model interrupted")]
        with self.assertRaises(RuntimeError):
            self.transcribe()
        self.assertEqual(set(self.checkpoint.get(SOURCE["id"])["chunks"]), {"0"})
        self.transcriber.model.transcribe.reset_mock()
        self.transcriber.model.transcribe.side_effect = [(iter([model_segment()]), SimpleNamespace(language="en"))]
        result = self.transcribe()
        self.transcriber.model.transcribe.assert_called_once()
        self.assertEqual(result["duration"], 31.0)
        self.assertEqual(len(result["segments"]), 2)

    def test_source_change_invalidates_cached_chunks(self):
        self.transcriber.model.transcribe.return_value = ([model_segment()], SimpleNamespace(language="pl"))
        self.transcribe()
        self.transcriber.model.transcribe.reset_mock()
        self.transcribe(dict(SOURCE, md5Checksum="changed"))
        self.assertEqual(self.transcriber.model.transcribe.call_count, 2)

    def test_no_speech_or_unreliable_segments_are_reported_empty(self):
        self.chunks = self.chunks[:1]
        segments = [model_segment(no_speech_prob=0.9, avg_logprob=-2.0), model_segment(avg_logprob=-2.0), model_segment(compression_ratio=4.0), model_segment(words=[SimpleNamespace(probability=0.1)])]
        self.transcriber.model.transcribe.return_value = (segments, SimpleNamespace(language="pl"))
        result = self.transcribe()
        self.assertEqual(result["status"], "empty")
        self.assertEqual(result["segments"], [])
        self.assertEqual(result["rejected_segments"], 4)

    def test_high_no_speech_probability_keeps_confident_decoded_speech(self):
        self.chunks = self.chunks[:1]
        segment = model_segment(no_speech_prob=0.98, avg_logprob=-0.2, compression_ratio=1.7,
                                words=[SimpleNamespace(probability=0.8)])
        self.transcriber.model.transcribe.return_value = ([segment], SimpleNamespace(language="en"))
        result = self.transcribe()
        self.assertEqual(result["status"], "success")
        self.assertEqual([item["text"] for item in result["segments"]], [segment.text])
        self.assertEqual(result["rejected_segments"], 0)

    def test_high_no_speech_probability_with_low_confidence_remains_rejected(self):
        self.chunks = self.chunks[:1]
        segment = model_segment(no_speech_prob=0.98, avg_logprob=-1.2,
                                words=[SimpleNamespace(probability=0.8)])
        self.transcriber.model.transcribe.return_value = ([segment], SimpleNamespace(language="en"))
        result = self.transcribe()
        self.assertEqual(result["status"], "empty")
        self.assertEqual(result["segments"], [])
        self.assertEqual(result["rejected_segments"], 1)

    def test_model_no_speech_result_remains_empty(self):
        self.chunks = self.chunks[:1]
        self.transcriber.model.transcribe.return_value = ([], SimpleNamespace(language="pl"))
        result = self.transcribe()
        self.assertEqual(result["status"], "empty")
        self.assertEqual(result["segments"], [])
        self.assertEqual(result["rejected_segments"], 0)

    def test_old_filter_checkpoint_with_partial_speech_is_reprocessed(self):
        self.chunks = self.chunks[:1]
        old_settings = {"model": "large-v3", "language": None, "chunk_seconds": 30,
                        "engine_version": 2, "vad": "silero-500-250", "overlap_seconds": 2}
        self.checkpoint.set(SOURCE["id"], {
            "fingerprint": cli.source_fingerprint(SOURCE), "settings": old_settings,
            "settings_hash": hashlib.sha256(json.dumps(old_settings, sort_keys=True).encode()).hexdigest(),
            "chunks": {"0": {"duration": 30.0, "segments": [{"start": 0.2, "end": 0.8, "text": "Previously kept speech."}],
                               "language": "en", "rejected": 1}},
        })
        restored = model_segment(text="Confident speech previously discarded.", no_speech_prob=0.98)
        self.transcriber.model.transcribe.return_value = ([restored], SimpleNamespace(language="en"))
        result = self.transcribe()
        self.transcriber.model.transcribe.assert_called_once()
        self.assertEqual([item["text"] for item in result["segments"]], [restored.text])
        self.assertEqual(self.checkpoint.get(SOURCE["id"])["settings"]["engine_version"], 3)

    def test_retry_empty_repeats_only_empty_chunks_while_resuming_speech(self):
        self.transcriber.model.transcribe.side_effect = [([], SimpleNamespace(language="pl")), ([model_segment()], SimpleNamespace(language="en"))]
        self.transcribe()
        self.transcriber.args.retry_empty = True
        self.transcriber.model.transcribe.reset_mock()
        self.transcriber.model.transcribe.side_effect = [([model_segment()], SimpleNamespace(language="pl"))]
        retried = self.transcribe()
        self.transcriber.model.transcribe.assert_called_once()
        self.assertEqual(len(retried["segments"]), 2)


class OverlapMergeTests(unittest.TestCase):
    def test_shared_context_words_appear_once_across_the_seam(self):
        words = [{"start": 28.2, "end": 28.8, "text": " before"},
                 {"start": 28.8, "end": 29.2, "text": " boundary"},
                 {"start": 29.2, "end": 29.8, "text": " after"}]
        segment = {"start": 28.2, "end": 29.8, "text": "before boundary after", "words": words}
        merged = cli.merge_chunk_segments([segment], [dict(segment)], 29.0)
        self.assertEqual(" ".join(item["text"] for item in merged), "before boundary after")
        self.assertEqual(merged[0]["start"], 28.2)
        self.assertEqual(merged[-1]["end"], 29.8)

    def test_words_on_other_side_are_dropped_even_when_segment_has_padding(self):
        words = [{"start": 29.2, "end": 29.8, "text": " after"}]
        previous = {"start": 27.0, "end": 30.0, "text": "after", "words": words}
        current = {"start": 28.0, "end": 30.0, "text": "after", "words": words}
        merged = cli.merge_chunk_segments([previous], [current], 29.0)
        self.assertEqual([item["text"] for item in merged], ["after"])

    def test_empty_next_chunk_does_not_erase_recognized_tail(self):
        previous = {"start": 29.3, "end": 29.9, "text": "recognized tail"}
        self.assertEqual(cli.merge_chunk_segments([previous], [], 29.0), [previous])

    def test_recognized_overlap_is_kept_when_previous_chunk_was_empty(self):
        current = {"start": 28.2, "end": 28.8, "text": "recognized lead"}
        self.assertEqual(cli.merge_chunk_segments([], [current], 29.0), [current])


class FakeArray:
    def __init__(self, values):
        self.values = list(values)

    def __len__(self):
        return len(self.values)

    def __getitem__(self, value):
        return FakeArray(self.values[value]) if isinstance(value, slice) else self.values[value]

    def reshape(self, *shape):
        return self

    def astype(self, dtype):
        return self

    def copy(self):
        return FakeArray(self.values)

    def __truediv__(self, value):
        return FakeArray([sample / value for sample in self.values])


class DecoderChunkTests(unittest.TestCase):
    def test_last_chunk_reuses_two_seconds_without_inflating_duration(self):
        numpy = ModuleType("numpy")
        numpy.float32 = object()
        numpy.concatenate = lambda arrays: FakeArray(sample for array in arrays for sample in array.values)
        av = ModuleType("av")
        av.AudioResampler = Mock()
        av.AudioResampler.return_value.resample.side_effect = lambda frame: [] if frame is None else [frame]
        container = Mock()
        container.streams.audio = [object()]
        first = Mock()
        first.to_ndarray.return_value = FakeArray([0] * (30 * 16000))
        last = Mock()
        last.to_ndarray.return_value = FakeArray([32767] * 16000)
        container.decode.return_value = iter([first, last])
        av.open = Mock()
        av.open.return_value.__enter__ = Mock(return_value=container)
        av.open.return_value.__exit__ = Mock(return_value=None)
        with patch.dict(sys.modules, {"av": av, "numpy": numpy}):
            chunks = list(cli.iter_audio_chunks(Path("unused.wav"), 30))
        self.assertEqual([(index, offset, len(audio) / 16000) for index, offset, audio in chunks], [(0, 0.0, 30.0), (1, 28.0, 3.0)])
        self.assertEqual(chunks[-1][1] + len(chunks[-1][2]) / 16000, 31.0)
        self.assertEqual(chunks[-1][2][0], 0)
        self.assertGreater(chunks[-1][2][-1], 0.99)
        av.AudioResampler.assert_called_once_with(format="s16", layout="mono", rate=16000)


class CliWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.state = Path(self.work.name)
        cli.STOP.clear()
        self.addCleanup(cli.STOP.clear)
        self.folder = file_meta("folder-1", "SpeechRecorder", mimeType=cli.FOLDER_MIME)
        self.drive = Mock()
        self.drive.get_file.return_value = self.folder
        self.drive.contents.return_value = [dict(SOURCE)]

    def invoke(self, extra, model_failure=None):
        with patch.object(cli, "create_session", return_value=object()), \
             patch.object(cli, "DriveClient", return_value=self.drive), \
             patch.object(cli.signal, "signal"), \
             patch.dict(cli.os.environ), \
             patch.object(cli, "LocalTranscriber", side_effect=model_failure) as load_model, \
             patch.object(cli.sys, "stdout", io.StringIO()), \
             patch.object(cli.sys, "stderr", io.StringIO()):
            status = cli.main(["--folder-id", "folder-1", "--state-dir", str(self.state), *extra])
        return status, json.loads((self.state / "report.json").read_text(encoding="utf-8")), load_model

    def test_dry_run_scans_without_audio_download_or_model_loading(self):
        status, report, load_model = self.invoke(["--dry-run", "--retry-empty"])
        self.assertEqual(status, 0)
        self.assertEqual(report["status"], "dry_run")
        self.assertEqual(report["inventory_before"]["original_full_wav"], 1)
        self.assertEqual(report["counts"]["pending"], 1)
        self.assertEqual(report["counts"]["success"], 0)
        self.drive.download.assert_not_called()
        self.drive.publish_text.assert_not_called()
        load_model.assert_not_called()

    def test_model_load_failure_reports_every_selected_file_as_pending(self):
        second = dict(SOURCE, id="wav-2", name="speech_20261012_200000.wav")
        self.drive.contents.return_value = [dict(SOURCE), second]
        status, report, unused = self.invoke([], model_failure=cli.TranscriptionError("model weights missing"))
        self.assertEqual(status, 1)
        self.assertEqual(report["status"], "blocked")
        self.assertEqual(report["counts"]["pending"], 2)
        self.assertEqual({file["id"] for file in report["requires_retry"]}, {SOURCE["id"], second["id"]})
        self.assertEqual(report["counts"]["success"], 0)

    def test_limit_marks_remaining_files_pending_and_selects_shortest(self):
        short = dict(SOURCE, id="wav-short", name="speech_20261012_200000.wav", size="1000")
        self.drive.contents.return_value = [dict(SOURCE), short]
        unused, report, load_model = self.invoke(["--dry-run", "--limit", "1"])
        by_id = {file["id"]: file for file in report["files"]}
        self.assertEqual(by_id[short["id"]]["reason"], "dry_run")
        self.assertEqual(by_id[SOURCE["id"]]["reason"], "limit")
        self.assertEqual(report["counts"]["pending"], 2)
        load_model.assert_not_called()

    def test_duplicate_original_names_are_errors_even_with_one_legacy_transcript(self):
        second = dict(SOURCE, id="wav-2")
        legacy = file_meta("txt-1", NAME + ".transkrypcja.txt", mimeType="text/plain")
        self.drive.contents.return_value = [dict(SOURCE), second, legacy]
        self.drive.read_text.return_value = COMPLETE
        status, report, load_model = self.invoke(["--dry-run"])
        self.assertEqual(status, 2)
        self.assertEqual(report["counts"]["error"], 2)
        self.assertEqual(report["counts"]["skipped"], 0)
        self.drive.publish_text.assert_not_called()
        load_model.assert_not_called()

    def test_private_paths_inside_checkout_are_rejected(self):
        with self.assertRaises(cli.TranscriptionError):
            cli.prepare_args(cli.build_parser(), ["--state-dir", str(SCRIPT.parent / "private-state")])

    def test_skipped_empty_document_cannot_report_complete_collection(self):
        legacy = file_meta("txt-1", NAME + ".transkrypcja.txt", mimeType="text/plain")
        self.drive.contents.return_value = [dict(SOURCE), legacy]
        self.drive.read_text.return_value = "Brak rozpoznanej mowy."
        status, report, load_model = self.invoke([])
        self.assertEqual(status, 2)
        self.assertEqual(report["counts"]["skipped"], 1)
        self.assertEqual(report["counts"]["success"], 0)
        self.assertEqual([item["id"] for item in report["remaining_recordings"]], [SOURCE["id"]])
        load_model.assert_not_called()


class OAuthRefreshTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.args = SimpleNamespace(token=Path(self.work.name) / "token.json", authorize=False)
        self.args.token.write_text("{}", encoding="utf-8")
        self.credentials = Mock()
        self.credentials.valid = False
        self.credentials.refresh_token = "unit-test-placeholder"
        self.credentials.to_json.return_value = '{"test_fixture":true}'
        credentials_module = ModuleType("google.oauth2.credentials")
        credentials_module.Credentials = Mock()
        credentials_module.Credentials.from_authorized_user_file.return_value = self.credentials
        transport_module = ModuleType("google.auth.transport.requests")
        transport_module.Request = Mock()
        transport_module.AuthorizedSession = Mock()
        fake_modules = {name: ModuleType(name) for name in ("google", "google.oauth2", "google.auth", "google.auth.transport")}
        for module in fake_modules.values():
            module.__path__ = []
        fake_modules[credentials_module.__name__] = credentials_module
        fake_modules[transport_module.__name__] = transport_module
        self.transport = transport_module
        self.module_patch = patch.dict(sys.modules, fake_modules)
        self.env_patch = patch.dict(os.environ, {key: "" for key in ("GDRIVE_OAUTH_CLIENT_ID", "GDRIVE_OAUTH_CLIENT_SECRET", "GDRIVE_OAUTH_REFRESH_TOKEN")})
        self.module_patch.start()
        self.env_patch.start()
        self.addCleanup(self.module_patch.stop)
        self.addCleanup(self.env_patch.stop)

    def test_expired_oauth_token_refreshes_and_is_persisted_privately(self):
        self.assertIs(cli.create_session(self.args), self.transport.AuthorizedSession.return_value)
        self.credentials.refresh.assert_called_once_with(self.transport.Request.return_value)
        self.transport.AuthorizedSession.assert_called_once_with(self.credentials)
        self.assertEqual(json.loads(self.args.token.read_text()), {"test_fixture": True})
        self.assertEqual(self.args.token.stat().st_mode & 0o777, 0o600)

    def test_refresh_failure_is_reported_without_leaking_provider_error(self):
        self.credentials.refresh.side_effect = RuntimeError("unit-test-provider-secret")
        with self.assertRaises(cli.TranscriptionError) as caught:
            cli.create_session(self.args)
        self.assertIn("refresh failed", str(caught.exception))
        self.assertNotIn("unit-test-provider-secret", str(caught.exception))
        self.transport.AuthorizedSession.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
