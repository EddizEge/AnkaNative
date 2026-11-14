"""scripts/prepare.py, the terminal interface for preparing a game on a computer (dm/tools/prepare_tui), without a terminal of the test's own:
- keys: the escape-sequence family (CSI and SS3 forms of the xterm, rxvt and Linux console spellings, bracketed paste, a lone Escape, sequences split between
  reads, UTF-8) and the Windows console's scan codes (a lone 0xE0 being the character à), fed as characters into term.Decoder
- drawing: every screen into an in-memory screen.Screen at 80x24, 160x50 and 60x18, Unicode and ASCII glyphs (an ASCII screen holds only ASCII), nothing past
  the edge, no colour in the text, each key line whole at 80 columns, a fix shared by several checks shown once; render()'s escape sequences with and without colour
- the browser's logic on a temporary folder tree (folders, .nup files, dump folders, the filter, up and into folders, the window, path completion) and the
  state file (written, read back, a damaged one)
- the packer's output: progress lines in the three spellings, step lines, error: lines and [RED] lines, and a failure's code paired as the phone pairs it
- the estimator (eta.py, the phone's Eta.java on the computer's steps): steady, a falling rate, a stall, the translation's end and the library's countdown, a
  resumed run's baseline, the split's hand-over to the shaders' figure, totals of 0, and a replay of the real run's trace (prepare_data/prepare-pc-oot.trace.txt)
- the plain line mode end to end (done, failed, a bad pick, and on POSIX Ctrl+C), and on POSIX the full-screen mode on a pseudo-terminal (prepare_vt.py): a
  made-up dump folder of Ocarina of Time (an NPDM and NSO headers of a few bytes, as test_pack_errors.py makes them) picked with the packer's own checks, and a
  stand-in packer that prints what pack_android.py prints; a file picked with keys, confirmed, the run watched to its end, the terminal's modes put back; Ctrl+C
  in the middle stops the stand-in packer and its worker; so do SIGHUP (a closed window) and SIGTERM
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
from prepare_tui import app, browser, eta, model, progress, screen as scr, texts   # noqa: E402
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
    fix = ['python3.14 -m venv <dump folder>/.venv-anka', '<dump folder>/.venv-anka/bin/python -m pip install "capstone>=5,<6" lz4 zbic']
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
                    if name == 'end-done' and (w, h) == (80, 24): self.assertIn('must keep the name', txt)   # the whole end fits at 80x24
                    if name == 'end-failed' and w >= 80: self.assertIn('Not enough free space (/games/x)', txt)
                    if name == 'end-stopped': self.assertIn('What was finished is kept', txt)

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


    def test_whole_job_on_resume(self):
        """A resumed translation's count includes what the earlier run translated: the whole job's bar starts the step at this run's share, not ahead of it"""
        p = FakePlan(translated=3384, kept=('main', 'subsdk0', 'sdk'), split=False)
        a, _ = live_at(p, ['step identify', 'step rewrite', 'step shaders', 'progress 3384 42854 translate'])
        b, _ = live_at(p, ['step identify', 'step rewrite', 'step shaders', 'progress 23119 42854 translate'])
        self.assertLess(a.overall(), 0.01)
        self.assertAlmostEqual(b.overall(), (b.weights[0] + 0.5 * (42854 - 3384) * p.shader_s()) / sum(b.weights), places=3)   # half of this run's translation


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
    checks.run_all = lambda: [Check('Python 3.14', True, 'stand-in')]
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
        with self.assertRaises(model.Bad) as c: model.identify(self.d / 'Switch2')
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
        r = subprocess.run([sys.executable, str(self.d / 'run.py'), str(self.d / 'Switch2'), '--plain', '--yes', *self.args], env=self.env, capture_output=True,
                           text=True, encoding='utf-8', timeout=120)
        self.assertEqual(r.returncode, 1); self.assertIn('The dump is incomplete', r.stdout)

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
            t.send('Oc'); t.wait_for('Filter: Oc'); t.send('\x1b[B'); t.send('\x1b[A'); t.send('\r')
            s = t.wait_for('Prepare this game?', 30)
            self.assertIn('131072', s); self.assertIn('4 shaders at once', s)
            t.send('+'); t.wait_for('5 shaders at once'); t.send('\r')
            s = t.wait_for('The pack is ready', 60)
            self.assertIn('Copy the pack folder', s)
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
