"""The Android launcher's emulator-frontend entry (FrontendLaunch, android/FRONTENDS.md): what Frontend.java reads from a frontend's intent (file:// URIs, provider
URIs that carry a /storage path, the shared storage's aliases, the paths another app may name, the card a request names, title IDs), on the JVM with
android/app/src/jvmtest's FrontendJvmTest. Also checks that the manifest exports only the alias, and the alias by the application ID, and that the play intent
writes nothing and Home takes a frontend's game only by FrontendLaunch's token (FrontendPlayOnly). The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/FrontendJvmTest.java'
MANIFEST = REPO / 'android/app/src/main/AndroidManifest.xml'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class FrontendJvm(unittest.TestCase):
    def test_frontend_paths(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', d, str(SRC / 'Frontend.java'), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.FrontendJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


class FrontendManifest(unittest.TestCase):
    def test_aliases(self):
        m = MANIFEST.read_text()
        act = re.search(r'<activity\s[^>]*android:name="\.FrontendLaunch"[^>]*>', m)
        self.assertIsNotNone(act)
        self.assertIn('android:exported="false"', act.group(0))
        # Android 15 checks an explicit intent against an exported component's filters when it has any, and never matches one with no action (Daijisho's
        # -e game {file.path}): the frontends' alias must have none
        launch = re.search(r'<activity-alias\s[^>]*?android:name="\$\{applicationId\}\.Launch"[^>]*?(/>|>)', m)
        self.assertIsNotNone(launch)
        self.assertEqual(launch.group(1), '/>', 'the Launch alias must have no intent filters')
        self.assertIn('android:targetActivity=".FrontendLaunch"', launch.group(0))
        self.assertIn('android:exported="true"', launch.group(0))
        op = re.search(r'<activity-alias\s[^>]*?android:name="\$\{applicationId\}\.Open"[^>]*>(.*?)</activity-alias>', m, re.S)
        self.assertIsNotNone(op)
        self.assertIn('pathPattern', op.group(1))


class FrontendPlayOnly(unittest.TestCase):
    """The play intent only starts a card, or hands Home a game to ask about (FRONTENDS.md, "What another app can do"): it never writes the list, a pack, a
    driver, a setting or a save, and Home takes a frontend's game only from FrontendLaunch, by its one-time token (MainActivity is exported)."""

    def test_launch_writes_nothing(self):
        src = re.sub(r'/\*.*?\*/|//[^\n]*', '', (SRC / 'FrontendLaunch.java').read_text(), flags=re.S)   # code only, not comments
        for call in ('Game.put', 'Game.save', 'Game.touch', 'Prefs.', 'Ondevice.', 'GpuDriver.', 'PackUi.fromIntent', 'SaveUi.activity', 'Folders.pickedPack',
                     'Folders.packFor', 'setResult', 'getContentResolver', 'FLAG_GRANT', 'getParcelableExtra', 'Intent.parseUri', 'setComponent', 'setClassName'):
            self.assertFalse(call in src, f'FrontendLaunch must not use {call}')

    def test_home_takes_only_the_token(self):
        main = (SRC / 'MainActivity.java').read_text()
        self.assertNotIn('"frontend_game"', main, 'Home must not take a game path from an extra another app can send')
        self.assertIn('FrontendLaunch.take(i.getStringExtra("frontend_token"))', main)
        # the adb control extras stay exactly as gated by issue #7 (Prefs.adbControl)
        m = re.search(r'CONTROL_EXTRAS = \{([^}]*)\}', main)
        self.assertIsNotNone(m)
        self.assertEqual(re.findall(r'"([^"]+)"', m.group(1)),
                         ['game', 'add_game', 'pack', 'save', 'run', 'ondevice_dump', 'ondevice_start', 'ondevice_pause', 'ondevice_pack', 'show_when_locked',
                          'gpu_driver_zip', 'gpu_driver', 'pack_title', 'pack_export', 'pack_import'])
        self.assertIn('if (!Prefs.adbControl(this))', main)


if __name__ == '__main__':
    unittest.main()
