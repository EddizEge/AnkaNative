"""scripts/pack_android.py's swap of a rewritten module into a pack (the phone's packer updates the user's pack in place): the module's files go in from its
staging folder by rename and its key file comes last, so a pack stopped before the key is written has a module without a key, which the launcher refuses."""
import os
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'scripts'))
import pack_android   # noqa: E402


class Swap(unittest.TestCase):
    def test_swap_in_replaces_files_and_writes_the_key_last(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / 'main.patched.bin').write_bytes(b'old'); (out / 'main.fixups.tsv').write_text('old\n')
            st = pack_android.staging(out, 'main'); st.mkdir()
            (st / 'main.patched.bin').write_bytes(b'new'); (st / 'main.fixups.tsv').write_text('new\n'); (st / 'build_src.sha256').write_text('x\n')
            seen = []
            real = os.replace
            def spy(a, b): seen.append(Path(b).name); real(a, b)
            pack_android.os.replace = spy
            try: pack_android.swap_in(out, 'main', 'k target=android')
            finally: pack_android.os.replace = real
            self.assertEqual(seen[-1], 'main.android.key', 'the key must go in last')
            self.assertEqual((out / 'main.patched.bin').read_bytes(), b'new')
            self.assertEqual((out / 'main.fixups.tsv').read_text(), 'new\n')
            self.assertEqual((out / 'main.android.key').read_text(), 'k target=android\n')
            self.assertFalse(st.exists())


class InProcess(unittest.TestCase):
    """run_in_process (the phone's rewrite step): a script's exit code, and an interrupt (the phone's Pause) that goes on as an interrupt instead of being taken
    for exit code 1, which the step printed as a red 'patch_nso.py exit code 1' line on every Pause"""
    def run_script(self, body):
        with tempfile.TemporaryDirectory() as d:
            script, log = Path(d) / 'tool.py', Path(d) / 'tool.log'
            script.write_text(body)
            argv, out, err, env = list(sys.argv), sys.stdout, sys.stderr, dict(os.environ)
            try: return pack_android.run_in_process(script, ['a'], {'DM_TEST_IN_PROCESS': '1'}, log), log.read_text()
            finally:
                self.assertEqual((sys.argv, sys.stdout, sys.stderr), (argv, out, err))
                self.assertEqual(dict(os.environ), env)

    def test_exit_codes(self):
        self.assertEqual(self.run_script('import sys; print(sys.argv[1:]); sys.exit(3)\n'), (3, "['a']\n"))
        self.assertEqual(self.run_script('pass\n')[0], 0)
        code, log = self.run_script('raise ValueError("bad")\n')
        self.assertEqual(code, 1)
        self.assertIn('ValueError: bad', log)

    def test_interrupt_goes_on(self):
        with self.assertRaises(KeyboardInterrupt):
            self.run_script('import os; assert os.environ["DM_TEST_IN_PROCESS"] == "1"; raise KeyboardInterrupt\n')


if __name__ == '__main__':
    unittest.main()
