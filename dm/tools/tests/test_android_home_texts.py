"""The home area's texts (strings_home.xml: MainActivity, Ui, Prefs, FrontendLaunch; docs/i18n.md) on the JVM with android/app/src/jvmtest's HomeTextsJvmTest:
the English Tr renders from the resources is the English the Java built before the texts moved. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/HomeTextsJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class HomeTextsJvm(unittest.TestCase):
    def test_home_texts(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', d, *map(str, TR), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', TR_RES, '-cp', d, 'dev.nxnative.android.HomeTextsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


if __name__ == '__main__':
    unittest.main()
