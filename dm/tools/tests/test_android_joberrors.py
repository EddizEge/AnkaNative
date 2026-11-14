"""The packer's error codes in the launcher (docs/i18n.md "Errors of the packer"): android/app's ondevice JobErrors.java on the JVM (JobErrorsJvmTest), against
dm/tools/pack_errors.py, the packers' side. Checks JobErrors' own rules (parse, message, its tables), that its codes and their numbers of arguments are pack_errors.CODES,
that each errors_<code> text in values/strings_errors.xml takes exactly that many arguments, and that every line pack_errors.line writes is read back by
JobErrors.parse and shown with its arguments. Skipped without a JDK (the JVM part)."""
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import pack_errors  # noqa: E402

SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/JobErrors.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/JobErrorsJvmTest.java'
STRINGS = REPO / 'android/app/src/main/res/values/strings_errors.xml'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'


class Strings(unittest.TestCase):
    def test_one_text_per_code_with_its_arguments(self):
        texts = {e.get('name'): e.text or '' for e in ET.parse(STRINGS).getroot() if e.tag == 'string'}
        self.assertEqual(sorted(texts), sorted(f'errors_{c}' for c in pack_errors.CODES))
        for code, n in pack_errors.CODES.items():
            got = sorted({int(m) for m in re.findall(r'%(\d+)\$s', texts[f'errors_{code}'])})
            self.assertEqual(got, list(range(1, n + 1)), f'errors_{code}: its placeholders are %1$s..%{n}$s')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.classes = Path(cls.tmp.name) / 'classes'
        r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.classes), str(SRC), *map(str, TR), str(TEST)], capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def java(self, mode, stdin=''):
        r = subprocess.run(['java', TR_RES, '-Dstdout.encoding=UTF-8', '-cp', str(self.classes), 'dev.nxnative.android.JobErrorsJvmTest', mode],
                           input=stdin, capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout

    def test_check(self):
        self.assertEqual(self.java('check').strip(), 'ok')

    def test_same_codes_as_the_packer(self):
        rows = [ln.split('\t') for ln in self.java('codes').splitlines()]
        self.assertEqual({c: int(n) for c, n in rows}, pack_errors.CODES)
        self.assertEqual([c for c, _ in rows], list(pack_errors.CODES), 'the same order, so both lists read alike')

    def test_packer_lines_read_back(self):
        lines, want = [], []
        for code, n in pack_errors.CODES.items():
            args = [f'/a {code} {k}' for k in range(n)]
            lines.append(pack_errors.line(code, *args))
            want.append(args)
        # an argument with a tab, a newline or nothing in it: still one argument each
        lines.append(pack_errors.line('module_missing', '/d/base/exefs', 'main\tsdk\nsubsdk0'))
        lines.append(pack_errors.line('no_space', ''))
        lines += ['[RED] no updates/5 (have: none)', 'progress 1/3 main', 'error: no_such_code\tx', 'error: version_missing\t1\t2']
        out = self.java('render', '\n'.join(lines) + '\n').splitlines()
        self.assertEqual(len(out), len(lines))
        for (code, args), text in zip(zip(pack_errors.CODES, want), out):
            self.assertNotIn(text, ('-', '?'), code)
            for x in args: self.assertIn(x, text, code)
        k = len(pack_errors.CODES)
        self.assertEqual(out[k], 'The dump is incomplete: /d/base/exefs has no main sdk subsdk0. Dump the game again.')
        self.assertEqual(out[k + 1], 'Not enough free space (-). Free some space, then try again.')
        self.assertEqual(out[k + 2:], ['-', '-', '?', '?'], 'other lines: not error: lines; an unknown code and a wrong count: no message')


if __name__ == '__main__':
    unittest.main()
