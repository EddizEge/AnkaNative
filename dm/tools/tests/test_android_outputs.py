"""The Android app's split outputs (android/app/src/ondevice Outputs.java): the split writes into split/ in place and the folder counts as complete only once a
marker is written after every output is synced, so Prepare never renames a folder on shared storage (MediaProvider may refuse that with EPERM,
BananaNative issue #1) and a stop at any point never leaves cut tables accepted. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/Outputs.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/OutputsJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'
PACK_SERVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class SplitOutputs(unittest.TestCase):
    def test_marker(self):
        tmp = Path(tempfile.mkdtemp(prefix='outputs_'))
        try:
            classes = tmp / 'classes'
            subprocess.run(['javac', '-d', str(classes), str(SRC), *map(str, TR), str(TEST)], check=True)
            work = tmp / 'work'; work.mkdir()
            r = subprocess.run(['java', TR_RES, '-cp', str(classes), 'dev.nxnative.android.OutputsJvmTest', str(work)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class NoFolderRename(unittest.TestCase):
    def test_split_not_renamed(self):
        """The split step writes into split/ and marks it, with no folder rename (Files.move of a folder) left in PackService"""
        s = PACK_SERVICE.read_text(encoding='utf-8')
        self.assertIn('Outputs.begin(split)', s)
        self.assertIn('Outputs.finish(split)', s)
        self.assertIn('Outputs.complete(split)', s)
        self.assertNotIn('Files.move(', s)


if __name__ == '__main__':
    unittest.main()
