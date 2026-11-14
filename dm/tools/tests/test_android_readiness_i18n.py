"""The readiness area's texts (docs/i18n.md) on the JVM: android/app/src/jvmtest's ReadinessI18nJvmTest compiled with Readiness.java and Tr's classes. The GPU line keeps
its English word for word, every HOST row has its resource, and a check made of Msgs is English in the report and the user's language on the screen. Skipped without
a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/ReadinessI18nJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ReadinessI18nJvm(unittest.TestCase):
    def test_texts(self):
        with tempfile.TemporaryDirectory() as tmp:
            classes = Path(tmp) / 'classes'
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(classes), str(SRC / 'Readiness.java'), *map(str, TR), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', TR_RES, '-cp', str(classes), 'dev.nxnative.android.ReadinessI18nJvmTest'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertRegex(r.stdout, r'^ok \d+')


if __name__ == '__main__':
    unittest.main()
