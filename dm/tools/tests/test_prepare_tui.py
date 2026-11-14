"""scripts/prepare.py, the terminal interface for preparing a game on a computer (dm/tools/prepare_tui), without a terminal of the test's own:
- keys: the escape-sequence family (CSI and SS3 forms of the xterm, rxvt and Linux console spellings, bracketed paste, a lone Escape, sequences split between
  reads, UTF-8; on a pty, a sequence whose parts come 80 ms apart) and the Windows console's scan codes (0xE0 being the character à unless a scan code follows it),
  fed as characters into term.Decoder
- drawing: every screen into an in-memory screen.Screen at 80x24, 160x50 and 60x18, Unicode and ASCII glyphs (an ASCII screen holds only ASCII), nothing past
  the edge, no colour in the text, each key line whole at 80 columns, a fix shared by several checks shown once; render()'s escape sequences with and without colour;
  file names with wide, decomposed, emoji and control characters (one size column, accents kept, no control character written)
- the browser's logic on a temporary folder tree (folders, .nup files, dump folders, the filter, up and into folders, the window, path completion) and the
  state file (written, read back, a damaged one); a path dropped on the terminal in the shell's quoting
- the packer's output: progress lines in the three spellings, step lines, error: lines and [RED] lines, and a failure's code paired as the phone pairs it
- the ZBIC decoder's check (checks.check_zbic) on a copy of its sources: built with the C compiler found, then cached (at once), no compiler (each system's
  install line), a compiler that fails (its lines), a DM_ZBIC_LIB that does not load, a $CC with an unclosed quote, a build folder that cannot be written,
  a compiler that writes junk (removed), junk an earlier compiler left (built again), a library that loads but does not decode, and run_all including it
- the packer as a child process (runner.Run): a worker left behind holding the output (the run still ends), a log that cannot be written (the run goes on),
  a folder that cannot be written, the lock against a second run; a failure without a code in words (a signal, a permission error, else a bug to report)
- the estimator (eta.py, the phone's Eta.java on the computer's steps): steady, a falling rate, a stall, the translation's end and the library's countdown, a
  resumed run's baseline, the split's hand-over to the shaders' figure, totals of 0, and a replay of the real run's trace (prepare_data/prepare-pc-oot.trace.txt)
- the plain line mode end to end (done, failed, a bad pick, a name in the last folder, a worker left behind, a pack folder that cannot be written, a second
  run refused, no C compiler for the decoder, and on POSIX Ctrl+C and the packer killed), and on POSIX the full-screen mode on a pseudo-terminal (prepare_vt.py): a
  made-up dump folder of Ocarina of Time (an NPDM and NSO headers of a few bytes, as test_pack_errors.py makes them) picked with the packer's own checks, and a
  stand-in packer that prints what pack_android.py prints; a file picked with keys, confirmed, the run watched to its end, the terminal's modes put back; Ctrl+C
  in the middle stops the stand-in packer and its worker; so do SIGHUP (a closed window) and SIGTERM; no C compiler stops on the checks (exit 1); Esc on the pick asks; kill -TSTP and SIGCONT give the
  terminal back and take it again; a pack folder that cannot be written is said on the plan
- the texts: every key the code uses is in texts.TEXTS, and every key there is used
The Windows console itself (term.WindowsTerminal: console modes, msvcrt, the close event) cannot run here: only its scan-code decoding is tested."""
import math
import os
import re
import struct
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(Path(__file__).resolve().parent))
import nso   # noqa: E402
from prepare_tui import app, browser, eta, model, progress, runner, screen as scr, texts   # noqa: E402
from prepare_tui.term import Decoder   # noqa: E402

DATA = Path(__file__).resolve().parent / 'prepare_data'
OOT = '0400486024DEC000'
BUILDS = {'main': 'e0f3ccfaf50065ae4ea01eb5aeb13b1a', 'subsdk0': 'b0110087a7b339ef74588d3bd0b4dd5d', 'sdk': '1643a838814f9c10c696f930ed681e7f'}


def name(e): return e.name if e is not None else None


def names(keys): return [k.name if k.name not in ('char', 'paste') else (k.name, k.text) for k in keys]


class Keys(unittest.TestCase):
    def test_escape_sequences(self):
        d = Decoder()
        seqs = {'\x1b[A': 'up', '\x1b[B': 'down', '\x1b[C': 'right', '\x1b[D': 'left', '\x1bOA': 'up', '\x1bOD': 'left', '\x1b[H': 'home', '\x1b[F': 'end',
                '\x1bOH': 'home', '\x1bOF': 'end', '\x1b[1~': 'home', '\x1b[4~': 'end', '\x1b[7~': 'home', '\x1b[8~': 'end', '\x1b[5~': 'pgup', '\x1b[6~': 'pgdn',
                '\x1b[2~': 'insert', '\x1b[3~': 'delete', '\x1bOP': 'f1', '\x1bOS': 'f4', '\x1b[11~': 'f1', '\x1b[15~': 'f5', '\x1b[17~': 'f6', '\x1b[24~': 'f12',
                '\x1b[[A': 'f1', '\x1b[[E': 'f5', '\x1b[1;5A': 'up', '\x1b[1;2D': 'left', '\x1b[3;5~': 'delete', '\x1b[Z': 'backtab', '\x1b[1;2P': 'f1'}
        for s, want in seqs.items(): self.assertEqual(names(d.feed(s)), [want], repr(s))
        self.assertEqual(d.buf, '')

    def test_control_keys_and_text(self):
        d = Decoder()
        self.assertEqual(names(d.feed('a\r\n\t\x7f\x08\x03\x0c\x15é')), [('char', 'a'), 'enter', 'enter', 'tab', 'backspace', 'backspace', 'ctrl-c', 'ctrl-l', 'ctrl-u',
                                                                      ('char', 'é')])

    def test_split_reads_and_lone_escape(self):
        d = Decoder()
        self.assertEqual(d.feed('\x1b'), []); self.assertEqual(d.feed('['), []); self.assertEqual(names(d.feed('6~x')), ['pgdn', ('char', 'x')])
        self.assertEqual(d.feed('\x1b'), []); self.assertEqual(names(d.flush()), ['esc'])   # nothing came after it: the Escape key
        self.assertEqual(names(d.feed('\x1bq')), ['esc', ('char', 'q')])   # Alt+q: Escape, then q
        self.assertEqual(d.feed('\x1b['), []); self.assertEqual(names(d.flush()), ['esc', ('char', '[')])

    def test_bracketed_paste(self):
        d = Decoder()
        got = d.feed('\x1b[200~/home/me/Switch2/Ocarina of Time.nup\x1b[201~\r')
        self.assertEqual(names(got), [('paste', '/home/me/Switch2/Ocarina of Time.nup'), 'enter'])
        got = d.feed('\x1b[200~C:\\Games\\a'); self.assertEqual(got, [])   # the end of the paste comes in a later read
        self.assertEqual(names(d.feed('b.nup\x1b[201~')), [('paste', 'C:\\Games\\ab.nup')])

    def test_windows_scan_codes(self):
        d = Decoder(scancodes=True)
        seqs = {'\xe0H': 'up', '\xe0P': 'down', '\xe0K': 'left', '\xe0M': 'right', '\xe0G': 'home', '\xe0O': 'end', '\xe0I': 'pgup', '\xe0Q': 'pgdn',
                '\xe0S': 'delete', '\xe0R': 'insert', '\x00;': 'f1', '\x00D': 'f10', '\xe0\x85': 'f11', '\xe0\x86': 'f12', '\x00H': 'up', '\xe0s': 'left',
                '\xe0\x8d': 'up'}
        for s, want in seqs.items(): self.assertEqual(names(d.feed(s)), [want], repr(s))
        self.assertEqual(d.feed('\xe0'), []); self.assertEqual(names(d.feed('P')), ['down'])   # the scan code in a later read
        self.assertEqual(names(d.feed('\r\x08\x03a')), ['enter', 'backspace', 'ctrl-c', ('char', 'a')])
        self.assertEqual(names(d.feed('\x1b[A')), ['up'])   # Windows Terminal with virtual terminal input: the other family
        self.assertEqual(names(Decoder().feed('\x00')), ['ctrl-`'])   # off the Windows console 0x00 is a control key, not a scan code's start
        d = Decoder(scancodes=True)
        self.assertEqual(d.feed('\xe0'), []); self.assertEqual(names(d.flush()), [('char', '\xe0')])   # nothing after it: the character à, typed
        self.assertEqual(names(d.feed('x')), [('char', 'x')])   # ... and the next key is not taken as its scan code
        got = Decoder(scancodes=True).feed('C:\\Jeux\\Voil\xe0\\x.nup')   # à and what follows it in one read (a paste, fast typing): à is a character there
        self.assertEqual(''.join(k.text for k in got), 'C:\\Jeux\\Voil\xe0\\x.nup')
        self.assertEqual(names(Decoder(scancodes=True).feed('d\xe9j\xe0 vu')), [('char', c) for c in 'd\xe9j\xe0 vu'])

    @unittest.skipIf(os.name == 'nt', 'a pseudo-terminal is POSIX')
    def test_sequence_split_by_a_delay(self):
        """An arrow key whose escape sequence comes in two parts 80 ms apart (ssh, a busy computer) is the arrow, not Escape and two characters"""
        import threading
        from prepare_tui.term import PosixTerminal
        m, sl = os.openpty()
        try:
            t = PosixTerminal(fd=sl, out=open(os.devnull, 'w'))
            t.modes_on()
            def later(): time.sleep(0.08); os.write(m, b'[B')
            os.write(m, b'\x1b'); threading.Thread(target=later).start()
            self.assertEqual(names(t.keys(1.0)), ['down'])
            t.modes_off(); t.out.close()
        finally: os.close(m); os.close(sl)


# ---- drawing

class FakeGame:
    def __init__(self, vers=(131072,), other=()):
        self.app, self.vers, self.ver, self.mods, self.other = int(OOT, 16), {v: None for v in vers}, max(vers), ['main', 'subsdk0', 'sdk'], list(other)
        self.folder = Path('/games/Switch2/OcarinaOfTime.nup')
        self.g = self

    def get(self, k, d=None): return {'name': 'The Legend of Zelda: Ocarina of Time'}.get(k, d)


class FakePlan:
    """What the screens read of a model.Plan"""
    def __init__(self, short=False, translated=0, kept=(), lib_current=False, split=True):
        self.x = FakeGame(); self.g = self.x
        self.out = Path('/games/Switch2/0400486024DEC000-work/android-pack')
        self.J, self.shaders, self.translated, self.kept_mods, self.lib_current = 12, 42854, translated, list(kept), lib_current
        self.rewrite, self.split, self.build = len(kept) < 3, split, not lib_current
        self.mix = (42854, 27002)
        self._short = short

    def space(self): return [(Path('/games'), 6_000_000_000, 1_000_000_000 if self._short else 90_000_000_000)]
    def short(self): return [s for s in self.space() if s[2] < s[1]]
    def shader_s(self): return eta.SHADER_CPU_S / self.J
    def rough(self): return 3000.0


def oks():
    from prepare_tui.checks import Check
    return [Check('Python 3.14', True, '3.14.0 (/usr/bin/python3)'), Check('Capstone 5', True, '5.0.7'),
            Check('spirv-val (SPIRV-Tools)', False, 'not on the PATH', ['sudo apt install spirv-tools'])]


def same_fix():
    """Two missing packages fixed by the same steps, and a long value that wraps"""
    from prepare_tui.checks import Check
    fix = ['python3.14 -m venv <dump folder>/.venv-anka', '<dump folder>/.venv-anka/bin/python -m pip install "capstone>=5,<6" lz4']
    return [Check('Python 3.14', True, '3.14.0 (/a/very/long/path/to/an/environment/of/its/own/on/the/dump/drive/bin/python3)'),
            Check('Capstone 5', False, 'missing (capstone)', fix), Check('lz4', False, 'missing (lz4)', fix)]


def live_at(plan, lines, t0=1000.0, dt=1.0):
    lv = model.Live(plan, t0)
    t = t0
    for l in lines: t += dt; lv.feed(t, l)
    lv.tick(t)
    return lv, t


RUN_LINES = ['step identify', '0400486024DEC000 The Legend of Zelda: Ocarina of Time version 131072: x', 'step rewrite', 'progress 0/3 main', 'progress 3/3 main',
             'step split', 'progress: 0/1 main', 'progress: 186583/186583', 'step shaders', 'translating 42854 shaders', 'progress 0 42854 translate',
             'progress 12034 42854 translate', '[RED] delta: k: the older library\'s entry cannot be read (x): translated']


class Drawing(unittest.TestCase):
    def screens(self):
        p = FakePlan()
        pick = app.Pick.__new__(app.Pick)
        with tempfile.TemporaryDirectory() as d:
            for n in ('base', 'updates'): (Path(d) / 'Game' / n).mkdir(parents=True)
            (Path(d) / 'a.nup').write_bytes(b'x' * 10)
            pick.state, pick.b, pick.places, pick.pcur, pick.focus, pick.path = {}, browser.Browser(Path(d)), [('Home', Path(d))], 0, 'list', None
            pick.msg, pick.msg_style = 'Reading the game ...', 0
            lv, t = live_at(p, RUN_LINES)
            yield 'checks', lambda s: app.draw_checks(s, oks())
            yield 'checks-same', lambda s: app.draw_checks(s, same_fix())
            yield 'pick', lambda s: app.draw_pick(s, pick)
            pick2 = pick; yield 'pick-path', lambda s: (setattr(pick2, 'path', '/very/long/path/' * 12), app.draw_pick(s, pick2), setattr(pick2, 'path', None))
            yield 'confirm', lambda s: app.draw_confirm(s, p)
            yield 'confirm-short', lambda s: app.draw_confirm(s, FakePlan(short=True, translated=1000, kept=('main',)))
            yield 'run', lambda s: app.draw_run(s, lv, Path('/games/x/prepare-logs/prepare-1.log'), t)
            yield 'run-stopping', lambda s: app.draw_run(s, lv, Path('/x.log'), t, app.T('run.stopping', 20))
            done = app.Outcome('done', lv, p, 0, 3000.0, Path('/x.log')); lv.state.pack = None
            yield 'end-done', lambda s: app.draw_end(s, done)
            lf, _ = live_at(p, RUN_LINES + ['Traceback (most recent call last):', 'error: no_space\t/games/x', '[RED] [Errno 28] No space left on device'])
            yield 'end-failed', lambda s: app.draw_end(s, app.Outcome('failed', lf, p, 1, 100.0, Path('/x.log')))
            yield 'end-stopped', lambda s: app.draw_end(s, app.Outcome('stopped', lv, p, -2, 100.0, Path('/x.log')))

    def test_every_screen_fits(self):
        for w, h in ((80, 24), (160, 50), (60, 18)):
            for uni in (True, False):
                g = scr.Glyphs(uni)
                for name, draw in self.screens():
                    sc = scr.Screen(w, h, g)
                    draw(sc)
                    txt = sc.text()
                    lines = txt.split('\n')
                    self.assertEqual(len(lines), h)
                    for l in lines: self.assertLessEqual(scr.width(l), w, f'{name} {w}x{h}: {l!r}')
                    if not uni: self.assertTrue(all(ord(c) < 128 for c in txt), f'{name} ASCII:\n{txt}')
                    self.assertNotIn('\x1b', txt)
                    if name == 'checks': self.assertIn('sudo apt install spirv-tools', txt); self.assertIn('1 missing', txt)
                    if name == 'checks-same' and h >= 24:
                        self.assertEqual(txt.count('pip install'), 1); self.assertIn('Fixed by the same steps as above.', txt)
                        self.assertIn('bin/python3)', txt)   # the long value whole, on more lines
                    if name == 'run':
                        self.assertIn('12,034 of 42,854 shaders', txt); self.assertIn('Log:', txt); self.assertIn('1 warning lines', txt)
                        self.assertIn('Build the shader library', txt)
                    if name == 'confirm' and w >= 80: self.assertIn('The Legend of Zelda: Ocarina of Time (0400486024DEC000)', txt); self.assertIn('Kept', txt)
                    if name == 'confirm-short': self.assertIn('Not enough free space', txt)
                    if name == 'end-done' and h >= 24: self.assertIn('The pack is ready', txt); self.assertIn('Next:', txt)
                    if name.startswith('end-') and name != 'end-failed': self.assertNotIn('failed', txt)
                    if name == 'end-done' and (w, h) == (80, 24): self.assertIn('must keep the name', txt)   # the whole end fits at 80x24
                    if name == 'end-failed' and w >= 80: self.assertIn('Not enough free space (/games/x)', txt)
                    if name == 'end-stopped': self.assertIn('What was finished is kept', txt)

    def test_workers_hint_whole(self):
        """The workers' key hint is never cut in two at 80 columns: beside the value when it fits (12 threads, 11.2 GB: the real run's), else whole below"""
        from unittest import mock
        for cpus, mem, beside in ((12, 11_200_000_000, True), (128, 251_300_000_000, False)):
            with mock.patch('os.cpu_count', return_value=cpus), mock.patch.object(model, 'free_memory', return_value=mem):
                sc = scr.Screen(80, 24, scr.Glyphs(True)); app.draw_confirm(sc, FakePlan())
            line = next(l for l in sc.text().split('\n') if 'shaders at once' in l)
            self.assertEqual(line.rstrip().endswith('(+/-)'), beside, line)
            self.assertIn('(+/-)', sc.text())

    def test_key_lines_fit(self):
        """Each screen's key line is whole at 80 columns (the footer has 78)"""
        for k, v in texts.TEXTS.items():
            if k.startswith('keys.'): self.assertLessEqual(scr.width(v), 78, k)

    def test_render(self):
        a = scr.Screen(20, 3); a.put(0, 0, 'hello', scr.BOLD | scr.GREEN)
        out = scr.render(a, None, True)
        self.assertIn('\x1b[1;1H', out); self.assertIn('\x1b[0;1;32mhello', out)
        self.assertNotIn('32', scr.render(a, None, False).replace('\x1b[1;1H', ''))   # no colour: bold stays, green goes
        b = scr.Screen(20, 3); b.rows = [r[:] for r in a.rows]; b.put(0, 2, 'x')
        self.assertEqual(scr.render(b, a, True).count('H'), 1)   # only the changed row

    def test_widths(self):
        s = scr.Screen(10, 1); s.put(0, 0, 'ab漢字cdef')
        self.assertEqual(s.text(), 'ab漢字cdef'[:8]); self.assertEqual(scr.width(s.text()), 10)
        self.assertEqual(scr.fit('/a/very/long/path/file.nup', 12, '…', left=True), '…th/file.nup')
        self.assertEqual(scr.wrap('one two three', 7), ['one two', 'three'])
        self.assertEqual(scr.wrap('Pack: /games/Switch2/run1/pack', 16), ['Pack:', '/games/Switch2/', 'run1/pack'])   # a path cut after a separator
        self.assertEqual(scr.wrap('abcdefghij', 4), ['abcd', 'efgh', 'ij'])
        s = scr.Screen(10, 1); s.put(0, 0, 'e\u0301x\u202ey'); self.assertEqual(s.rows[0][:3], [('é', 0), ('x', 0), ('y', 0)])   # composed; a bidi control not drawn
        self.assertEqual(scr.pad('漢x', 5), '漢x  ')

    def test_odd_names(self):
        """File names with wide, combining (a name the Mac stores decomposed), emoji and control characters: the size column stays in place, accents stay,
        no control character reaches the terminal"""
        import unicodedata
        with tempfile.TemporaryDirectory() as d:
            names_ = ['日本語のゲーム名がとても長いファイル.nup', unicodedata.normalize('NFD', 'Pokémon.nup'), 'combining-e\u0301\u0301\u0301.nup', 'emoji-🎮\ufe0f.nup',
                      'csi-\x9b31m-red.nup', 'plain.nup']
            for n in names_: (Path(d) / n).write_bytes(b'x' * 10)
            pick = app.Pick.__new__(app.Pick)
            pick.state, pick.b, pick.places, pick.pcur, pick.focus, pick.path = {}, browser.Browser(Path(d)), [('Home', Path(d))], 0, 'places', None
            pick.msg, pick.msg_style = '', 0
            sc = scr.Screen(80, 24); app.draw_pick(sc, pick)
            txt = sc.text()
            rows = [l for l in txt.split('\n') if '0.0 GB' in l]
            self.assertEqual(len(rows), len(names_), txt)
            self.assertEqual({scr.width(l[:l.index('0.0 GB')]) for l in rows}, {scr.width(rows[-1][:rows[-1].index('0.0 GB')])}, txt)   # one column for every size
            self.assertIn('Pokémon.nup', unicodedata.normalize('NFC', txt))
            out = scr.render(sc, None, True)
            self.assertNotIn('\x9b', out); self.assertIn('csi-?31m-red', txt)

    def test_glyphs_for(self):
        self.assertTrue(scr.glyphs_for('utf-8').unicode)
        self.assertFalse(scr.glyphs_for('cp437').unicode)   # cp437 has the box lines but not ✓ / ▶: ASCII
        self.assertFalse(scr.glyphs_for('ascii').unicode)


# ---- the browser and its state

class Browsing(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp(prefix='pick_'))
        for p in ('Games/Zelda/base/exefs', 'Games/Zelda/base/romfs', 'Games/Other', 'Games/Upd/updates/65536/exefs', 'Games/Upd/updates/65536/romfs', '.hidden'):
            (self.d / p).mkdir(parents=True)
        (self.d / 'Games/Zelda/base/exefs/main.npdm').write_bytes(b'META')
        (self.d / 'Games/Upd/updates/65536/exefs/main.npdm').write_bytes(b'META')
        (self.d / 'Games/Ocarina.NUP').write_bytes(b'x' * 100); (self.d / 'Games/notes.txt').write_text('x')

    def tearDown(self):
        import shutil; shutil.rmtree(self.d, ignore_errors=True)

    def test_listing(self):
        b = browser.Browser(self.d / 'Games')
        self.assertEqual([(e.kind, e.name) for e in b.shown()], [('up', '..'), ('dir', 'Other'), ('game', 'Upd'), ('game', 'Zelda'), ('nup', 'Ocarina.NUP')])
        self.assertEqual(b.shown()[-1].size, 100)

    def test_filter_moves_and_pick(self):
        b = browser.Browser(self.d / 'Games')
        b.type('o')
        self.assertEqual([e.name for e in b.shown()], ['Other', 'Ocarina.NUP'])
        b.move('end'); self.assertEqual(b.enter(), self.d / 'Games/Ocarina.NUP')
        b.erase(); self.assertEqual(b.filter, '')
        b.move('home'); b.move('down'); self.assertEqual(name(b.current()), 'Other')
        self.assertIsNone(b.enter()); self.assertEqual(b.folder, self.d / 'Games/Other')
        self.assertTrue(b.erase()); self.assertEqual(b.folder, self.d / 'Games'); self.assertEqual(name(b.current()), 'Other')   # up: the cursor on the folder left
        b.move('pgdn'); self.assertEqual(b.cur, len(b.shown()) - 1); b.move('pgup'); self.assertEqual(b.cur, 0)
        self.assertEqual(b.go(self.d / 'nowhere') != '', True); self.assertEqual(b.folder, self.d / 'Games')

    def test_window(self):
        for i in range(30): (self.d / f'Games/Other/d{i:02d}').mkdir()
        b = browser.Browser(self.d / 'Games/Other')
        b.move('end'); top, items = b.window(10)
        self.assertEqual(items[-1].name, 'd29'); self.assertEqual(len(items), 10)
        b.move('home'); top, items = b.window(10); self.assertEqual(top, 0)

    def test_complete(self):
        g = str(self.d / 'Games') + os.sep
        self.assertEqual(browser.complete(g + 'Ze'), g + 'Zelda' + os.sep)
        self.assertEqual(browser.complete(g + 'O'), g + 'O')   # Ocarina.NUP and Other: their common start only
        self.assertEqual(browser.complete(g + 'Oc'), g + 'Ocarina.NUP')

    @unittest.skipIf(os.name == 'nt', "the shell's quoting is POSIX")
    def test_dropped_path(self):
        """A file dragged onto the terminal comes in the shell's quoting"""
        (self.d / 'Switch 2').mkdir(); (self.d / "it's").mkdir()
        self.assertEqual(browser.unquote(str(self.d) + '/Switch\\ 2 '), str(self.d / 'Switch 2'))
        self.assertEqual(browser.unquote("'" + str(self.d) + "/it'\\''s'"), str(self.d / "it's"))
        self.assertEqual(browser.unquote('"' + str(self.d / 'Switch 2') + '"'), str(self.d / 'Switch 2'))
        self.assertEqual(browser.unquote(str(self.d / 'Switch 2')), str(self.d / 'Switch 2'))   # a path as it is stays as it is
        self.assertEqual(browser.unquote("/x/it's"), "/x/it's")   # an open quote: no shell quoting

    def test_state(self):
        f = self.d / 'cfg/anka-native/prepare.json'
        self.assertEqual(browser.load_state(f), ({}, ''))
        st = browser.remember({}, self.d / 'Games')
        st = browser.remember(st, self.d / 'Games/Other'); st = browser.remember(st, self.d / 'Games')
        self.assertEqual(st['last'], str(self.d / 'Games')); self.assertEqual(st['recent'], [str(self.d / 'Games'), str(self.d / 'Games/Other')])
        self.assertEqual(browser.save_state(st, f), '')
        self.assertEqual(browser.load_state(f), (st, ''))
        f.write_text('{broken'); d, why = browser.load_state(f)
        self.assertEqual(d, {}); self.assertIn(str(f), why)
        labels = [l for l, _ in browser.places(st)]
        self.assertEqual(labels[:3], ['Home', 'Games', 'Other'])


# ---- the packer's output

class Output(unittest.TestCase):
    def test_spellings(self):
        e = progress.parse('progress 2/3 main'); self.assertEqual((e.kind, e.done, e.total, e.stage), ('progress', 2, 3, 'main'))
        e = progress.parse('progress: 96/186583'); self.assertEqual((e.done, e.total, e.stage), (96, 186583, ''))
        e = progress.parse('progress: 0/1 main\r\n'); self.assertEqual((e.done, e.total, e.stage), (0, 1, 'main'))
        e = progress.parse('progress 12034 42854 translate'); self.assertEqual((e.done, e.total, e.stage), (12034, 42854, 'translate'))
        self.assertEqual(progress.parse('step shaders').name, 'shaders')
        self.assertEqual(progress.parse('progressive 1/2').kind, 'line')
        e = progress.parse('error: no_space\t/x'); self.assertEqual((e.kind, e.code, e.args), ('error', 'no_space', ['/x']))
        self.assertEqual(progress.parse('[RED] boom').text, 'boom')
        self.assertEqual(progress.parse('done: /p/android-pack (2029.4 MiB); RomFS x and 0 DLC stay in the game folder').path, '/p/android-pack')

    def test_state_and_failure(self):
        st = progress.State()
        for l in ['step identify', 'step rewrite', 'progress 1/3 sdk', '[RED] main build x is not the profile\'s build y: build-gated cuts do not apply',
                  'step split', 'progress: 5/10', 'some line', 'error: shaders_other_build\t3\t42854', '[RED] the dump\'s shaders lack 3 of the 42854']:
            st.feed(l)
        self.assertEqual(st.step, 2); self.assertEqual(st.prog[1], (1, 3, 'sdk')); self.assertEqual(st.prog[2], (5, 10, ''))
        self.assertEqual(st.warnings, 2)
        self.assertEqual(st.failure(), (('shaders_other_build', ['3', '42854']), "the dump's shaders lack 3 of the 42854"))
        st.feed('[RED] another'); self.assertEqual(st.failure(), (None, 'another'))   # an error: line not right before the last [RED] line is not its
        self.assertIn('42854', model.plain_error('shaders_other_build', ['3', '42854'], 'x'))
        self.assertEqual(model.plain_error('a_new_code', ['1'], 'the sentence'), 'the sentence')   # a code of another packer: its sentence
        self.assertEqual(model.plain_error('no_space', [], 'the sentence'), 'the sentence')       # too few arguments


    def test_failure_words(self):
        """A failure without a code: killed by a signal (SIGKILL: the out-of-memory killer), a permission error in a traceback, else a bug to report"""
        p = FakePlan()
        lv, _ = live_at(p, RUN_LINES)   # (its last [RED] line is a warning of the run, not why it ended)
        end = lambda rc: '\n'.join(t for t, _ in app.end_lines(app.Outcome('failed', lv, p, rc, 100.0, Path('/x.log'))))
        self.assertIn('Not enough memory: pack_android.py was stopped', end(-9))
        self.assertIn('signal SIGTERM', end(-15)); self.assertNotIn('older library', end(-15).split('Its last lines')[0])
        lp, _ = live_at(p, RUN_LINES + ['Traceback (most recent call last):', "PermissionError: [Errno 13] Permission denied: '/games/ro/pack/rewrite_main.log'"])
        txt = '\n'.join(t for t, _ in app.end_lines(app.Outcome('failed', lp, p, 1, 100.0, Path('/x.log'))))
        self.assertIn('does not let the packer use /games/ro/pack/rewrite_main.log', txt); self.assertNotIn('Fix what it says', txt)   # (its own advice)
        self.assertNotIn('Traceback', txt)
        lb, _ = live_at(p, RUN_LINES[:-1] + ['Traceback (most recent call last):', 'AssertionError'])
        txt = '\n'.join(t for t, _ in app.end_lines(app.Outcome('failed', lb, p, 1, 100.0, Path('/x.log'))))
        self.assertIn('no advice for', txt); self.assertNotIn('Fix what it says', txt)

    def test_no_space_in_plain_words(self):
        """A failure the user can fix (no space, in the rewrite: the packer names only the tool): its own text with the folder, the packer's sentence pointing
        at the tool's log, no second advice, and of the traceback before it only what was raised"""
        p = FakePlan()
        lv, _ = live_at(p, RUN_LINES[:5] + ['  sdk: rewriting /x/sdk (in process)', "function patch 'x' skipped: 0 matching functions", 'Traceback (most recent call last):',
                                           '  File "/r/dm/tools/patch_nso.py", line 9, in <module>', '    out.write(b)', '    ~~~~~~~~~^^^',
                                           "OSError: [Errno 28] No space left on device: '/games/pack/sdk.staging/x'", 'error: no_space\tpatch_nso.py',
                                           '[RED] patch_nso.py exit code 1 on sdk (the last lines of rewrite_sdk.log above)'])
        for mode in ('lines', 'screen'):
            if mode == 'lines': txt = '\n'.join(t for t, _ in app.end_lines(app.Outcome('failed', lv, p, 1, 100.0, Path('/x.log'))))
            else: sc = scr.Screen(80, 24, scr.Glyphs(True)); app.draw_end(sc, app.Outcome('failed', lv, p, 1, 100.0, Path('/x.log'))); txt = sc.text()
            self.assertIn(f'Not enough free space ({p.out})', txt); self.assertIn(f'its log: {p.out}/rewrite_sdk.log', txt.replace('\n  ', ' '))
            self.assertNotIn('above', txt); self.assertNotIn('Fix what it says', txt); self.assertNotIn('report it', txt)
            self.assertNotIn('Traceback', txt); self.assertNotIn('File "', txt); self.assertNotIn('~~~', txt); self.assertNotIn('error: no_space', txt)
            self.assertIn('OSError: [Errno 28] No space left on device', txt); self.assertIn('0 matching functions', txt)

    def test_plain_confirm_has_no_keys(self):
        p = FakePlan()
        p.x.vers = {0: None, 131072: None}
        full, plain = ' '.join(str(v) for _, v, _ in app.plan_lines(p)), ' '.join(str(v) for _, v, _ in app.plan_lines(p, keys=False))
        self.assertIn('(+/-)', full); self.assertIn('v: another', full)
        self.assertNotIn('(+/-)', plain); self.assertNotIn('v: another', plain)

    def test_whole_job_on_resume(self):
        """A resumed run's whole-job bar goes on from where the stopped run's was (what it finished counts as done), never back towards 0 (the review's round 1
        stopped at 21.7 %, round 2 then showed 2.2 %); a resumed translation's count includes the earlier run's shaders, counted once"""
        fresh = FakePlan()
        a, _ = live_at(fresh, ['step identify', 'step rewrite', 'progress 3/3 main', 'step split', 'progress: 9/9', 'step shaders', 'progress 0 42854 translate',
                               'progress 3384 42854 translate'])
        p = FakePlan(translated=3384, kept=('main', 'subsdk0', 'sdk'), split=False)
        b0, _ = live_at(p, [])
        b, _ = live_at(p, ['step identify', 'step rewrite', 'step shaders', 'progress 3384 42854 translate'])
        self.assertGreater(a.overall(), 0.05)
        self.assertAlmostEqual(b0.overall(), a.overall(), delta=0.01); self.assertAlmostEqual(b.overall(), a.overall(), delta=0.01)
        c, _ = live_at(p, ['step identify', 'step rewrite', 'step shaders', 'progress 23119 42854 translate'])
        mid = (c.kept + c.weights[0] + 0.5 * (42854 - 3384) * p.shader_s()) / (sum(c.weights) + c.kept)   # half of this run's translation
        self.assertAlmostEqual(c.overall(), mid, places=3)
        d, _ = live_at(FakePlan(kept=('main',)), ['step identify', 'step rewrite', 'progress 0/2 sdk'])
        self.assertAlmostEqual(d.overall(), (d.kept + 2.0) / (sum(d.weights) + d.kept), places=6); self.assertAlmostEqual(d.weights[1], eta.REWRITE_S * 2 / 3)


# ---- the packer as a child process

class Runs(unittest.TestCase):
    def setUp(self): self.d = Path(tempfile.mkdtemp(prefix='run_'))

    def tearDown(self):
        import shutil
        for p in self.d.rglob('*'):
            if p.is_dir(): p.chmod(0o755)
        shutil.rmtree(self.d, ignore_errors=True)

    def run_to_end(self, r, limit=30):
        got, end = [], time.monotonic() + limit
        while not r.finished():
            self.assertLess(time.monotonic(), end, 'the run does not end')
            got += [l for _, l in r.lines()[0]]; time.sleep(0.05)
        got += [l for _, l in r.lines()[0]]; r.close()
        return got

    def test_worker_left_behind(self):
        """The packer exits while a worker it started still holds its output: the run ends GRACE after the exit, by the packer's exit code, the worker ended"""
        code = ('import subprocess, sys; w = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(600)"]); print(w.pid, flush=True); '
                'print("done: x (1.0 MiB)", flush=True)')
        t0 = time.monotonic()
        r = runner.Run([sys.executable, '-c', code], self.d / 'logs/a.log')
        got = self.run_to_end(r)
        self.assertEqual(r.p.returncode, 0); self.assertLess(time.monotonic() - t0, 20)
        self.assertIn('done: x (1.0 MiB)', got)
        if os.name != 'nt':
            time.sleep(0.2)
            with self.assertRaises(ProcessLookupError): os.kill(int(got[0]), 0)

    def test_log_cannot_be_written(self):
        """The log's drive fills up during the run: the lines still come and the run ends; log_error says why the log stops"""
        import errno
        class Full:
            def __init__(self, f): self.f, self.n = f, 0
            def write(self, s):
                self.n += 1
                if self.n > 3: raise OSError(errno.ENOSPC, 'No space left on device')
                return self.f.write(s)
            def flush(self): self.f.flush()
            def close(self): self.f.close()
        r = runner.Run([sys.executable, '-c', 'for i in range(100000): print(f"progress {i} 100000 translate")'], self.d / 'b.log')
        r.log = Full(r.log)   # pyright: ignore[reportAttributeAccessIssue]  # (a stand-in for the log file)
        got = self.run_to_end(r)
        self.assertEqual(len(got), 100000); self.assertEqual(r.p.returncode, 0)
        assert r.log_error is not None; self.assertEqual(r.log_error.errno, errno.ENOSPC)

    @unittest.skipIf(os.name == 'nt' or hasattr(os, 'geteuid') and os.geteuid() == 0, 'POSIX permissions (root ignores them)')
    def test_folder_not_writable(self):
        ro = self.d / 'ro'; ro.mkdir(); ro.chmod(0o555)
        self.assertIn('Cannot write in', model.writable(ro / 'pack'))
        self.assertEqual(model.writable(self.d / 'rw/pack'), '')
        with self.assertRaises(OSError): runner.Run([sys.executable, '-c', 'pass'], ro / 'prepare-logs/x.log')

    def test_lock(self):
        a, b = model.Lock(self.d / 'x.lock'), model.Lock(self.d / 'x.lock')
        self.assertTrue(a.take()); self.assertFalse(b.take())
        code = f'import sys; sys.path.insert(0, {str(REPO / "dm/tools")!r}); from prepare_tui import model; print(model.Lock({str(self.d / "x.lock")!r}).take())'
        self.assertEqual(subprocess.run([sys.executable, '-c', code], capture_output=True, text=True).stdout.strip(), 'False')   # another process: refused too
        assert a.fd is not None; os.close(a.fd)
        self.assertEqual(subprocess.run([sys.executable, '-c', code], capture_output=True, text=True).stdout.strip(), 'True')   # released with its holder


class ZbicCheck(unittest.TestCase):
    """The check of the ZBIC decoder (checks.check_zbic) on a copy of dm/third_party/zstd-zbic without its build folder, so nso.zbic_build builds there: a C
    compiler found (built, loaded and decoding), the build then cached (at once, no compiler asked for), no compiler (the system's install line), a compiler that
    fails, a DM_ZBIC_LIB that does not load, and the cases below"""
    def setUp(self):
        import shutil
        from prepare_tui import checks
        self.checks = checks
        self.d = Path(tempfile.mkdtemp(prefix='zbic_'))
        self.src = self.d / 'zstd-zbic'
        shutil.copytree(nso.ZBIC_SRC, self.src, ignore=lambda d, names: ['build'] if Path(d) == Path(nso.ZBIC_SRC) else [])
        self.saved = (nso.ZBIC_SRC, {k: os.environ.get(k) for k in ('CC', 'PATH', nso.ZBIC_LIB_ENV)})
        nso.ZBIC_SRC = str(self.src)
        os.environ.pop(nso.ZBIC_LIB_ENV, None)

    def tearDown(self):
        import shutil
        nso.ZBIC_SRC = self.saved[0]
        for k, v in self.saved[1].items():
            if v is None: os.environ.pop(k, None)
            else: os.environ[k] = v
        shutil.rmtree(self.d, ignore_errors=True)

    def no_compiler(self):
        """PATH without any C compiler (an empty folder) and no CC"""
        (self.d / 'empty').mkdir(exist_ok=True)
        os.environ['PATH'] = str(self.d / 'empty'); os.environ.pop('CC', None)

    @unittest.skipUnless(os.environ.get('CC') or any(__import__('shutil').which(c) for c in ('cc', 'clang', 'gcc')), 'no C compiler on this machine')
    def test_found_then_cached(self):
        c = self.checks.check_zbic('debian')
        self.assertTrue(c.ok, c.found); self.assertEqual(c.fix, [])
        self.assertIn('built now', c.found); self.assertIn(str(self.src / 'build'), c.found)
        self.no_compiler()   # the build is there: the check loads it, with no compiler and at once
        t0 = time.monotonic()
        c = self.checks.check_zbic('debian')
        self.assertLess(time.monotonic() - t0, 1.0)
        self.assertTrue(c.ok, c.found); self.assertIn('built earlier', c.found)

    def test_missing(self):
        self.no_compiler()
        for sysname, line in (('debian', 'sudo apt install build-essential'), ('fedora', 'sudo dnf install gcc'), ('arch', 'sudo pacman -S gcc'), ('mac', 'xcode-select --install'),
                              ('windows', 'LLVM-MinGW'), ('linux', "distribution's gcc or clang")):
            c = self.checks.check_zbic(sysname)
            self.assertFalse(c.ok); self.assertIn('no C compiler', c.found)
            self.assertTrue(any(line in f for f in c.fix), (sysname, c.fix)); self.assertIn('DM_ZBIC_LIB', c.fix[-1])
        self.assertFalse((self.src / 'build').exists() and any((self.src / 'build').rglob('libzbicdec*')))

    @unittest.skipIf(os.name == 'nt', 'a POSIX shell script as the compiler')
    def test_build_fails(self):
        cc = self.d / 'badcc'; cc.write_text('#!/bin/sh\necho "badcc: error: this compiler builds nothing" >&2\nexit 1\n'); cc.chmod(0o755)
        os.environ['CC'] = str(cc)
        c = self.checks.check_zbic('fedora')
        self.assertFalse(c.ok); self.assertIn('does not build', c.found); self.assertIn('badcc: error: this compiler builds nothing', c.detail)   # (its own line)
        self.assertIn('did not build', c.fix[0]); self.assertIn('sudo dnf install gcc', c.fix)

    def test_named_library_does_not_load(self):
        os.environ[nso.ZBIC_LIB_ENV] = str(self.d / 'nothere.so')
        c = self.checks.check_zbic('debian')
        self.assertFalse(c.ok); self.assertIn('does not load', c.found); self.assertIn('DM_ZBIC_LIB', c.fix[0])

    def test_cc_unclosed_quote(self):
        """A $CC that cannot be split into words (shlex: no closing quotation): the check says so, never a traceback"""
        os.environ['CC'] = "gcc 'x"
        c = self.checks.check_zbic('arch')
        self.assertFalse(c.ok); self.assertIn('cannot be read as a command', c.found); self.assertIn('quotes', c.fix[0])

    @unittest.skipIf(os.name == 'nt' or hasattr(os, 'geteuid') and os.geteuid() == 0, 'POSIX permissions (root ignores them)')
    def test_folder_not_writable(self):
        """The build folder cannot be made: its own fix (not the compiler's), no exception out of the check"""
        self.src.chmod(0o555)
        try: c = self.checks.check_zbic('arch')
        finally: self.src.chmod(0o755)
        self.assertFalse(c.ok); self.assertIn('cannot be written', c.fix[0]); self.assertNotIn('pacman', ' '.join(c.fix))

    def test_run_all_has_it(self):
        """The real run_all checks the decoder (the end-to-end tests put their own checks in its place)"""
        from unittest import mock
        mark = self.checks.Check('zbic', False, 'x')
        with mock.patch.object(self.checks, 'check_zbic', return_value=mark): self.assertIn(mark, self.checks.run_all('arch'))

    def fake_cc(self, body):
        """A compiler for build.sh that writes body (a shell command with $out, the -o argument) and exits 0"""
        cc = self.d / 'fakecc'
        cc.write_text('#!/bin/sh\nout=\nwhile [ $# -gt 0 ]; do [ "$1" = -o ] && out=$2; shift; done\n' + body + '\n'); cc.chmod(0o755)
        return str(cc)

    def real_cc(self):
        import shutil
        return next((c for c in ('cc', 'clang', 'gcc') if shutil.which(c)), None)

    @unittest.skipIf(os.name == 'nt', 'a POSIX shell script as the compiler')
    def test_junk_build_removed(self):
        """A compiler that exits 0 but writes no real library: missing with the other-computer fix, and the junk removed (nso.zbic_build would take it as done)"""
        os.environ['CC'] = self.fake_cc('printf junkjunk > "$out"')
        c = self.checks.check_zbic('arch')
        self.assertFalse(c.ok); self.assertIn('cannot be used here', c.found); self.assertIn('another kind of computer', c.fix[0])
        self.assertEqual(self.checks.zbic_libs(), set())

    @unittest.skipIf(os.name == 'nt', 'a POSIX shell script as the compiler')
    def test_earlier_junk_built_again(self):
        """A junk build an earlier compiler left in the folder: the check removes it and builds again with the compiler found now"""
        cc = self.real_cc()
        if not cc: self.skipTest('no C compiler on this machine')
        os.environ['CC'] = self.fake_cc('printf junkjunk > "$out"')
        junk = nso.zbic_build()   # (as the rewriter would have left it)
        self.assertEqual(Path(junk).read_bytes(), b'junkjunk')
        os.environ['CC'] = cc
        c = self.checks.check_zbic('arch')
        self.assertTrue(c.ok, c.found); self.assertIn('built now', c.found); self.assertNotEqual(Path(junk).read_bytes(), b'junkjunk')

    @unittest.skipIf(os.name == 'nt', 'a POSIX shell script as the compiler')
    def test_loads_but_does_not_decode(self):
        """A library with both entry points that does not decode ZBIC: missing, as the rewrite would fail; as DM_ZBIC_LIB and as a build in the folder"""
        cc = self.real_cc()
        if not cc: self.skipTest('no C compiler on this machine')
        stub = self.d / 'stub.c'
        stub.write_text('long zbic_decompress(void *d, unsigned long n, const char *s, unsigned long m) { return -1; }\n'
                        'const char *zbic_error_name(long e) { return "stub"; }\n')
        os.environ['CC'] = self.fake_cc(f'{cc} -shared -fPIC -o "$out" {stub}')
        c = self.checks.check_zbic('arch')
        self.assertFalse(c.ok); self.assertIn('does not decode ZBIC', c.found); self.assertEqual(self.checks.zbic_libs(), set())
        lib = self.d / 'libstub.so'
        subprocess.run([cc, '-shared', '-fPIC', '-o', str(lib), str(stub)], check=True)
        os.environ.pop('CC'); os.environ[nso.ZBIC_LIB_ENV] = str(lib)
        c = self.checks.check_zbic('arch')
        self.assertFalse(c.ok); self.assertIn('does not decode ZBIC', c.found); self.assertIn('DM_ZBIC_LIB', c.fix[0])


# ---- the estimator

def feed_rate(e, step, t0, t1, total, d0, rate, every=5.0, stage='translate'):
    """Progress lines of step every seconds from t0 to t1 at rate units a second, from d0; → (time, done) at the end"""
    t, d = t0, d0
    while t < t1:
        e.progress(step, int(d), total, stage, t)
        t += every; d = min(total, d + rate * every)
    return t, d


class Estimator(unittest.TestCase):
    def test_steady(self):
        e = eta.Eta(10000, 10000, 0.1); e.start(0); e.step_to(eta.SHADERS, 0)
        t, d = feed_rate(e, eta.SHADERS, 0, 60, 10000, 0, 10)
        self.assertEqual(e.minutes(t), -1)   # too early: nothing shown
        t, d = feed_rate(e, eta.SHADERS, t, 400, 10000, d, 10)
        want = (10000 - d) / 10 + 10000 * eta.LIBRARY_S + eta.PACK_S
        self.assertLess(abs(e.seconds(t) - want) / want, 0.05)

    def test_falling_rate(self):
        e = eta.Eta(20000, 20000, 0.1); e.start(0); e.step_to(eta.SHADERS, 0)
        t, d = feed_rate(e, eta.SHADERS, 0, 600, 20000, 0, 20)
        t, d = feed_rate(e, eta.SHADERS, t, 1200, 20000, d, 10)   # the rate halves (heat, another program)
        for _ in range(40): e.seconds(t)   # (the smoothing follows; the window already holds only the slower rate)
        want = (20000 - d) / 10 + 20000 * eta.LIBRARY_S + eta.PACK_S
        self.assertLess(abs(e.raw() - want) / want, 0.05)

    def test_stall(self):
        e = eta.Eta(10000, 10000, 0.1); e.start(0); e.step_to(eta.SHADERS, 0)
        t, d = feed_rate(e, eta.SHADERS, 0, 300, 10000, 0, 10)
        self.assertGreater(e.minutes(t), 0)
        self.assertEqual(e.minutes(t + eta.STALL_S + 10), -1)   # no progress for STALL_S: no estimate
        t, d = feed_rate(e, eta.SHADERS, t + eta.STALL_S + 10, t + eta.STALL_S + 40, 10000, d, 10)
        self.assertGreater(e.minutes(t), 0)   # going again: the rate measured before the stall, the stall not counted as slow work
        self.assertLess(abs(e.raw() - ((10000 - d) / 10 + 10000 * eta.LIBRARY_S + eta.PACK_S)) / e.raw(), 0.1)

    def test_library_and_pack(self):
        e = eta.Eta(10000, 10000, 0.1); e.start(0); e.step_to(eta.SHADERS, 0)
        t, d = feed_rate(e, eta.SHADERS, 0, 1100, 10000, 0, 10)
        e.library = round(130 / eta.LIBRARY_S)   # (a library of 130 s: counted down until LATE_S is left, which it then says until it has taken twice its figure)
        lib = e.library * eta.LIBRARY_S
        e.progress(eta.SHADERS, 10000, 10000, 'library', t)
        self.assertAlmostEqual(e.raw(), lib + eta.PACK_S)
        e.clock(t + 5); self.assertAlmostEqual(e.raw(), lib - 5 + eta.PACK_S)
        e.clock(t + 125); self.assertAlmostEqual(e.raw(), eta.LATE_S + eta.PACK_S)
        e.step_to(eta.PACK, t + 130); self.assertAlmostEqual(e.raw(), eta.PACK_S)
        e.clock(t + 130 + 3 * eta.PACK_S + 61); self.assertTrue(math.isnan(e.raw()))   # far over its figure: nothing

    def test_resume_baseline(self):
        """A run started again at 30,000 of 42,854: those are not this run's work; the rate comes from its own steps forward"""
        e = eta.Eta(12854, 42854, 0.1); e.mix((42854, 27002)); e.start(0); e.step_to(eta.SHADERS, 0)
        e.progress(eta.SHADERS, 30000, 42854, 'translate', 70)   # the tool's set-up before its first line
        self.assertEqual(e.minutes(70), -1)
        t, d = feed_rate(e, eta.SHADERS, 75, 400, 42854, 30000, 5)
        rest = (27002 + (42854 - 27002) * eta.VERTEX_COST - (27002 + (d - 27002) * eta.VERTEX_COST)) / (5 * eta.VERTEX_COST) + 42854 * eta.LIBRARY_S + eta.PACK_S
        self.assertLess(abs(e.raw() - rest) / rest, 0.05)

    def test_split_hands_over(self):
        e = eta.Eta(42854, 42854, 0.07); e.start(0); e.step_to(eta.SPLIT, 0)
        e.progress(eta.SPLIT, 0, 1, 'main', 1)
        self.assertTrue(math.isnan(e.raw()))   # the split's start: unknown
        t, d = feed_rate(e, eta.SPLIT, 30, 300, 186583, 0, 500, stage='')
        self.assertAlmostEqual(e.raw(), (186583 - d) / 500 + 42854 * 0.07 + 42854 * eta.LIBRARY_S + eta.PACK_S, delta=60)
        e.step_to(eta.SHADERS, t)
        e.progress(eta.SHADERS, 0, 42854, 'translate', t + 30)
        self.assertAlmostEqual(e.raw(), 42854 * 0.07 + 42854 * eta.LIBRARY_S + eta.PACK_S)   # the figure until the translation has its own rate

    def test_guess(self):
        """The time from the fixed figure (the split, the translation before its own rate) is called a first guess; a measured one is not"""
        e = eta.Eta(42854, 42854, 0.07); e.start(0); e.step_to(eta.SPLIT, 0)
        feed_rate(e, eta.SPLIT, 30, 300, 186583, 0, 500, stage='')
        e.raw(); self.assertTrue(e.guess)
        e.step_to(eta.SHADERS, 300); e.progress(eta.SHADERS, 0, 42854, 'translate', 330)
        e.raw(); self.assertTrue(e.guess)
        feed_rate(e, eta.SHADERS, 335, 800, 42854, 0, 30)
        e.raw(); self.assertFalse(e.guess)
        self.assertTrue(model.eta_words(20, now=0, guess=True).endswith(': a first guess'))

    def test_guess_not_carried(self):
        """A time still mostly the figure's is called a guess, also after the translation's own rate replaced the figure, until it is near the measured one: the
        review's run, a computer 30 % slower than the figure, showed 19 minutes without the label while 24 were measured"""
        e = eta.Eta(42854, 42854, 0.034); e.start(0); e.step_to(eta.SPLIT, 0)
        feed_rate(e, eta.SPLIT, 30, 300, 186583, 0, 700, stage='')
        self.assertTrue(e.seconds(300) > 0 and e.guess)   # (the split's own rate, then the shaders at the figure)
        e.step_to(eta.SHADERS, 300)
        t, was = 300.0, None
        while t < 800:
            e.progress(eta.SHADERS, int((t - 300) * 22), 42854, 'translate', t)   # 22 a second, against the figure's 29
            s = e.seconds(t)
            if was and not e.guess:
                r = e.raw(); self.assertLessEqual(abs(s - r), eta.GUESS_NEAR * r + 1); break
            was = e.guess; t += 5
        else: self.fail('the label never went')

    def test_no_total(self):
        e = eta.Eta(0, 0, 0.1); e.start(0); e.step_to(eta.SHADERS, 0)
        feed_rate(e, eta.SHADERS, 0, 300, 0, 0, 0)
        self.assertEqual(e.minutes(300), -1)
        e = eta.Eta(100, 100, 0.1); e.start(0); e.step_to(eta.SHADERS, 0)
        t, _ = feed_rate(e, eta.SHADERS, 0, 300, 100000, 0, 10)
        e.progress(eta.SHADERS, 0, 5000, 'translate', t)   # another total: start over
        self.assertTrue(math.isnan(e.raw()))

    def test_words(self):
        self.assertEqual(eta.rnd(30), 0); self.assertEqual(eta.rnd(61), 1); self.assertEqual(eta.rnd(19.4 * 60), 19); self.assertEqual(eta.rnd(23 * 60), 25)
        self.assertEqual(eta.keep(12.45 * 60, 12), 12)   # near the boundary: kept
        self.assertEqual(model.eta_words(12, now=0).split(',')[0], 'About 12 minutes left')
        self.assertTrue(model.eta_words(75, now=0).startswith('About 1 hour 15 minutes left, done around '))
        self.assertTrue(model.eta_words(0, now=0).startswith('Less than a minute left'))
        self.assertEqual(model.eta_words(-1), '')
        self.assertEqual(model.minutes_words(90 * 60), '1 hour 30 minutes')

    def test_mix(self):
        self.assertEqual(eta.shader_mix(REPO / f'dm/games/{OOT}/shader_map.tsv'), (42854, 27002))


def read_trace(path):
    """The real run's trace: (end, mix, workers, [(seconds, step, stage, done, total)]) (prepare_data/prepare-pc-oot.trace.txt's header says what it is)"""
    end, mix, workers, rows = None, None, None, []
    for l in path.read_text().splitlines():
        if not l or l.startswith('#'): continue
        c = l.split()
        if c[0] == 'end': end = float(c[1])
        elif c[0] == 'mix': mix = (int(c[1]), int(c[2]))
        elif c[0] == 'workers': workers = int(c[1])
        else: rows.append((float(c[0]), c[1], '' if c[2] == '-' else c[2], int(c[3]), int(c[4])))
    return end, mix, workers, rows


def replay(path, every=10.0):
    """The estimator through the trace, asked every `every` seconds: [(seconds since the start, estimate or nan, true time left)]"""
    end, mix, workers, rows = read_trace(path)
    assert end is not None and mix is not None and workers, f'{path}: no end, mix or workers line'
    e = eta.Eta(mix[0], mix[0], eta.SHADER_CPU_S / workers); e.mix(mix); e.start(0)   # as model.Live makes it for a run from the start
    steps = {s: i for i, s in enumerate(progress.STEPS)}
    out, k, t, cur = [], 0, 0.0, -1
    while t < end:
        while k < len(rows) and rows[k][0] <= t:
            s, step, stage, d, tot = rows[k]
            i = steps[step]
            if i != cur: e.step_to(i, s); cur = i
            if tot >= 0: e.progress(i, d, tot, stage, s)
            k += 1
        e.minutes(t)
        out.append((t, e.last_seconds, end - t))
        t += every
    return out


class Replay(unittest.TestCase):
    TRACE = DATA / 'prepare-pc-oot.trace.txt'

    @unittest.skipUnless(TRACE.is_file(), 'no trace')
    def test_real_run(self):
        """The real run on this computer (2026-11-14, the trace's header): the estimate's error at 10 / 25 / 50 / 75 / 90 % of the run within the bounds"""
        r = replay(self.TRACE)
        end = r[-1][0]
        for pct, bound in BOUNDS.items():
            t, est, true = min(r, key=lambda x: abs(x[0] - end * pct / 100))
            print(f'replay {pct}%: estimate {est / 60 if est == est else float("nan"):.1f} min, true {true / 60:.1f} min')
            self.assertFalse(math.isnan(est), f'no estimate at {pct}%')
            self.assertLess(abs(est - true) / true, bound, f'{pct}%: {est:.0f} s against {true:.0f} s')
        shown = [(t, est, true) for t, est, true in r if not math.isnan(est)]
        self.assertLess(shown[0][0], 0.15 * end, 'the first estimate comes late')   # (the split's own rate, after its scan has gone on for a while)
        self.assertFalse(any(t < 60 for t, _, _ in shown), 'an estimate before anything was measured')
        err = [abs(est - true) / true for t, est, true in shown if true > 120]
        print(f'replay: first estimate at {shown[0][0]:.0f} s of {end:.0f} s, mean error {sum(err) / len(err) * 100:.1f} %, largest {max(err) * 100:.1f} %')
        self.assertLess(sum(err) / len(err), 0.10)


BOUNDS = {25: 0.25, 50: 0.15, 75: 0.15, 90: 0.30}


# ---- end to end: a made-up dump and a stand-in packer

FAKE_PACKER = textwrap.dedent('''\
    import os, sys, time
    a = sys.argv[1:]
    out = a[a.index('--out') + 1]
    mode = os.environ.get('FAKE_MODE', 'ok'); slow = float(os.environ.get('FAKE_SLOW', '0'))
    print('step identify', flush=True)
    print('0400486024DEC000 The Legend of Zelda: Ocarina of Time version 131072: ' + a[0], flush=True)
    print('step rewrite', flush=True)
    for i in range(4): print(f'progress {i}/3 main', flush=True); time.sleep(0.02)
    print('step split', flush=True); print('progress: 0/1 main', flush=True)
    for i in range(0, 1001, 250): print(f'progress: {i}/1000', flush=True); time.sleep(0.02)
    print('step shaders', flush=True)
    if mode == 'fail':
        print('Traceback (most recent call last):', flush=True)
        print('error: no_space\\t' + out, file=sys.stderr, flush=True)
        sys.exit('[RED] [Errno 28] No space left on device: ' + repr(out))
    if mode == 'slow':
        import subprocess
        w = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(600)'])   # a worker in the packer's group
        open(os.environ['FAKE_PIDS'], 'w').write(f'{os.getpid()} {w.pid}')
    for i in range(0, 501, 50):
        print(f'progress {i} 500 translate', flush=True); time.sleep(0.02 + slow)
    print('progress 500 500 library', flush=True); print('step pack', flush=True)
    os.makedirs(out, exist_ok=True); open(os.path.join(out, 'pack.tsv'), 'w').write('format\\t1\\n')
    print(f'done: {out} (0.0 MiB); RomFS x and 0 DLC stay in the game folder', flush=True)
''')

# The interface with the stand-ins: no checks of this computer (each is tested by what it reads; here only the flow), the stand-in packer, no space figures
WRAPPER = textwrap.dedent('''\
    import sys
    sys.path.insert(0, {tools!r})
    from prepare_tui import app, checks, model
    from prepare_tui.checks import Check
    import os
    checks.run_all = lambda: [Check('Python 3.14', True, 'stand-in')]
    if os.environ.get('ZBIC_SRC_COPY'):   # the real decoder check, on a copy of its sources (ZbicCheck)
        checks.nso.ZBIC_SRC = os.environ['ZBIC_SRC_COPY']
        checks.run_all = lambda: [Check('Python 3.14', True, 'stand-in'), checks.check_zbic(checks.system())]
    model.PACK_ANDROID = {packer!r}
    model.PKG_B = model.RESULT_B = model.CACHE_B = model.LIB_B = model.MODULE_X = 0
    sys.argv[0] = 'prepare.py'
    exec(open({prepare!r}).read().replace("if __name__ == '__main__':", 'if True:'))
''')


def make_dump(root):
    """An Ocarina of Time dump folder of a few bytes: main.npdm with the title ID, the profile's modules as NSO headers with their builds, an empty RomFS"""
    g = root / 'Ocarina'
    ex = g / 'updates/131072/exefs'; ex.mkdir(parents=True); (g / 'updates/131072/romfs').mkdir(); (g / 'updates/131072/romfs/x').write_text('x')
    npdm = bytearray(0x100); npdm[:4] = b'META'; struct.pack_into('<II', npdm, 0x70, 0x80, 0x40); npdm[0x80:0x84] = b'ACI0'
    struct.pack_into('<Q', npdm, 0x90, int(OOT, 16)); (ex / 'main.npdm').write_bytes(bytes(npdm))
    for m, b in BUILDS.items():
        h = bytearray(0x100); h[:4] = b'NSO0'; h[0x40:0x50] = bytes.fromhex(b); (ex / m).write_bytes(bytes(h))
    return g


class EndToEnd(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp(prefix='prep_'))
        self.game = make_dump(self.d / 'Switch2')
        (self.d / 'packer.py').write_text(FAKE_PACKER)
        (self.d / 'run.py').write_text(WRAPPER.format(tools=str(REPO / 'dm/tools'), packer=str(self.d / 'packer.py'), prepare=str(REPO / 'scripts/prepare.py')))
        self.env = dict(os.environ, ANKA_PREPARE_STATE=str(self.d / 'state.json'), PYTHONUTF8='1', FAKE_PIDS=str(self.d / 'pids'))
        self.env.pop('NO_COLOR', None)
        self.args = ['--out', str(self.d / 'out/pack'), '--shader-cache', str(self.d / 'out/lib'), '--shader-pkg', str(self.d / 'out/pkg'), '-J', '4']

    def tearDown(self):
        import shutil; shutil.rmtree(self.d, ignore_errors=True)

    def test_identify_made_up_dump(self):
        x = model.identify(self.game)
        self.assertEqual((f'{x.app:016X}', x.ver, x.other), (OOT, 131072, []))
        with self.assertRaises(model.Bad) as c: model.identify(self.d / 'Switch2')   # a folder of games, not a dump
        self.assertIn('is not a .nup file or a dump folder', model.plain_error(c.exception.code, c.exception.args_, c.exception.msg))
        (self.d / 'Half/base').mkdir(parents=True)
        with self.assertRaises(model.Bad) as c: model.identify(self.d / 'Half')
        self.assertEqual(c.exception.code, 'dump_incomplete')
        self.assertIn('The dump is incomplete', model.plain_error(c.exception.code, c.exception.args_, c.exception.msg))

    def test_plain_ok(self):
        r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *self.args], env=self.env, capture_output=True, text=True,
                           encoding='utf-8', timeout=120)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('The Legend of Zelda: Ocarina of Time (0400486024DEC000)', r.stdout)
        for s in ('Check the game', "Rewrite the game's code", 'Find the shaders in the game', 'Build the shader library', 'Write the pack', 'The pack is ready (',
                  'Copy the pack folder'): self.assertIn(s, r.stdout)
        m = re.search(r'Log: (\S+\.log)', r.stdout); assert m, r.stdout
        log = m.group(1)
        self.assertIn('progress 500 500 library', Path(log).read_text())
        self.assertEqual(Path(log).parent, self.d / 'out/prepare-logs')
        self.assertEqual(browser.load_state(self.d / 'state.json')[0]['last'], str(self.d / 'Switch2'))

    def test_plain_failure(self):
        r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *self.args], env=dict(self.env, FAKE_MODE='fail'),
                           capture_output=True, text=True, encoding='utf-8', timeout=120)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('Preparing failed.', r.stdout)
        self.assertIn(f'Not enough free space ({self.d / "out/pack"}). Free some space', r.stdout)
        self.assertNotIn('Traceback', r.stdout.split('Preparing failed.')[1].split('Its last lines:')[0])   # the words first; the traceback only among the last lines

    def no_compiler(self):
        """The environment of a computer with no C compiler (an empty PATH, no CC) and a copy of the decoder's sources that has no build yet"""
        import shutil
        src = Path(nso.ZBIC_SRC)
        shutil.copytree(src, self.d / 'zstd-zbic', ignore=lambda d, names: ['build'] if Path(d) == src else [])
        (self.d / 'empty').mkdir()
        env = dict(self.env, PATH=str(self.d / 'empty'), ZBIC_SRC_COPY=str(self.d / 'zstd-zbic'))
        for k in ('CC', 'DM_ZBIC_LIB'): env.pop(k, None)
        return env

    def test_plain_no_compiler(self):
        """No C compiler: the plain mode stops on the checks with the fix and exit code 1, before the pick and the packer"""
        r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *self.args], env=self.no_compiler(), capture_output=True,
                           text=True, encoding='utf-8', timeout=120)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('[missing] ZBIC decoder (C compiler): not built yet, and no C compiler', r.stdout)
        self.assertIn('Install a C compiler', r.stdout); self.assertIn('1 missing', r.stdout)
        self.assertNotIn('Reading', r.stdout); self.assertFalse((self.d / 'out').exists())

    @unittest.skipIf(os.name == 'nt', 'a pseudo-terminal is POSIX')
    def test_full_screen_no_compiler(self):
        """No C compiler: the full-screen mode shows the check missing with the fix, Enter does not go on, q leaves with exit code 1"""
        from prepare_vt import Pty
        t = Pty([sys.executable, str(self.d / 'run.py'), str(self.game), *self.args], 80, 24, dict(self.no_compiler(), TERM='xterm'))
        try:
            s = t.wait_for('1 missing', 30)
            self.assertIn('no C compiler', s); self.assertIn('Install a C compiler', s)
            t.send('\r'); time.sleep(0.5); self.assertIn('1 missing', t.screen())   # Enter does not pass a missing check
            t.send('q'); t.p.wait(30)
            self.assertEqual(t.p.returncode, 1); self.assertFalse((self.d / 'out').exists())
        finally: t.close()

    @unittest.skipIf(os.name == 'nt', 'Ctrl+C is sent as SIGINT here (a console control event on Windows)')
    def test_plain_interrupt(self):
        """Ctrl+C in the plain mode's run (--yes: no question): the packer and its worker stop, exit code 3"""
        import signal
        p = subprocess.Popen([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *self.args], env=dict(self.env, FAKE_MODE='slow', FAKE_SLOW='5'),
                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        try:
            for _ in range(300):
                if (self.d / 'pids').is_file() and (self.d / 'pids').read_text(): break
                time.sleep(0.1)
            pids = [int(x) for x in (self.d / 'pids').read_text().split()]
            p.send_signal(signal.SIGINT)
            out, _ = p.communicate(timeout=60)
        finally:
            if p.poll() is None: p.kill()
        self.assertEqual(p.returncode, 3, out)
        self.assertIn('Stopped.', out); self.assertIn('What was finished is kept', out)
        for pid in pids:
            with self.assertRaises(ProcessLookupError): os.kill(pid, 0)

    def test_plain_bad_pick(self):
        (self.d / 'Half/base').mkdir(parents=True)
        r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.d / 'Half'), '--plain', '--yes', *self.args], env=self.env, capture_output=True,
                           text=True, encoding='utf-8', timeout=120)
        self.assertEqual(r.returncode, 1); self.assertIn('The dump is incomplete', r.stdout)

    def test_plain_name_in_last_folder(self):
        """A name alone typed at the plain mode's question is looked for in the last folder used, as the question says"""
        browser.save_state(browser.remember({}, self.d / 'Switch2'), self.d / 'state.json')
        r = subprocess.run([sys.executable, str(self.d / 'run.py'), '--plain', *self.args], env=self.env, input='Ocarina\n\ny\n', capture_output=True, text=True,
                           encoding='utf-8', timeout=120)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr); self.assertIn(f'looked for in {self.d / "Switch2"}', r.stdout)
        self.assertNotIn('+ / -', r.stdout)   # (no key hints in the plain mode)

    def test_plain_worker_left_behind(self):
        """The packer ends while its worker lives on and holds the output: the run still ends, done by the packer's exit code, and the worker is ended"""
        t0 = time.monotonic()
        r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *self.args], env=dict(self.env, FAKE_MODE='slow'),
                           capture_output=True, text=True, encoding='utf-8', timeout=120)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr); self.assertIn('The pack is ready', r.stdout); self.assertLess(time.monotonic() - t0, 60)
        if os.name != 'nt':
            with self.assertRaises(ProcessLookupError): os.kill(int((self.d / 'pids').read_text().split()[1]), 0)

    @unittest.skipIf(os.name == 'nt', 'SIGKILL is POSIX')
    def test_plain_packer_killed(self):
        """The packer killed (SIGKILL, as the out-of-memory killer does) while its worker goes on: the run fails at once, in plain words, the worker ended"""
        import signal
        p = subprocess.Popen([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *self.args], env=dict(self.env, FAKE_MODE='slow', FAKE_SLOW='5'),
                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        try:
            for _ in range(300):
                if (self.d / 'pids').is_file() and (self.d / 'pids').read_text(): break
                time.sleep(0.1)
            pids = [int(x) for x in (self.d / 'pids').read_text().split()]
            os.kill(pids[0], signal.SIGKILL)
            out, _ = p.communicate(timeout=60)
        finally:
            if p.poll() is None: p.kill()
        self.assertEqual(p.returncode, 1, out)
        self.assertIn('Preparing failed.', out); self.assertIn('Not enough memory: pack_android.py was stopped', out)
        with self.assertRaises(ProcessLookupError): os.kill(pids[1], 0)

    @unittest.skipIf(os.name == 'nt' or hasattr(os, 'geteuid') and os.geteuid() == 0, 'POSIX permissions (root ignores them)')
    def test_plain_pack_folder_not_writable(self):
        (self.d / 'ro').mkdir(); (self.d / 'ro').chmod(0o555)
        try:
            args = ['--out', str(self.d / 'ro/pack')] + self.args[2:]
            r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *args], env=self.env, capture_output=True, text=True,
                               encoding='utf-8', timeout=120)
        finally: (self.d / 'ro').chmod(0o755)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn(f'Cannot write in {self.d / "ro/pack"}', r.stdout); self.assertNotIn('Traceback', r.stdout + r.stderr)

    def test_plain_second_run_refused(self):
        """A second run on the pack folder another run of the interface is using does not start"""
        hold = model.Lock(self.d / 'out/prepare-logs/pack.lock'); self.assertTrue(hold.take())
        try:
            r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.game), '--plain', '--yes', *self.args], env=self.env, capture_output=True, text=True,
                               encoding='utf-8', timeout=120)
        finally:
            assert hold.fd is not None; os.close(hold.fd)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr); self.assertIn('Another preparing run is using', r.stdout)
        self.assertFalse((self.d / 'out/pack/pack.tsv').exists())

    @unittest.skipIf(os.name == 'nt', 'a pseudo-terminal is POSIX')
    def test_full_screen(self):
        from prepare_vt import Pty
        t = Pty([sys.executable, str(self.d / 'run.py'), '--folder', str(self.d / 'Switch2'), *self.args], 80, 24, dict(self.env, TERM='xterm'))
        try:
            t.wait_for('Everything is there', 30); t.send('\r')
            s = t.wait_for('Folders and games', 30)
            self.assertIn('* Ocarina/', s); self.assertIn('dump folder', s)
            self.assertIn('Esc: quit', s)   # the key line whole at 80 columns
            t.resize(160, 30)   # drawn again at the new size: the title's right part at the new right edge
            t.wait_for(re.compile(r'^ Anka Native.{100,}2 Pick the game$', re.M))
            t.send('\x1b'); t.wait_for('Leave without preparing'); t.send('n')   # Esc asks first
            t.wait_for(re.compile(r'\A(?![\s\S]*Leave without)[\s\S]*Folders and games'))   # (the question gone: the keys are the browser's again)
            t.send('Oc'); t.wait_for('Filter: Oc'); t.send('\x1b[B'); t.send('\x1b[A'); t.send('\r')
            s = t.wait_for('Prepare this game?', 30)
            self.assertIn('131072', s); self.assertIn('4 shaders at once', s)
            t.send('+'); t.wait_for('5 shaders at once'); t.send('\r')
            s = t.wait_for('The pack is ready', 60)
            self.assertIn('Copy the pack folder', s); self.assertNotIn('failed', ''.join(t.raw))
            t.send('\r'); t.p.wait(30); time.sleep(0.3)
            self.assertEqual(t.p.returncode, 0)
            self.assertEqual(t.attrs(), t.before)   # the terminal's modes put back
            self.assertEqual({k: t.vt.modes.get(k) for k in (1049, 25, 2004, 7)}, {1049: False, 25: True, 2004: False, 7: True})
            self.assertIn('The pack is ready (', t.screen())   # printed again on the normal screen
            self.assertTrue((self.d / 'out/pack/pack.tsv').is_file())
        finally: t.close()

    @unittest.skipIf(os.name == 'nt', 'a pseudo-terminal is POSIX')
    def test_full_screen_stop(self):
        from prepare_vt import Pty
        t = Pty([sys.executable, str(self.d / 'run.py'), str(self.game), *self.args], 100, 30, dict(self.env, TERM='xterm', FAKE_MODE='slow', FAKE_SLOW='5'))
        try:
            t.wait_for('Everything is there', 30); t.send('\r')
            t.wait_for('Prepare this game?', 30); t.send('\r')
            t.wait_for('shaders', 60)
            for _ in range(100):
                if (self.d / 'pids').is_file(): break
                time.sleep(0.1)
            pids = [int(x) for x in (self.d / 'pids').read_text().split()]
            t.send('\x03'); t.wait_for('Stop preparing?')
            t.send('n'); time.sleep(0.5); self.assertIsNone(t.p.poll())   # no: it goes on
            t.send('\x03'); t.wait_for('Stop preparing?'); t.send('y')
            s = t.wait_for('Stopped.', 60)
            self.assertIn('What was finished is kept', s)
            t.send('q'); t.p.wait(30)
            self.assertEqual(t.p.returncode, 3)
            self.assertEqual(t.attrs(), t.before)
            for pid in pids:   # the packer and its worker are gone
                with self.assertRaises(ProcessLookupError): os.kill(pid, 0)
        finally: t.close()


    @unittest.skipIf(os.name == 'nt', 'a pseudo-terminal is POSIX')
    def test_full_screen_hangup(self):
        """A closed window (SIGHUP) or a SIGTERM in the middle of a run: the packer and its worker are stopped, the terminal's modes put back"""
        import signal
        from prepare_vt import Pty
        for sig in (signal.SIGHUP, signal.SIGTERM):
            (self.d / 'pids').unlink(missing_ok=True)
            t = Pty([sys.executable, str(self.d / 'run.py'), str(self.game), *self.args], 100, 30, dict(self.env, TERM='xterm', FAKE_MODE='slow', FAKE_SLOW='5'))
            try:
                t.wait_for('Everything is there', 30); t.send('\r')
                t.wait_for('Prepare this game?', 30); t.send('\r')
                for _ in range(300):
                    if (self.d / 'pids').is_file() and (self.d / 'pids').read_text(): break
                    time.sleep(0.1)
                pids = [int(x) for x in (self.d / 'pids').read_text().split()]
                os.kill(t.p.pid, sig); t.p.wait(60)
                self.assertEqual(t.p.returncode, 128 + sig, sig)
                self.assertEqual(t.attrs(), t.before)
                for pid in pids:
                    with self.assertRaises(ProcessLookupError): os.kill(pid, 0)
            finally: t.close()

    @unittest.skipIf(os.name == 'nt', 'a pseudo-terminal is POSIX')
    def test_full_screen_suspend(self):
        """kill -TSTP and then SIGCONT (fg): the terminal is given back before the process stops, and taken again, the screen drawn whole, when it goes on"""
        import signal
        from prepare_vt import Pty
        t = Pty([sys.executable, str(self.d / 'run.py'), '--folder', str(self.d / 'Switch2'), *self.args], 80, 24, dict(self.env, TERM='xterm'))
        try:
            t.wait_for('Everything is there', 30); t.send('\r'); t.wait_for('Folders and games', 30)
            raw = t.attrs()
            os.kill(t.p.pid, signal.SIGTSTP)
            for _ in range(100):
                if t.attrs() == t.before: break
                time.sleep(0.05)
            self.assertEqual(t.attrs(), t.before); self.assertFalse(t.vt.modes.get(1049))
            os.kill(t.p.pid, signal.SIGCONT)
            t.wait_for('Folders and games', 30)
            self.assertEqual(t.attrs(), raw); self.assertTrue(t.vt.modes.get(1049))
            t.send('\x1b[B'); t.send('\x03'); t.p.wait(30)   # keys work again
            self.assertEqual(t.p.returncode, 2); self.assertEqual(t.attrs(), t.before)
        finally: t.close()

    @unittest.skipIf(os.name == 'nt', 'a pseudo-terminal is POSIX')
    def test_full_screen_pack_folder_not_writable(self):
        from prepare_vt import Pty
        (self.d / 'ro').mkdir(); (self.d / 'ro').chmod(0o555)
        t = Pty([sys.executable, str(self.d / 'run.py'), str(self.game), '--out', str(self.d / 'ro/pack'), *self.args[2:]], 100, 30, dict(self.env, TERM='xterm'))
        try:
            t.wait_for('Everything is there', 30); t.send('\r')
            t.wait_for('Prepare this game?', 30); t.send('\r')
            t.wait_for('Cannot write in', 30); t.send('\r')
            t.wait_for(re.compile(r'\A(?![\s\S]*Cannot write in)[\s\S]*Prepare this game\?'), 30)   # told, and back on the plan
            t.send('q'); t.p.wait(30)
            self.assertEqual(t.p.returncode, 2); self.assertNotIn('Traceback', ''.join(t.raw))
        finally:
            t.close(); (self.d / 'ro').chmod(0o755)


class Texts(unittest.TestCase):
    def test_keys(self):
        src = ''.join(p.read_text() for p in (REPO / 'dm/tools/prepare_tui').glob('*.py') if p.name != 'texts.py')
        used = set(re.findall(r"'([a-z]+\.[a-z_0-9]+)'", src))   # every key written out
        self.assertEqual(sorted(k for k in set(re.findall(r"T\('([a-z_.0-9]+)'", src)) - set(texts.TEXTS) if not k.endswith('_')), [], 'T() of keys not in texts.TEXTS')
        used &= set(texts.TEXTS)
        used |= {f'run.lib_{s}' for s in ('library', 'check', 'done')} | {f'end.next{i}' for i in (1, 2, 3)}   # the keys made of a part
        used |= {f'error.{c}' for c in __import__('pack_errors').CODES}
        self.assertEqual(sorted(used - set(texts.TEXTS)), [], 'keys used but not in texts.TEXTS')
        self.assertEqual(sorted(set(texts.TEXTS) - used), [], 'keys in texts.TEXTS that nothing uses')


if __name__ == '__main__':
    unittest.main()
