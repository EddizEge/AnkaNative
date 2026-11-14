"""The packers' error codes (docs/i18n.md "Errors of the packer", dm/tools/pack_errors.py): scripts/pack_dump.py and scripts/pack_android.py print `error: <code>`
and its tab-separated arguments on the line before a failure's [RED] line, for the failures a user can fix; other failures print the [RED] line alone, as before.
Runs both scripts on small made-up dump folders (no game files: an NPDM of a few bytes, empty folders) and checks the error: line, its place and the exit code;
checks pack_errors' line format and its readings of OSErrors, tool logs and exit codes, and that every code the scripts pass is in CODES with its number of arguments
(read from their source). pack_android.py needs Capstone 5 and lz4 before anything else: they are replaced here by stand-ins, since none of these failures reaches
the rewriter. The JVM side (JobErrors.parse / message on the same lines) is dm/tools/tests/test_android_joberrors.py."""
import ast
import errno
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import pack_errors  # noqa: E402

OOT = 0x0400486024DEC000   # a profile this tree has (dm/games/0400486024DEC000)
OTHER = 0x0100000000001000   # a base title ID with no profile


def npdm(pid):   # a main.npdm as pack_dump.program_id reads it: META, the ACI0 section's offset and size at 0x70, the program ID at ACI0 + 0x10
    b = bytearray(0x100)
    b[:4] = b'META'; struct.pack_into('<II', b, 0x70, 0x80, 0x40)
    b[0x80:0x84] = b'ACI0'; struct.pack_into('<Q', b, 0x90, pid)
    return bytes(b)


def version(d, pid, modules=(), romfs=True, main_npdm=None):   # a version folder: exefs/main.npdm (+ modules), romfs/ with a file
    (d / 'exefs').mkdir(parents=True)
    (d / 'exefs/main.npdm').write_bytes(main_npdm if main_npdm is not None else npdm(pid))
    for m in modules: (d / 'exefs' / m).write_bytes(b'not an NSO')
    if romfs: (d / 'romfs').mkdir(); (d / 'romfs/file.bin').write_bytes(b'x')


class Run(unittest.TestCase):
    def setUp(self):
        self.t = Path(tempfile.mkdtemp(prefix='packerr_'))
        self.addCleanup(shutil.rmtree, self.t, True)
        # stand-ins for Capstone 5 and lz4, which pack_android.py imports first (only the import and the version check run here)
        fake = self.t / 'fake'; (fake / 'lz4').mkdir(parents=True)
        (fake / 'capstone.py').write_text("__version__ = '5.0.0'\ndef cs_version(): return (5, 0, 5)\n")
        (fake / 'lz4/__init__.py').write_text(''); (fake / 'lz4/block.py').write_text('')
        self.env = dict(os.environ, PYTHONPATH=str(fake), PYTHONDONTWRITEBYTECODE='1')
        self.env.pop('DM_GAME', None)
        self.dump = self.t / 'dump'

    def run_script(self, script, *args):
        r = subprocess.run([sys.executable, str(REPO / 'scripts' / script), *map(str, args)], env=self.env, capture_output=True, text=True, cwd=self.t)
        return r.returncode, r.stdout, r.stderr

    def failed(self, script, *args):
        """(code line split, [RED] line) of a failed run, checking the error: line comes right before the [RED] line on stderr"""
        rc, out, err = self.run_script(script, *args)
        self.assertEqual(rc, 1, out + err)
        lines = err.splitlines()
        red = next(i for i, ln in enumerate(lines) if ln.startswith('[RED] '))
        codes = [i for i, ln in enumerate(lines) if pack_errors.parse(ln)]
        self.assertNotIn('error: ', out, 'error: lines go to stderr, with the [RED] line')
        if not codes: return None, lines[red]
        self.assertEqual(codes, [red - 1], f'one error: line, right before the [RED] line: {err}')
        return pack_errors.parse(lines[red - 1]), lines[red]

    # pack_dump.py
    def test_dump_incomplete(self):
        self.dump.mkdir()
        p, red = self.failed('pack_dump.py', self.dump)
        self.assertEqual(p, ['dump_incomplete', str(self.dump)])
        self.assertIn('no base/exefs/main.npdm', red)

    def test_dump_not_an_npdm(self):
        version(self.dump / 'base', OOT, main_npdm=b'not an NPDM')
        p, red = self.failed('pack_dump.py', self.dump)
        self.assertEqual(p, ['unreadable_file', str(self.dump / 'base/exefs/main.npdm')])
        self.assertTrue(red.endswith('main.npdm: not an NPDM'), red)

    def test_dump_not_base_title(self):
        version(self.dump / 'base', OOT + 0x800)
        p, _ = self.failed('pack_dump.py', self.dump)
        self.assertEqual(p, ['not_base_title', str(self.dump), f'{OOT + 0x800:016X}'])

    def test_dump_version_missing(self):
        version(self.dump / 'base', OOT)
        p, red = self.failed('pack_dump.py', self.dump, '--version', 65536, '--work-dir', self.t / 'work')
        self.assertEqual(p, ['version_missing', '65536'])
        self.assertEqual(red, '[RED] no updates/65536 (have: none)')

    def test_dump_wrong_title(self):
        version(self.dump / 'base', OOT)
        version(self.dump / 'updates/65536', OTHER)
        (self.dump / 'updates/65536/exefs/main').write_bytes(b'x')
        p, _ = self.failed('pack_dump.py', self.dump, '--work-dir', self.t / 'work')
        self.assertEqual(p, ['wrong_title', str(self.dump / 'updates/65536'), f'{OTHER:016X}', f'{OOT:016X}'])

    def test_dump_uncoded_failure(self):   # a DLC folder that is not a DLC: the [RED] line alone, as before
        version(self.dump / 'base', OOT)
        (self.dump / 'dlc/zz').mkdir(parents=True)
        p, red = self.failed('pack_dump.py', self.dump)
        self.assertIsNone(p)
        self.assertTrue(red.endswith('zz: not a DLC title ID'), red)

    # pack_android.py
    def test_android_no_profile(self):
        version(self.dump / 'base', OTHER)
        p, _ = self.failed('pack_android.py', self.dump, '--out', self.t / 'pack')
        self.assertEqual(p, ['no_profile', f'{OTHER:016X}'])

    def test_android_dump_incomplete(self):
        self.dump.mkdir()
        p, _ = self.failed('pack_android.py', self.dump, '--out', self.t / 'pack')
        self.assertEqual(p, ['dump_incomplete', str(self.dump)])

    def test_android_version_missing(self):
        version(self.dump / 'base', OOT)
        p, _ = self.failed('pack_android.py', self.dump, '--version', 5, '--out', self.t / 'pack')
        self.assertEqual(p, ['version_missing', '5'])

    def test_android_wrong_title(self):
        version(self.dump / 'base', OOT)
        version(self.dump / 'updates/65536', OTHER, modules=('main',))
        p, _ = self.failed('pack_android.py', self.dump, '--out', self.t / 'pack')
        self.assertEqual(p, ['wrong_title', str(self.dump / 'updates/65536'), f'{OTHER:016X}', f'{OOT:016X}'])

    def test_android_module_missing(self):
        version(self.dump / 'base', OOT, modules=('main',))
        p, red = self.failed('pack_android.py', self.dump, '--out', self.t / 'pack')
        self.assertEqual(p, ['module_missing', str(self.dump / 'base/exefs'), 'subsdk0 sdk'])
        self.assertIn('no subsdk0, sdk', red)

    def test_android_not_an_nso(self):
        version(self.dump / 'base', OOT, modules=('main', 'subsdk0', 'sdk'))
        p, _ = self.failed('pack_android.py', self.dump, '--out', self.t / 'pack')
        self.assertEqual(p, ['unreadable_file', str(self.dump / 'base/exefs/main')])

    def test_android_uncoded_failure(self):   # a pack folder inside the repository: the [RED] line alone, as before
        version(self.dump / 'base', OOT, modules=('main', 'subsdk0', 'sdk'))
        p, red = self.failed('pack_android.py', self.dump, '--out', REPO / 'no-such-pack')
        self.assertIsNone(p)
        self.assertIn('is inside the repository', red)
        self.assertFalse((REPO / 'no-such-pack').exists())


class Lines(unittest.TestCase):
    def test_line_and_parse(self):
        ln = pack_errors.line('wrong_title', Path('/d/u'), 'A\tB', '')
        self.assertEqual(ln, 'error: wrong_title\t/d/u\tA B\t-')
        self.assertEqual(pack_errors.parse(ln + '\n'), ['wrong_title', '/d/u', 'A B', '-'])
        self.assertEqual(pack_errors.parse('error: translator_crashed'), ['translator_crashed'])
        self.assertEqual(pack_errors.line('module_missing', 'x', 'a\r\nb'), 'error: module_missing\tx\ta  b')
        for other in ('[RED] x', 'error: line 3: bad', 'pack_android.py: error: x', 'error: ', 'error: Bad\tx'):
            self.assertIsNone(pack_errors.parse(other), other)

    def test_unknown_code_or_count_refused(self):
        with self.assertRaises(ValueError): pack_errors.line('no_such_code')
        with self.assertRaises(ValueError): pack_errors.line('no_space')
        with self.assertRaises(ValueError): pack_errors.Coded('msg', 'no_space', 'a', 'b')
        e = pack_errors.Coded('msg', 'version_missing', 5)
        self.assertEqual((str(e), e.code, e.code_args), ('msg', 'version_missing', ('5',)))
        self.assertIsNone(pack_errors.Coded('msg').code)

    def test_codes_are_words(self):
        for c in pack_errors.CODES: self.assertRegex(c, pack_errors.CODE)

    def test_os_errors(self):
        self.assertEqual(pack_errors.of_os_error(OSError(errno.ENOSPC, 'No space left on device', '/p/x')), ('no_space', ('/p/x',)))
        self.assertEqual(pack_errors.of_os_error(OSError(errno.ENOSPC, 'No space left on device')), ('no_space', ('-',)))
        self.assertEqual(pack_errors.of_os_error(OSError(errno.EIO, 'I/O error', '/sd/romfs/a')), ('unreadable_file', ('/sd/romfs/a',)))
        self.assertIsNone(pack_errors.of_os_error(OSError(errno.ENOENT, 'No such file or directory', '/x')))
        self.assertIsNone(pack_errors.of_os_error(OSError(errno.EIO, 'I/O error')))

    def test_tool_logs(self):
        tb = 'Traceback (most recent call last):\n  File "patch_nso.py", line 9, in <module>\nMemoryError\n'
        self.assertEqual(pack_errors.of_log(tb, 'patch_nso.py'), ('out_of_memory', ('patch_nso.py',)))
        tb = "Traceback (most recent call last):\nOSError: [Errno 28] No space left on device: '/sdcard/pack/.rewrite-main/main.patched.bin'\n"
        self.assertEqual(pack_errors.of_log(tb, 'patch_nso.py'), ('no_space', ('/sdcard/pack/.rewrite-main/main.patched.bin',)))
        self.assertEqual(pack_errors.of_log('OSError: [Errno 28] No space left on device\n', 'x.py'), ('no_space', ('x.py',)))
        self.assertIsNone(pack_errors.of_log('[RED] something else\nValueError: no MemoryError here\n', 'x.py'))
        self.assertEqual(pack_errors.of_exit(-9, 'vk_android_build.py'), ('out_of_memory', ('vk_android_build.py',)))
        self.assertIsNone(pack_errors.of_exit(1, 'vk_android_build.py'))
        self.assertIsNone(pack_errors.of_exit(-11, 'vk_android_build.py'))
        self.assertEqual(pack_errors.flat(None), ())
        self.assertEqual(pack_errors.flat(('no_space', ('/p',))), ('no_space', '/p'))


class Detail(unittest.TestCase):
    """A coded failure with a tool's log tail (Coded.detail, pack_android.py's failed patch_nso.py): the tail comes first, so the error: line stays right before
    the last [RED] line even when the tail has [RED] lines of its own (JobErrors.Watch pairs the code with the step log's last [RED] line)"""
    def test_tail_before_the_code(self):
        code = ("import sys; sys.path.insert(0, sys.argv[1]); import pack_errors\n"
                "pack_errors.fail(pack_errors.Coded('patch_nso.py exit code 1 on main (the last lines of rewrite_main.log above)', 'out_of_memory', 'patch_nso.py',"
                " detail=['  rewriting', '[RED] a red line of the tool', 'MemoryError']))\n")
        r = subprocess.run([sys.executable, '-c', code, str(REPO / 'dm/tools')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1, r.stderr)
        lines = r.stderr.splitlines()
        self.assertEqual(lines, ['  rewriting', '[RED] a red line of the tool', 'MemoryError', 'error: out_of_memory\tpatch_nso.py',
                                 '[RED] patch_nso.py exit code 1 on main (the last lines of rewrite_main.log above)'])

    def test_scripts_pass_the_tail_as_detail(self):
        """pack_android.py's patch_nso.py failures put their tail in detail=, never into the message after the sentence"""
        tree = ast.parse((REPO / 'scripts/pack_android.py').read_text(encoding='utf-8'))
        calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == 'Error'
                 and isinstance(n.args[0], ast.JoinedStr) and 'patch_nso.py exit code' in ast.unparse(n.args[0])]
        self.assertEqual(len(calls), 2)
        for n in calls:
            self.assertIn('detail', [k.arg for k in n.keywords], ast.unparse(n))
            self.assertNotIn('\\n', ast.unparse(n.args[0]), ast.unparse(n))


class Source(unittest.TestCase):
    """Every Error(message, 'code', args...) in the two scripts names a code of CODES with its number of arguments (a call whose code is not a literal passes
    through pack_errors.flat, which only gives what pack_errors itself made)"""
    def test_codes_in_the_scripts(self):
        seen = set()
        for script in ('pack_dump.py', 'pack_android.py'):
            tree = ast.parse((REPO / 'scripts' / script).read_text(encoding='utf-8'))
            for n in ast.walk(tree):
                if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == 'Error' and len(n.args) > 1 and isinstance(n.args[1], ast.Constant):
                    code = n.args[1].value
                    self.assertIn(code, pack_errors.CODES, f'{script}:{n.lineno}')
                    self.assertEqual(len(n.args) - 2, pack_errors.CODES[code], f'{script}:{n.lineno}: {code} arguments')
                    seen.add(code)
        # every code but translator_crashed (no tool prints it yet), out_of_memory and no_space (from pack_errors' readings) is passed by a script
        self.assertEqual(seen, set(pack_errors.CODES) - {'translator_crashed', 'out_of_memory', 'no_space'})

    def test_not_a_shader_chain_or_rewriter_input(self):
        sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
        import dmsh_chain
        src = (REPO / 'dm/gfx/tools/vk_android_build.py').read_text(encoding='utf-8')
        vk_files = ast.literal_eval(next(ln for ln in src.splitlines() if ln.startswith('VK_FILES = ')).split('=', 1)[1].strip())
        for f in ('dm/tools/pack_errors.py', 'scripts/pack_android.py', 'scripts/pack_dump.py'):
            gfx = os.path.relpath(REPO / f, REPO / 'dm/gfx')
            self.assertNotIn(gfx, dmsh_chain.FILES, f)
            self.assertNotIn(gfx, vk_files, f)
        rewriter = ast.literal_eval(next(ln for ln in (REPO / 'scripts/pack_android.py').read_text(encoding='utf-8').splitlines() if ln.startswith('REWRITER = ')).split('=', 1)[1].strip())
        self.assertNotIn('pack_errors.py', rewriter)


if __name__ == '__main__':
    unittest.main()
