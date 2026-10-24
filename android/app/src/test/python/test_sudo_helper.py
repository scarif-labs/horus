import array
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest


HELPER = (
    Path(__file__).parents[2]
    / "main/resources/com/scariflabs/horus/terminal/guest/sudo-helper"
)


class SudoHelperLoggingTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.log_path = Path(self.temp_dir.name) / "helper.log"
        self.log_path.touch(mode=0o600)
        os.environ["HORUS_SUDO_LOG"] = str(self.log_path)
        spec = importlib.util.spec_from_file_location("sudo_helper", HELPER)
        self.helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.helper)

    def tearDown(self):
        os.environ.pop("HORUS_SUDO_LOG", None)
        self.temp_dir.cleanup()

    def test_success_audit_and_failure_diagnostic_redact_secrets(self):
        secret = "SUDO_HELPER_SECRET_SENTINEL"
        self.helper.log_success(1000, ["/usr/bin/tool", secret])
        self.helper.log_request_failure(ValueError(secret))

        contents = self.log_path.read_text()
        self.assertIn("uid 1000 ran tool", contents)
        self.assertIn("request failed: ValueError", contents)
        self.assertNotIn(secret, contents)

    def test_success_and_failure_redact_secrets_from_stderr(self):
        secret = "SUDO_HELPER_STDERR_SECRET"
        environment = os.environ.copy()
        environment.pop("HORUS_SUDO_LOG", None)
        helper_socket = Path(self.temp_dir.name) / "helper.sock"
        process = subprocess.Popen(
            [sys.executable, str(HELPER), str(helper_socket), str(os.getuid())],
            stderr=subprocess.PIPE,
            text=True,
            env=environment,
        )
        try:
            deadline = time.monotonic() + 5
            while not helper_socket.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(helper_socket.exists(), "sudo helper did not create its socket")
            time.sleep(0.05)

            with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as connection:
                connection.connect(str(helper_socket))
                request_fds = [os.open(os.devnull, os.O_RDWR) for _ in range(3)]
                try:
                    request = {
                        "argv": ["/usr/bin/true", secret],
                        "env": {},
                        "cwd": self.temp_dir.name,
                        "tty_fds": [],
                    }
                    connection.sendmsg(
                        [json.dumps(request).encode()],
                        [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", request_fds))],
                    )
                finally:
                    for fd in request_fds:
                        os.close(fd)
                self.assertEqual(json.loads(connection.recv(4096)), {"ok": True})
                self.assertEqual(json.loads(connection.recv(4096)), {"exit": 0})

            with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as connection:
                connection.connect(str(helper_socket))
                request_fds = [os.open(os.devnull, os.O_RDWR) for _ in range(3)]
                try:
                    connection.sendmsg(
                        [b"\xff" + secret.encode()],
                        [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", request_fds))],
                    )
                finally:
                    for fd in request_fds:
                        os.close(fd)
                self.assertEqual(json.loads(connection.recv(4096)), {"error": "internal"})
        finally:
            if process.poll() is None:
                process.terminate()
            _, stderr = process.communicate(timeout=5)

        self.assertIn("uid %d ran true" % os.getuid(), stderr)
        self.assertIn("request failed: UnicodeDecodeError", stderr)
        self.assertNotIn(secret, stderr)

    def test_log_size_is_bounded(self):
        for _ in range(2000):
            self.helper.log("bounded diagnostic")

        self.assertLessEqual(self.log_path.stat().st_size, self.helper.MAX_LOG_BYTES)


if __name__ == "__main__":
    unittest.main()
