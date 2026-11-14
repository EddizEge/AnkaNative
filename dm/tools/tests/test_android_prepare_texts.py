"""The prepare area's English (android/app/src/main/res/values/strings_prepare.xml: Ondevice, Job, PackService, DumpCheck) is the English the Java built before its
texts moved (docs/i18n.md: English output stays byte-identical): PrepareTextsJvmTest renders each key through Tr's XML backend and compares it with the old
concatenation or String.format. Those classes use Android, so the test renders the keys, not the classes. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PrepareTextsJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class PrepareTexts(unittest.TestCase):
    def test_english_unchanged(self):
        tmp = Path(tempfile.mkdtemp(prefix='prepare_texts_'))
        try:
            classes = tmp / 'classes'
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *map(str, TR), str(TEST)], check=True)
            r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', TR_RES, '-cp', str(classes), 'dev.nxnative.android.PrepareTextsJvmTest'],
                               capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
