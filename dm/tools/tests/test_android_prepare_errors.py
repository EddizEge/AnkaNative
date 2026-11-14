"""Prepare's own failure codes (docs/i18n.md "Errors of Prepare"): android/app's ondevice PrepareErrors.java on the JVM (PrepareErrorsJvmTest), compiled with
JobErrors (its codes must differ), Profile (the version in words) and a stub BuildConfig. Checks that each code's English is the error PackService and DumpCheck wrote
before, that the status line Job builds from it is too, that job.json's strings come back as the same text, that another version's code or arguments give null,
and a scratch Turkish rendering. Also checks, from the source, that every PrepareErrors.coded / failed call in PackService and DumpCheck names a code of CODES with
its number of arguments. Skipped without a JDK (the JVM part)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PrepareErrorsJvmTest.java'
TR = [MAIN / f'{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "0400486024DEC000\\tThe Legend of Zelda\\t1\\tabcdef012345\\t65536 main=aa";
}
'''


class Source(unittest.TestCase):
    def test_calls_name_known_codes(self):
        src = (ONDEVICE / 'PrepareErrors.java').read_text(encoding='utf-8')
        codes = re.search(r'CODES = \{([^}]*)\}', src).group(1)
        args = re.search(r'ARGS = \{([^}]*)\}', src).group(1)
        table = dict(zip(re.findall(r'"([a-z_]+)"', codes), map(int, re.findall(r'\d+', args))))
        seen = set()
        for f in ('PackService.java', 'DumpCheck.java'):
            text = (ONDEVICE / f).read_text(encoding='utf-8')
            for m in re.finditer(r'(?:PrepareErrors\.coded|failed)\("([a-z_]+)"((?:[^()]|\([^()]*\))*)\)', text):
                code, rest = m.group(1), m.group(2)
                self.assertIn(code, table, f'{f}: {m.group(0)}')
                depth, count = 0, 0
                for ch in rest:   # the arguments after the code: top-level commas
                    depth += ch in '(['
                    depth -= ch in ')]'
                    count += ch == ',' and depth == 0
                self.assertEqual(count, table[code], f'{f}: {m.group(0)}: {code} takes {table[code]} arguments')
                seen.add(code)
        self.assertEqual(seen, set(table), 'every code is used')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_prepare_errors(self):
        tmp = Path(tempfile.mkdtemp(prefix='prepare_errors_'))
        try:
            (tmp / 'BuildConfig.java').write_text(STUB)
            srcs = [ONDEVICE / 'PrepareErrors.java', ONDEVICE / 'JobErrors.java', MAIN / 'Profile.java']
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(tmp / 'classes'), *map(str, srcs), *map(str, TR), str(TEST), str(tmp / 'BuildConfig.java')],
                           check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', TR_RES, '-cp', str(tmp / 'classes'), 'dev.nxnative.android.PrepareErrorsJvmTest'],
                               capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
