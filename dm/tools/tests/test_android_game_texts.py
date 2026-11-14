"""The game area's texts (docs/i18n.md; strings_game.xml: GameActivity, SidePanel, TouchControls, Overlay, Fsr, Lsfg, SelftestActivity) render the same English as
the Java built before they moved into resources (android/app/src/jvmtest's GameTextsJvmTest, which holds each old expression beside its key). Those classes need
Android, so the JVM test compiles only Tr and its message classes and reads the English through Tr's XML backend, as aapt2 compiles it. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/GameTextsJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class GameTexts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='gametexts_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.tmp / 'classes'), *map(str, TR), str(TEST)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_english_unchanged(self):
        r = subprocess.run(['java', TR_RES, '-Dstdout.encoding=UTF-8', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.GameTextsJvmTest'],
                           capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertRegex(r.stdout, r'^ok \d+\n$')


if __name__ == '__main__':
    unittest.main()
