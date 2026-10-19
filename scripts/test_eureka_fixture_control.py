#!/usr/bin/env python3
"""Exercise the fixture launcher with captured commands, separate from real Eureka tests."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
CONTROL = ROOT / ".github/test-fixtures/eureka-server/control.sh"


class EurekaFixtureControlTest(unittest.TestCase):
    """Keep the standalone fixture free of replica peers and bound stale response caching."""

    def test_default_port_matches_instance_hostname_service_url_and_readiness(self) -> None:
        self.assert_standalone_start(None, 8761)

    def test_custom_port_matches_instance_hostname_service_url_and_readiness(self) -> None:
        self.assert_standalone_start("18762", 18762)

    def assert_standalone_start(self, configured_port: str | None, expected_port: int) -> None:
        with tempfile.TemporaryDirectory(prefix="eureka-control-") as temporary:
            root = Path(temporary)
            fixture = root / "fixture"
            target = fixture / "target"
            target.mkdir(parents=True)
            control = fixture / "control.sh"
            shutil.copy2(CONTROL, control)
            jar = target / "eureka-test-server-1.0.0-SNAPSHOT.jar"
            jar.touch()
            calls = root / "calls"
            calls.mkdir()
            binaries = root / "bin"
            binaries.mkdir()
            # Both commands exit without opening sockets. The real shell still expands
            # and passes every argument; readiness waits for the captured Java command.
            command = "#!" + sys.executable + "\n" + textwrap.dedent('''\
                import json
                import os
                from pathlib import Path
                import sys
                import time

                calls = Path(os.environ["EUREKA_FIXTURE_CALLS"])
                name = Path(sys.argv[0]).name
                captured = calls / (name + ".json")
                pending = captured.with_suffix(".tmp")
                pending.write_text(json.dumps(sys.argv[1:]), encoding="utf-8")
                pending.replace(captured)
                if name == "curl":
                    deadline = time.monotonic() + 5
                    while not (calls / "java.json").is_file():
                        if time.monotonic() >= deadline:
                            sys.exit(1)
                        time.sleep(0.01)
                ''')
            for name in ("java", "curl"):
                executable = binaries / name
                executable.write_text(command, encoding="utf-8")
                executable.chmod(0o755)
            environment = os.environ.copy()
            environment.pop("EUREKA_TEST_PORT", None)
            if configured_port is not None:
                environment["EUREKA_TEST_PORT"] = configured_port
            environment["PATH"] = str(binaries) + os.pathsep + environment["PATH"]
            environment["EUREKA_FIXTURE_CALLS"] = str(calls)
            result = subprocess.run(
                ["bash", str(control), "start"], env=environment,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stdout)
            java_args = json.loads((calls / "java.json").read_text(encoding="utf-8"))
            curl_args = json.loads((calls / "curl.json").read_text(encoding="utf-8"))
            self.assertEqual(java_args[:2], ["-jar", str(jar)])
            settings = dict(argument.removeprefix("--").split("=", 1) for argument in java_args[2:])
            self.assertEqual(settings.get("server.address"), "127.0.0.1")
            self.assertEqual(settings.get("server.port"), str(expected_port))
            self.assertEqual(
                settings.get("eureka.instance.hostname"), settings["server.address"],
                "Eureka must identify the loopback service URL as its own instance",
            )
            service_url = urlsplit(settings.get("eureka.client.service-url.defaultZone", ""))
            self.assertEqual(
                (service_url.scheme, service_url.hostname, service_url.port, service_url.path),
                ("http", settings["eureka.instance.hostname"], expected_port, "/eureka/"),
                "The standalone service URL must point to this fixture on the selected port",
            )
            self.assertEqual(settings.get("eureka.client.register-with-eureka"), "false")
            self.assertEqual(settings.get("eureka.client.fetch-registry"), "false")
            self.assertEqual(settings.get("eureka.server.use-read-only-response-cache"), "false")
            self.assertEqual(
                settings.get("eureka.server.response-cache-auto-expiration-in-seconds"), "1",
                "The read-write cache must expire an old load that finishes after registration invalidates it",
            )
            self.assertEqual(curl_args[-1], f"http://127.0.0.1:{expected_port}/eureka/apps")


if __name__ == "__main__":
    unittest.main()
