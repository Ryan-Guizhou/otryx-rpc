#!/usr/bin/env python3
"""Real, bounded Linux process regressions; no sockets, backends or JVMs are used."""
from __future__ import annotations

import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import etcd_security_fixture as fixture
import owned_process_group as ownership
import run_quickstart_mtls_e2e as runner

CHILD_SECONDS = 30
WAIT_SECONDS = 5
TERM_SECONDS = 0.1
KILL_SECONDS = 2
GET_CHILD_SUBREAPER = 37
SET_CHILD_SUBREAPER = 36

FORK_PROGRAM = """
import os
from pathlib import Path
import signal
import sys
import time

mode, result = sys.argv[1:]
reader, writer = os.pipe()
child = os.fork()
if child == 0:
    os.close(reader)
    if mode == 'ignore-term':
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    os.write(writer, b'R')
    os.close(writer)
    if mode == 'zombie':
        os._exit(0)
    time.sleep(30)
    os._exit(0)
os.close(writer)
assert os.read(reader, 1) == b'R'
os.close(reader)
if mode == 'zombie':
    os.waitid(os.P_PID, child, os.WEXITED | os.WNOWAIT)
pending = Path(result + '.pending')
pending.write_text(str(child), encoding='ascii')
pending.replace(result)
if mode != 'ignore-term':
    os._exit(0)
time.sleep(30)
"""


@unittest.skipUnless(sys.platform == "linux", "Owned process groups require Linux")
class OwnedProcessGroupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.libc = ctypes.CDLL(None, use_errno=True)
        previous = ctypes.c_int()
        if cls.libc.prctl(GET_CHILD_SUBREAPER, ctypes.byref(previous), 0, 0, 0) != 0:
            raise RuntimeError("Could not inspect the test's child-subreaper setting")
        cls.previous_subreaper = previous.value
        if cls.libc.prctl(SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0:
            raise RuntimeError("Could not make the test responsible for orphan reaping")
        cls.records = []

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.libc.prctl(SET_CHILD_SUBREAPER, cls.previous_subreaper, 0, 0, 0) != 0:
            raise RuntimeError("Could not restore the test's child-subreaper setting")
        print("Owned process evidence: " + json.dumps(cls.records, sort_keys=True), flush=True)

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="otryx-owned-process-test-")
        self.directory = Path(self.temporary.name)
        self.groups = []
        self.descendants = set()

    def tearDown(self) -> None:
        failures = []
        for process in reversed(self.groups):
            try:
                ownership.stop_owned(process, TERM_SECONDS, KILL_SECONDS)
            except Exception as error:
                failures.append(type(error).__name__)
        # Never waitpid(-1): only descendants explicitly created by this test are
        # reaped, after their recorded group leaders have been stopped.
        for pid in self.descendants.copy():
            try:
                status = os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if status is None:
                    os.kill(pid, signal.SIGKILL)
                self.reap_descendant(pid)
            except ChildProcessError:
                failures.append("MissingOwnedDescendant")
        self.temporary.cleanup()
        self.assertEqual(failures, [], "The process regression itself must leave no owned children")

    def start(self, program: str, *arguments: str) -> ownership.OwnedProcess:
        process = ownership.OwnedProcess([sys.executable, "-c", program, *arguments],
                                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, start_new_session=True)
        self.groups.append(process)
        return process

    def fork_group(self, mode: str = "exited-leader") -> tuple[ownership.OwnedProcess, int]:
        result = self.directory / (str(len(self.groups)) + ".pid")
        process = self.start(FORK_PROGRAM, mode, str(result))
        deadline = time.monotonic() + WAIT_SECONDS
        while not result.exists():
            if time.monotonic() >= deadline:
                self.fail("The no-network child did not reach its deterministic ready barrier")
            time.sleep(0.01)
        descendant = int(result.read_text(encoding="ascii"))
        self.descendants.add(descendant)
        state = ownership.process_stat(descendant)
        self.assertIsNotNone(state)
        self.assertEqual(state[1:3], (process.pid, process.pid))
        self.records.append({"test": self._testMethodName, "leaderPid": process.pid,
                             "descendantPid": descendant, "leaderStartTick": process._leader_start})
        if mode != "ignore-term":
            self.assertEqual(process.wait(WAIT_SECONDS), 0)
            self.assertEqual(ownership.process_stat(process.pid)[0], "Z")
        return process, descendant

    def assert_inactive(self, pid: int) -> None:
        state = ownership.process_stat(pid)
        self.assertIsNotNone(state, "The test subreaper must retain its own child's exit record")
        self.assertIn(state[0], ownership.NON_EXECUTING_STATES)

    def reap_descendant(self, pid: int) -> int:
        deadline = time.monotonic() + WAIT_SECONDS
        while True:
            result, status = os.waitpid(pid, os.WNOHANG)
            if result == pid:
                self.descendants.remove(pid)
                return os.waitstatus_to_exitcode(status)
            if time.monotonic() >= deadline:
                self.fail("The test subreaper could not reclaim an owned child's exit record")
            time.sleep(0.01)

    def test_etcd_stop_reclaims_child_after_leader_exit(self) -> None:
        process, descendant = self.fork_group()
        fixture.stop_owned(process)
        self.assert_inactive(descendant)
        self.assertIsNone(ownership.process_stat(process.pid))
        self.assertEqual(self.reap_descendant(descendant), -signal.SIGTERM)
        fixture.stop_owned(process)

    def test_scenario_stop_reclaims_child_before_dropping_ownership(self) -> None:
        process, descendant = self.fork_group()
        scenario = object.__new__(runner.SecurityScenario)
        scenario.processes = [process]
        scenario.stop(process)
        self.assert_inactive(descendant)
        self.assertNotIn(process, scenario.processes)
        self.assertEqual(self.reap_descendant(descendant), -signal.SIGTERM)
        scenario.stop(process)

    def test_term_exited_leader_does_not_hide_child_requiring_kill(self) -> None:
        process, descendant = self.fork_group("ignore-term")
        started = time.monotonic()
        ownership.stop_owned(process, TERM_SECONDS, KILL_SECONDS)
        elapsed = time.monotonic() - started
        self.assertEqual(process.poll(), -signal.SIGTERM)
        self.assert_inactive(descendant)
        self.assertEqual(self.reap_descendant(descendant), -signal.SIGKILL)
        self.assertLess(elapsed, TERM_SECONDS + KILL_SECONDS + 1)
        self.records[-1]["cleanupMillis"] = round(elapsed * 1000)

    def test_zombies_do_not_consume_the_term_or_kill_budget(self) -> None:
        process, descendant = self.fork_group("zombie")
        self.assert_inactive(descendant)
        with patch.object(ownership.os, "killpg", wraps=os.killpg) as signals:
            ownership.stop_owned(process, KILL_SECONDS, KILL_SECONDS)
        self.assertEqual([call.args[1] for call in signals.call_args_list], [signal.SIGTERM])
        self.assertEqual(self.reap_descendant(descendant), 0)
        with patch.object(ownership.os, "killpg") as repeated:
            ownership.stop_owned(process, TERM_SECONDS, KILL_SECONDS)
        repeated.assert_not_called()

    def test_timeout_does_not_reap_or_disown_the_running_leader(self) -> None:
        process = self.start(f"import time; time.sleep({CHILD_SECONDS})")
        with self.assertRaises(subprocess.TimeoutExpired) as failure:
            process.wait(0.03)
        self.assertEqual(failure.exception.cmd, "owned process")
        self.assertIsNone(process.poll())
        self.assertEqual(ownership.process_stat(process.pid)[1:3], (process.pid, process.pid))

    def test_arbitrary_popen_cannot_be_used_as_a_group_ownership_token(self) -> None:
        process = subprocess.Popen([sys.executable, "-c", f"import time; time.sleep({CHILD_SECONDS})"],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            with self.assertRaises(ownership.OwnedProcessFailure):
                ownership.stop_owned(process, TERM_SECONDS, KILL_SECONDS)
            self.assertIsNone(process.poll())
        finally:
            process.kill()
            process.wait(timeout=KILL_SECONDS)

    def test_external_reaping_loses_ownership_and_never_signals_a_reusable_pid(self) -> None:
        process = self.start("import sys; sys.exit(17)")
        self.assertEqual(process.wait(WAIT_SECONDS), 17)
        pid, status = os.waitpid(process.pid, 0)
        self.assertEqual(pid, process.pid)
        # Deliberate contract violation: the numeric PID is now reusable. Keep
        # Popen's destructor from attempting a second reap of this test child.
        process._process.returncode = os.waitstatus_to_exitcode(status)
        self.groups.remove(process)
        with patch.object(ownership.os, "killpg") as signals:
            with self.assertRaises(ownership.OwnedProcessFailure):
                ownership.stop_owned(process, TERM_SECONDS, KILL_SECONDS)
        signals.assert_not_called()

    def test_process_lookup_is_idempotent_only_when_the_group_is_actually_inactive(self) -> None:
        exited = self.start("pass")
        self.assertEqual(exited.wait(WAIT_SECONDS), 0)
        with patch.object(ownership.os, "killpg", side_effect=ProcessLookupError):
            ownership.stop_owned(exited, TERM_SECONDS, KILL_SECONDS)
        running = self.start(f"import time; time.sleep({CHILD_SECONDS})")
        with patch.object(ownership.os, "killpg", side_effect=ProcessLookupError):
            with self.assertRaises(ownership.OwnedProcessFailure):
                ownership.stop_owned(running, 0.02, 0.02)
        self.assertIsNone(running.poll())

    def test_scenario_close_attempts_other_groups_and_retains_files_on_stop_failure(self) -> None:
        successful = self.start(f"import time; time.sleep({CHILD_SECONDS})")
        blocked = self.start(f"import time; time.sleep({CHILD_SECONDS})")
        scenario = object.__new__(runner.SecurityScenario)
        scenario.processes = [successful, blocked]
        scenario.work = self.directory / "scenario"
        scenario.work.mkdir(mode=0o700)
        scenario.diagnostics = None
        actual_stop = ownership.stop_owned

        def fail_one(process, term_timeout, kill_timeout):
            if process is blocked:
                raise ownership.OwnedProcessFailure("Injected bounded cleanup failure")
            actual_stop(process, term_timeout, kill_timeout)

        with patch.object(runner, "stop_process_group", side_effect=fail_one):
            with self.assertRaises(ownership.OwnedProcessFailure):
                scenario.close(True)
        self.assertEqual(scenario.processes, [blocked])
        self.assertIsNotNone(successful.poll())
        self.assertIsNone(blocked.poll())
        self.assertTrue(scenario.work.exists())
        scenario.close(True)
        self.assertFalse(scenario.work.exists())

    def test_fixture_close_retains_private_files_until_all_owners_confirm_stop(self) -> None:
        process = self.start(f"import time; time.sleep({CHILD_SECONDS})")
        instance = fixture.EtcdSecurityFixture("unused-no-network-binary")
        instance.directory = self.directory / "fixture"
        instance.directory.mkdir(mode=0o700)
        instance.log = (instance.directory / "owned.log").open("wb")
        instance.process = process
        with patch.object(fixture, "stop_process_group", side_effect=ownership.OwnedProcessFailure("Stop failed")):
            with self.assertRaises(ownership.OwnedProcessFailure):
                instance.__exit__(None, None, None)
        self.assertTrue(instance.log.closed)
        self.assertTrue(instance.directory.exists())
        failed_owner = ownership.OwnedProcessFailure("Another owner could not confirm cleanup")
        instance.__exit__(type(failed_owner), failed_owner, None)
        self.assertTrue(instance.directory.exists())
        self.assertIsNotNone(process.poll())
        instance.__exit__(None, None, None)
        self.assertFalse(instance.directory.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
