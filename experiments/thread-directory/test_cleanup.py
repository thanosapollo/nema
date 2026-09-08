# /// script
# requires-python = ">=3.11"
# dependencies = ["slixmpp==1.12.0"]
# ///
"""Deterministic lifecycle regressions; opt-in real Docker CLI faults."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import proof


class CleanupTests(unittest.TestCase):
    def exercise_launch_failure(self, failure):
        state = {}
        calls = []
        fixtures = []

        def command(*args, timeout=30):
            calls.append((args, timeout))
            if args[0] == "openssl":
                return ""
            if args[:2] == ("docker", "run"):
                mount = args[args.index("--mount") + 1]
                fixtures.append(Path(mount.split("src=", 1)[1].split(",dst=", 1)[0]))
                state["id"] = "a" * 64
                state["label"] = args[args.index("--label") + 1].split("=", 1)[1]
                raise failure
            if args[1:3] == ("container", "ls"):
                return state.get("id", "")
            if args[1] == "inspect":
                return json.dumps({proof.OWNER_LABEL: state["label"]})
            if args[1] == "rm":
                self.assertEqual(args[-1], "a" * 64)
                state.clear()
                return "a" * 64
            self.fail(f"Unexpected command {args}")

        with patch.object(proof, "command", side_effect=command):
            with self.assertRaises(type(failure)):
                proof.main()
            self.assertEqual(len(fixtures), 1)
            self.assertFalse(fixtures[0].exists())
        self.assertFalse(state)
        self.assertEqual(calls[-1][0][1:3], ("container", "ls"))
        self.assertIn("id=" + "a" * 64, calls[-1][0])
        self.assertTrue(all(timeout <= 30 for _, timeout in calls))

    def test_creation_then_cli_timeout(self):
        self.exercise_launch_failure(subprocess.TimeoutExpired(["docker", "run"], 30))

    def test_creation_then_nonzero_start(self):
        self.exercise_launch_failure(RuntimeError("injected nonzero start"))

    def test_ambiguous_launch_initial_absence_preserves_fixture(self):
        for failure in (subprocess.TimeoutExpired(["docker", "run"], 30),
                        RuntimeError("injected nonzero launch")):
            with self.subTest(failure=type(failure).__name__):
                calls = []
                output = io.StringIO()
                fixture = None

                def command(*args, timeout=30):
                    calls.append((args, timeout))
                    if args[1] == "run":
                        raise failure
                    if args[1:3] == ("container", "ls"):
                        return ""  # Create may still be in flight after this snapshot.
                    self.fail(f"Unexpected command {args}")

                try:
                    with patch.object(proof, "command", side_effect=command):
                        with contextlib.redirect_stderr(output):
                            with self.assertRaisesRegex(proof.CleanupError, "launch not acknowledged"):
                                with proof.server_fixture() as (fixture, server):
                                    server.launch(proof.IMAGE)
                    assert fixture is not None
                    self.assertTrue(fixture.is_dir())
                    self.assertIsNone(server.identifier)
                    self.assertIn(server.name, output.getvalue())
                    self.assertIn(str(fixture), output.getvalue())
                    self.assertIn("unresolved", output.getvalue())
                    self.assertEqual(len(calls), 2)  # No polling window can prove completion.
                    self.assertEqual(calls[-1][1], 10)
                finally:
                    # No daemon request occurred in this model; only the test owns this directory.
                    if fixture is not None and fixture.exists():
                        fixture.rmdir()

    def test_acknowledged_launch_with_pinned_id_already_removed(self):
        calls = []

        def command(*args, timeout=30):
            calls.append(args)
            if args[1] == "run":
                return "d" * 64
            if args[1] == "inspect":
                return json.dumps({proof.OWNER_LABEL: server.owner})
            if args[1:3] == ("container", "ls"):
                return "" if server.identifier else "d" * 64
            self.fail(f"Unexpected command {args}")

        with patch.object(proof, "command", side_effect=command):
            with proof.server_fixture() as (fixture, server):
                self.assertEqual(server.launch(proof.IMAGE), "d" * 64)
                self.assertTrue(server.acknowledged)
        self.assertFalse(fixture.exists())
        self.assertIn("id=" + "d" * 64, calls[-1])

    def test_foreign_collision_preserved(self):
        def command(*args, timeout=30):
            if args[1] == "run":
                raise RuntimeError("name collision")
            if args[1:3] == ("container", "ls"):
                return "b" * 64
            if args[1] == "inspect":
                return json.dumps({proof.OWNER_LABEL: "foreign-owner"})
            self.fail(f"Must not remove foreign container: {args}")

        output = io.StringIO()
        fixture = None
        try:
            with patch.object(proof, "command", side_effect=command):
                with contextlib.redirect_stderr(output):
                    with self.assertRaisesRegex(proof.CleanupError, "launch not acknowledged"):
                        with proof.server_fixture() as (fixture, server):
                            server.launch(proof.IMAGE)
            assert fixture is not None
            self.assertTrue(fixture.is_dir())
            self.assertIn(str(fixture), output.getvalue())
        finally:
            # This mock never contacted Docker; remove only its exact empty fixture.
            if fixture is not None and fixture.exists():
                fixture.rmdir()

    def test_removal_failure_always_reads_back_and_retains_unresolved_fixture(self):
        for removed in (False, True):
            with self.subTest(removed=removed):
                calls = []
                with contextlib.redirect_stderr(io.StringIO()):
                    manager = proof.server_fixture()
                    fixture, server = manager.__enter__()
                    server.attempted = True
                    server.identifier = "c" * 64

                    def command(*args, timeout=30):
                        calls.append((args, timeout))
                        if args[1] == "inspect":
                            return json.dumps({proof.OWNER_LABEL: server.owner})
                        if args[1] == "rm":
                            raise subprocess.TimeoutExpired(["docker", "rm"], timeout)
                        if args[1:3] == ("container", "ls"):
                            return "" if removed and any(c[0][1] == "rm" for c in calls) else server.identifier
                        self.fail(f"Unexpected command {args}")

                    try:
                        with patch.object(proof, "command", side_effect=command):
                            if removed:
                                manager.__exit__(None, None, None)
                                self.assertFalse(fixture.exists())
                            else:
                                with self.assertRaisesRegex(proof.CleanupError, "c" * 64):
                                    manager.__exit__(None, None, None)
                                self.assertTrue(fixture.is_dir())
                        self.assertEqual(calls[-1][0][1:3], ("container", "ls"))
                        self.assertTrue(all(timeout == 10 for _, timeout in calls))
                    finally:
                        if fixture.exists():
                            fixture.rmdir()

    def test_unreachable_daemon_preserves_fixture_and_reports_target(self):
        output = io.StringIO()
        with patch.object(proof, "command", side_effect=RuntimeError("daemon unreachable")):
            with contextlib.redirect_stderr(output):
                with self.assertRaises(proof.CleanupError):
                    with proof.server_fixture() as (fixture, server):
                        server.launch(proof.IMAGE)
        try:
            self.assertTrue(fixture.is_dir())
            self.assertIn(server.name, output.getvalue())
            self.assertIn(str(fixture), output.getvalue())
            self.assertIn("unresolved", output.getvalue())
        finally:
            fixture.rmdir()


@unittest.skipUnless(os.environ.get("NEMA_CLEANUP_DOCKER_TESTS") == "1", "opt-in real Docker")
class DockerCleanupTests(unittest.TestCase):
    """Run only under the shared proof flock. Never run a task XMPP server."""

    def test_real_post_creation_faults_and_foreign_collision(self):
        real_command = proof.command
        for fault in ("timeout", "nonzero", "collision"):
            with self.subTest(fault=fault):
                captured = {}
                foreign = proof.OwnedContainer()
                fixture = None
                collision_rejected = False

                def launch_boundary(*args, timeout=30):
                    nonlocal collision_rejected
                    if args[:2] != ("docker", "run"):
                        return real_command(*args, timeout=timeout)
                    if fault == "collision":
                        foreign.name = args[args.index("--name") + 1]
                        with patch.object(proof, "command", real_command):
                            captured["id"] = foreign.launch(
                                "--cpus=0.25", "--memory=512m", "--pids-limit=64",
                                "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                                "--entrypoint=/bin/sleep", proof.IMAGE, "120")
                        try:
                            return real_command(*args, timeout=timeout)  # genuine name collision
                        except RuntimeError as error:
                            # Only the test owner uses the actual rejection as recovery evidence.
                            self.assertIn("Conflict", str(error))
                            self.assertIn(foreign.name, str(error))
                            collision_rejected = True
                            raise
                    creation = ("docker", "create", *args[3:]) if fault == "nonzero" else args
                    captured["id"] = real_command(*creation, timeout=timeout)
                    self.assertEqual(real_command("docker", "inspect", "--format", "{{.State.Status}}",
                                                  captured["id"]), "created" if fault == "nonzero" else "running")
                    if fault == "timeout":
                        raise subprocess.TimeoutExpired(["docker", "run"], timeout)
                    raise RuntimeError("injected nonzero CLI start result after real creation")

                try:
                    with patch.object(proof, "command", side_effect=launch_boundary):
                        expected = (proof.CleanupError if fault == "collision" else
                                    subprocess.TimeoutExpired if fault == "timeout" else RuntimeError)
                        with self.assertRaises(expected):
                            with proof.server_fixture() as (fixture, server):
                                server.launch(
                                    "--cpus=0.25", "--memory=512m", "--pids-limit=64",
                                    "--network=none", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                                    "--entrypoint=/bin/sleep", proof.IMAGE, "120")
                    assert fixture is not None
                    self.assertEqual(fixture.exists(), fault == "collision")
                    remaining = real_command("docker", "container", "ls", "--all", "--no-trunc",
                                             "--filter", "id=" + captured["id"], "--format", "{{.ID}}", timeout=10)
                    self.assertEqual(remaining, captured["id"] if fault == "collision" else "")
                    print(f"DOCKER VERIFIED {fault}: exact ID {'preserved' if remaining else 'absent'}", flush=True)
                finally:
                    foreign.cleanup()
                    if fault == "collision" and collision_rejected and fixture is not None:
                        # Genuine rejection completed and foreign exact ID removal was verified.
                        # This test owns the empty fixture: no glob or production recovery guess.
                        fixture.rmdir()
                        self.assertFalse(fixture.exists())
                        print("DOCKER VERIFIED collision: exact private fixture removed by test owner", flush=True)
                    if fault != "collision" and captured.get("id"):
                        # The production cleanup must already have succeeded; rescue only our exact owner.
                        server.cleanup()


if __name__ == "__main__":
    unittest.main()
