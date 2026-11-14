"""The saves area's texts (strings_saves.xml, docs/i18n.md) on the JVM: javac compiles SaveStore.java and SaveMigrate.java with Tr, Msg, LocalizedIOException and
android/app/src/jvmtest's SavesTextJvmTest, which checks that the English of each moved sentence is the English the code built before the move (and the sentences
rephrased on purpose in their new form), that the refusals are LocalizedIOExceptions, and that SaveMigrate's Result keeps its Msg for the screen. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/SavesTextJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class SavesTextJvm(unittest.TestCase):
    def test_english_unchanged(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(d / 'classes'), str(SRC / 'SaveStore.java'), str(SRC / 'SaveMigrate.java'), *map(str, TR), str(TEST)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (d / 'run').mkdir()
            r = subprocess.run(['java', TR_RES, '-cp', str(d / 'classes'), 'dev.nxnative.android.SavesTextJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


if __name__ == '__main__':
    unittest.main()
