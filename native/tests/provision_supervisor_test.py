#!/usr/bin/env python3
"""Disposable process/FD fixtures for the exact production supervisor."""
import fcntl
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

SUPERVISOR = str(Path(sys.argv.pop(1)).resolve())

class SupervisorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='horus-process-')
        self.base = Path(self.tmp.name)
        self.processes = []
        self.pids = []
        self.identities = {}

    def tearDown(self):
        for p in self.processes:
            if p.poll() is None:
                p.terminate()
                try: p.wait(5)
                except subprocess.TimeoutExpired: p.kill(); p.wait()
        for pid in self.pids:
            # Do not signal a later process if a reaped fixture PID was reused.
            if self.identities.get(pid) is None or self.identity(pid) != self.identities[pid]:
                continue
            try: os.kill(pid, signal.SIGKILL)
            except ProcessLookupError: pass
        self.tmp.cleanup()

    def start(self, script):
        p = subprocess.Popen([SUPERVISOR, '/bin/sh', '-c', script], cwd=self.base,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.processes.append(p)
        return p

    @staticmethod
    def identity(pid):
        try: return Path(f'/proc/{pid}/stat').read_text().rpartition(')')[2].split()[19]
        except (FileNotFoundError, ProcessLookupError): return None

    def pid(self, name='child'):
        path = self.base / name
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if path.exists() and path.read_text().strip():
                pid = int(path.read_text()); self.pids.append(pid)
                self.identities[pid] = self.identity(pid)
                return pid
            time.sleep(.01)
        self.fail('fixture never became ready')

    def gone(self, pid):
        self.assertFalse(Path(f'/proc/{pid}').exists(), f'pid {pid} was not reaped')

    def lock_available(self):
        with (self.base / 'lock').open('w') as f:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_baseline_parent_only_termination_leaves_lock(self):
        p = subprocess.Popen(['/bin/sh', '-c',
            'exec 9>lock; flock 9; sleep 60 & echo $! >child; wait'], cwd=self.base)
        self.processes.append(p)
        pid = self.pid(); p.terminate(); p.wait(3)
        self.assertTrue(Path(f'/proc/{pid}').exists())
        with (self.base / 'lock').open('w') as f:
            with self.assertRaises(BlockingIOError):
                fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print(f'BEFORE parent={p.pid} status={p.returncode} child={pid} alive=true lock=held')

    def test_success_and_exit_status(self):
        self.assertEqual(0, self.start('exit 0').wait(3))
        self.assertEqual(37, self.start('exit 37').wait(3))

    def test_inherited_sigchld_ignore_cannot_discard_owned_children(self):
        p = subprocess.Popen([SUPERVISOR, '/bin/sh', '-c', 'exit 37'],
            preexec_fn=lambda: signal.signal(signal.SIGCHLD, signal.SIG_IGN))
        self.processes.append(p)
        self.assertEqual(37, p.wait(3))

    def test_term_releases_inherited_lock_and_reaps(self):
        p = self.start('exec 9>lock; flock 9; sleep 60 & echo $! >child; wait')
        pid = self.pid(); p.terminate()
        self.assertEqual(143, p.wait(5)); self.gone(pid); self.lock_available()
        print(f'AFTER supervisor={p.pid} status={p.returncode} child={pid} reaped lock=released')

    def test_term_ignoring_tree_requires_escalation(self):
        p = self.start("trap '' TERM; sleep 60 & echo $! >child; wait")
        pid = self.pid(); start = time.monotonic(); p.terminate()
        self.assertEqual(143, p.wait(5)); self.gone(pid)
        self.assertGreater(time.monotonic() - start, 1.8)

    def test_graceful_shutdown_runs_trap(self):
        p = self.start("trap 'echo graceful >done; exit 0' TERM; echo $$ >child; while :; do sleep .05; done")
        self.pid(); p.terminate(); self.assertEqual(143, p.wait(5))
        self.assertEqual('graceful', (self.base / 'done').read_text().strip())

    def test_parent_exit_cleans_orphan_before_reporting_success(self):
        p = self.start('sleep 60 & echo $! >child; exit 0')
        pid = self.pid(); self.assertEqual(0, p.wait(5)); self.gone(pid)

    def test_new_session_orphan_is_adopted(self):
        p = self.start("setsid sh -c 'trap \"\" TERM; echo $$ >child; exec sleep 60' & while [ ! -s child ]; do sleep .01; done; exit 8")
        pid = self.pid(); self.assertEqual(8, p.wait(5)); self.gone(pid)

    def test_adopted_orphan_gets_time_for_graceful_trap(self):
        p = self.start("setsid sh -c 'trap \"sleep .2; echo graceful >done; exit 0\" TERM; echo $$ >child; while :; do sleep .1; done' & while [ ! -s child ]; do sleep .01; done; exit 0")
        pid = self.pid(); self.assertEqual(0, p.wait(6)); self.gone(pid)
        self.assertEqual('graceful', (self.base / 'done').read_text().strip())

    def test_immediate_cancel_and_retry(self):
        for i in range(20):
            p = self.start('sleep 60 & wait'); p.terminate(); p.wait(5)
            self.assertNotEqual(0, p.returncode)
            self.assertEqual(0, self.start('exec 9>lock; flock -n 9; exit $?').wait(3))

    def test_unrelated_session_survives_concurrent_supervisors(self):
        unrelated = subprocess.Popen(['sleep', '60'])
        self.processes.append(unrelated)
        first = self.start('sleep 60 & echo $! >child; wait'); pid = self.pid()
        second = self.start('sleep 60 & echo $! >second; wait'); other = self.pid('second')
        first.terminate(); first.wait(5); self.gone(pid)
        self.assertIsNone(second.poll()); self.assertIsNone(unrelated.poll())
        self.assertTrue(Path(f'/proc/{other}').exists())
        second.terminate(); second.wait(5); self.gone(other)

    def test_android_parent_death_signal(self):
        # Simulate the app dying without onDestroy. The supervisor, not guest,
        # receives PDEATHSIG. No user installation participates.
        parent = subprocess.Popen([sys.executable, '-c',
            'import subprocess,time,sys; p=subprocess.Popen(sys.argv[1:]); time.sleep(60)',
            SUPERVISOR, '/bin/sh', '-c', f'exec 9>"{self.base}/lock"; flock 9; sleep 60 & echo $! >"{self.base}/child"; wait'])
        self.processes.append(parent)
        pid = self.pid(); parent.kill(); parent.wait()
        deadline = time.monotonic() + 5
        while Path(f'/proc/{pid}').exists() and time.monotonic() < deadline: time.sleep(.02)
        self.gone(pid); self.lock_available()

if __name__ == '__main__': unittest.main(verbosity=2)
