#!/usr/bin/env python3
"""Keep ownership of a newly created Linux process group until every member stops.

poll/wait observe the direct child's terminal state with waitid(WNOWAIT). The
unreaped leader reserves its PID, so its numeric process-group ID cannot become a
new, unrelated session before cleanup. Only this class may create owned handles;
passing an arbitrary Popen or reaping its child externally is not supported.
"""
from __future__ import annotations

import math
import os
from pathlib import Path
import signal
import subprocess
import time

PROC = Path("/proc")
MAX_PROC_ENTRIES = 65536
MAX_STAT_BYTES = 4096
POLL_SECONDS = 0.02
NON_EXECUTING_STATES = frozenset(("Z", "X", "x"))


class OwnedProcessFailure(RuntimeError):
    """Fixed lifecycle diagnostics with no command, environment or process output."""


def positive_timeout(value: float) -> float:
    if not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ValueError("Owned process timeout must be positive and finite")
    return float(value)


def process_stat(pid: int) -> tuple[str, int, int, int] | None:
    """Read state, process group, session and start tick, including a zombie leader."""
    try:
        with (PROC / str(pid) / "stat").open("rb") as stream:
            value = stream.read(MAX_STAT_BYTES + 1)
    except (FileNotFoundError, ProcessLookupError):
        return None
    except OSError:
        raise OwnedProcessFailure("Owned process state could not be inspected") from None
    try:
        if len(value) > MAX_STAT_BYTES or int(value.split(b" ", 1)[0]) != pid:
            raise ValueError
        fields = value[value.rindex(b")") + 2:].split()
        return fields[0].decode("ascii"), int(fields[2]), int(fields[3]), int(fields[19])
    except (ValueError, IndexError, UnicodeError):
        raise OwnedProcessFailure("Owned process state was invalid") from None


class OwnedProcess:
    """A new session whose leader is reaped only after bounded whole-group cleanup."""

    def __init__(self, arguments: list[str], **options):
        if options.pop("start_new_session", True) is not True:
            raise ValueError("Owned processes require a new session")
        if not hasattr(os, "WNOWAIT") or not hasattr(os, "waitid"):
            raise OwnedProcessFailure("Owned process groups require Linux waitid and procfs")
        try:
            visible_pid = int((PROC / "self/stat").read_bytes().split(b" ", 1)[0])
        except (OSError, ValueError):
            raise OwnedProcessFailure("Owned process groups require matching Linux procfs") from None
        if visible_pid != os.getpid():
            raise OwnedProcessFailure("Owned process groups require matching Linux PID and procfs namespaces")
        self._process = subprocess.Popen(arguments, start_new_session=True, **options)
        self.pid = self._process.pid
        self._returncode = None
        self._closed = False
        try:
            leader = process_stat(self.pid)
            if leader is None or leader[1:3] != (self.pid, self.pid):
                raise OwnedProcessFailure("New process group ownership could not be established")
            self._leader_start = leader[3]
        except BaseException:
            # No caller has received the child and no poll/wait has reaped it.
            # Verify that it is still our new session before signalling the group.
            try:
                os.waitid(os.P_PID, self.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if os.getpgid(self.pid) == self.pid and os.getsid(self.pid) == self.pid:
                    os.killpg(self.pid, signal.SIGKILL)
                else:
                    self._process.kill()
            except ProcessLookupError:
                pass
            finally:
                self._process.wait(timeout=1)
            raise

    def poll(self) -> int | None:
        if self._closed:
            return self._returncode
        try:
            status = os.waitid(os.P_PID, self.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        except ChildProcessError:
            raise OwnedProcessFailure("Owned process leader was reaped outside its owner") from None
        if status is not None:
            self._returncode = status.si_status if status.si_code == os.CLD_EXITED else -status.si_status
        return self._returncode

    def wait(self, timeout: float) -> int:
        deadline = time.monotonic() + positive_timeout(timeout)
        while True:
            code = self.poll()
            if code is not None:
                return code
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired("owned process", timeout)
            time.sleep(min(POLL_SECONDS, remaining))

    def _verify_leader(self) -> None:
        self.poll()  # WNOWAIT verifies that the direct child has not been externally reaped.
        leader = process_stat(self.pid)
        if leader is None or leader[1:] != (self.pid, self.pid, self._leader_start):
            raise OwnedProcessFailure("Owned process group identity changed before cleanup")

    def _signal_group(self, number: int) -> None:
        self._verify_leader()
        try:
            os.killpg(self.pid, number)
        except ProcessLookupError:
            # A vanished group is not evidence of a different, signalable owner.
            pass

    def _running_members(self, deadline: float) -> bool | None:
        self._verify_leader()
        try:
            with os.scandir(PROC) as entries:
                for count, entry in enumerate(entries, 1):
                    if count > MAX_PROC_ENTRIES:
                        raise OwnedProcessFailure("Process inspection exceeded its bounded entry budget")
                    if time.monotonic() >= deadline:
                        return None
                    if not entry.name.isdecimal():
                        continue
                    state = process_stat(int(entry.name))
                    if state is not None and state[1:3] == (self.pid, self.pid):
                        if state[0] not in NON_EXECUTING_STATES:
                            return True
        except OSError:
            raise OwnedProcessFailure("Owned process group could not be inspected") from None
        return False

    def _await_inactive_group(self, timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        while True:
            running = self._running_members(deadline)
            if running is False:
                return True
            remaining = deadline - time.monotonic()
            if running is None or remaining <= 0:
                return False
            time.sleep(min(POLL_SECONDS, remaining))

    def stop(self, term_timeout: float, kill_timeout: float) -> None:
        term_timeout = positive_timeout(term_timeout)
        kill_timeout = positive_timeout(kill_timeout)
        if self._closed:
            return
        self._signal_group(signal.SIGTERM)
        if not self._await_inactive_group(term_timeout):
            self._signal_group(signal.SIGKILL)
            if not self._await_inactive_group(kill_timeout):
                raise OwnedProcessFailure("Owned process group remained active after its stop budget")
        # Zombies retain an exit record but execute no code. Reap only our direct
        # child here; an owning subreaper or init reaps any orphaned descendants.
        self._returncode = self._process.wait(timeout=kill_timeout)
        self._closed = True


def stop_owned(process: OwnedProcess | None, term_timeout: float, kill_timeout: float) -> None:
    if process is None:
        return
    if not isinstance(process, OwnedProcess):
        raise OwnedProcessFailure("Cannot signal a process group without an owned creation handle")
    process.stop(term_timeout, kill_timeout)
