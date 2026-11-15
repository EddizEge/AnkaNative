"""The keys that open the in-game panel on Android (PadMenu.java with android/app/src/jvmtest's PadMenuJvmTest): a gamepad's Home / guide key (KEYCODE_BUTTON_MODE),
consumed both ways because Generic.kcm falls back to HOME for an unhandled one, and, when the player turns it on, both stick buttons together for a controller whose
Home key the system takes (KEYCODE_HOME). The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/PadMenu.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PadMenuJvmTest.java'
ACTIVITY = REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java'


class Wiring(unittest.TestCase):
    def test_constants_match_android(self):
        # KeyEvent.KEYCODE_BUTTON_MODE, _BUTTON_THUMBL, _BUTTON_THUMBR
        self.assertIn('BUTTON_MODE = 110, THUMBL = 106, THUMBR = 107', SRC.read_text(encoding='utf-8'))

    def test_activity_asks_it_first(self):
        t = ACTIVITY.read_text(encoding='utf-8')
        d = t[t.index('public boolean dispatchKeyEvent(KeyEvent e)'):]
        d = d[:d.index('\n    }\n')]
        self.assertIn('padMenu.key(', d)
        # before the open panel, the pause and the host see the key: none of them swallows Home or the stick buttons first
        for later in ('panel.isOpen()', 'if (paused', 'Native.key(e.getDeviceId()'):
            self.assertLess(d.index('padMenu.key('), d.index(later), later)
        self.assertIn('padMenu.removed(id)', t)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class PadMenuJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.PadMenuJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
