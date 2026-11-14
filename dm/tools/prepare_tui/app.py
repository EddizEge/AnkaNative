# The screens of scripts/prepare.py, in order: the checks (what the packer needs), the pick (a file browser), the confirmation (the plan), the run, and the end
# (done, failed or stopped). Two faces with the same questions and the same progress (model.py says what they show):
#   Full    the full-screen mode: term.py's terminal, screen.py's frames, keys
#   Plain   lines: when stdin or stdout is not a terminal, TERM=dumb, the console takes no escape sequences, or --plain; questions through input(), progress as
#           a line at each step and every PLAIN_EVERY seconds
# The run is pack_android.py as a child process (runner.py); Ctrl+C or q asks, then stops it cleanly, and a later run resumes. Before it starts, the folders it
# writes in are checked and locked against another run of this interface (model.Plan.ready). It is over when the packer has exited (runner.Run.finished): done or
# failed by its exit code, a packer killed by a signal said in words (SIGKILL: the out-of-memory killer's), a failure without a code as a permission problem or a
# failure to report. SIGTERM and SIGHUP (a closed
# window) end the interface through SystemExit, which stops the child and puts the terminal back (main's finally); on Windows the console's close event does it
# (term.WindowsTerminal.on_close).
import os, re, signal, sys, time
from pathlib import Path

from prepare_tui import browser, checks, eta, model, progress, runner, screen as scr, term
from prepare_tui.screen import BOLD, DIM, REV, GREEN, RED, YELLOW, CYAN
from prepare_tui.texts import T

PLAIN_EVERY = 30.0   # seconds between the plain mode's progress lines within a step
STEP_KEYS = ('step.identify', 'step.rewrite', 'step.split', 'step.shaders', 'step.pack')


def fmt(n): return f'{n:,}'


class Quit(Exception):
    """The user left the interface (q, Esc, Ctrl+C before a run): nothing was started. code: the exit code (2; 1 when something the packer needs is missing)"""
    def __init__(self, code=2): super().__init__(code); self.code = code


class Outcome:
    def __init__(self, kind, live=None, plan=None, rc=0, took=0.0, log=None):
        self.kind, self.live, self.plan, self.rc, self.took, self.log = kind, live, plan, rc, took, log   # kind: done, failed, stopped


def outcome(proc, live, plan, log, now):
    """The Outcome of a run that is over (proc.finished()): its last lines fed first; stopped when it was asked to stop, else done or failed by the packer's exit
    code (never by its output)"""
    lines, _ = proc.lines()
    for t, l in lines: live.feed(live.t0 + t, l)
    proc.close()
    live.log_error = proc.log_error
    rc = proc.p.returncode
    return Outcome('stopped' if proc.stopping else 'done' if rc == 0 else 'failed', live, plan, rc, now - live.t0, log)


# ---- the full-screen mode

class Full:
    def __init__(self, terminal, glyphs, color):
        self.t, self.g, self.color = terminal, glyphs, color
        self.shown = None   # the screen on the terminal
        self.drawn = None
        self.size = None

    def frame(self, draw):
        """One frame: a Screen of the terminal's size drawn by draw(sc), written as the rows that changed (all after a resize)"""
        w, h = self.t.size()
        sc = scr.Screen(w, h, self.g)
        self.drawn = draw(sc)   # what draw returned (draw_end: the scroll it used)
        if (w, h) != self.size or self.t.dirty: self.t.write('\x1b[0m\x1b[2J'); self.shown = None; self.size = (w, h); self.t.dirty = False
        out = scr.render(sc, self.shown, self.color)
        if out: self.t.write(out)
        self.shown = sc
        return sc

    def keys(self, timeout=0.25): return self.t.keys(timeout)

    def ask(self, draw, question, yes='y', no='n'):
        """A yes / no question drawn over the screen; → True for yes (Enter is no)"""
        while True:
            self.frame(lambda sc: (draw(sc), dialog(sc, question)))
            for k in self.keys():
                if k.name == 'char' and k.text.lower() == yes: return True
                if k.name in ('esc', 'enter', 'ctrl-c') or k.name == 'char' and k.text.lower() == no: return False

    def tell(self, draw, text):
        """A message drawn over the screen until a key"""
        while True:
            self.frame(lambda sc: (draw(sc), dialog(sc, text + '  (Enter)')))
            if self.keys(): return


def dialog(sc, text):
    lines = scr.wrap(text, min(60, sc.w - 8))
    w = min(sc.w - 2, max(scr.width(l) for l in lines) + 6); h = len(lines) + 2
    x, y = (sc.w - w) // 2, max(1, (sc.h - h) // 2)
    for i in range(h): sc.fill(x, y + i, w, REV)
    sc.box(x, y, w, h, '', REV)
    for i, l in enumerate(lines): sc.put(x + 3, y + 1 + i, l, REV | BOLD)


def title(sc, where):
    sc.fill(0, 0, sc.w, REV)
    sc.put(1, 0, T('app.title'), REV | BOLD)
    r = f' {where} '
    if scr.width(T('app.title')) + scr.width(r) + 4 <= sc.w: sc.put(sc.w - scr.width(r) - 1, 0, r, REV)


def footer(sc, text):
    sc.fill(0, sc.h - 1, sc.w, REV)
    sc.put(1, sc.h - 1, scr.fit(text, sc.w - 2, sc.g.dots), REV)


def field(sc, y, label, value, style=0, lw=14):
    sc.put(2, y, label, DIM)
    sc.put(2 + lw, y, scr.fit(value, sc.w - 3 - lw, sc.g.dots), style)


# ---- screen 1: the checks

def draw_checks(sc, res, scroll=0, busy=False):
    title(sc, T('where.checks'))
    sc.put(2, 2, T('checks.head'), BOLD)
    lines = []   # (text, style, indent)
    nw = min(max(scr.width(c.name) for c in res) + 2, sc.w // 2) if res else 20
    for c in res:
        mark = sc.g.ok if c.ok else sc.g.bad
        found = scr.wrap(c.found, max(10, sc.w - 5 - nw))   # what was found in full (a path, a version string), on more lines when it is long
        lines.append(([(mark + ' ', GREEN | BOLD if c.ok else RED | BOLD), (scr.pad(scr.fit(c.name, nw - 1, sc.g.dots), nw), BOLD), (found[0], 0 if c.ok else RED)], 2))
        lines += [([(f, 0 if c.ok else RED)], 4 + nw) for f in found[1:]]
        lines += [([(l, DIM)], 4 + nw) for d in c.detail for l in scr.wrap(d, max(10, sc.w - 5 - nw))]   # (a compiler's last lines)
        if not c.ok and c.fix == []: continue
        if not c.ok and fix_shown(res, c):
            lines.append(([(T('checks.same_fix'), YELLOW)], 6))
        elif not c.ok:
            lines.append(([(T('checks.fix'), YELLOW)], 6))
            for f in c.fix:
                for i, l in enumerate(scr.wrap(f, sc.w - 10)): lines.append(([(l, YELLOW | BOLD if i == 0 and not f.endswith(':') else YELLOW)], 8))
    top, rows = 4, sc.h - 8
    scroll = max(0, min(scroll, len(lines) - rows))
    for i, (parts, ind) in enumerate(lines[scroll:scroll + rows]):
        x = ind
        for txt, st in parts: x = sc.put(x, top + i, txt, st, sc.w - 1 - x)
    bad = sum(1 for c in res if not c.ok)
    y = sc.h - 3
    if busy: sc.put(2, y, T('checks.busy'), CYAN)
    elif bad: sc.put(2, y, scr.fit(T('checks.missing', bad), sc.w - 4, sc.g.dots), RED | BOLD)
    else: sc.put(2, y, T('checks.ok'), GREEN | BOLD)
    footer(sc, T('keys.checks_ok') if not bad else T('keys.checks_bad'))
    return len(lines) > rows


def fix_shown(res, c):
    """Whether a check before c that is missing too has the same fix (one environment brings Capstone and lz4): it is shown once"""
    return any(not o.ok and o.fix == c.fix for o in res[:res.index(c)])


def screen_checks(ui, run_checks):
    ui.frame(lambda sc: draw_checks(sc, [], busy=True))
    res = run_checks()
    scroll = 0
    while True:
        ui.frame(lambda sc, res=res, scroll=scroll: draw_checks(sc, res, scroll))
        for k in ui.keys():
            if k.name == 'enter' and all(c.ok for c in res): return res
            if k.name in ('esc', 'ctrl-c') or k.name == 'char' and k.text in 'qQ': raise Quit(2 if all(c.ok for c in res) else 1)
            if k.name == 'char' and k.text in 'rR':
                ui.frame(lambda sc, res=res, scroll=scroll: draw_checks(sc, res, scroll, busy=True)); res = run_checks()
            if k.name in ('down', 'pgdn'): scroll += 1 if k.name == 'down' else 10
            if k.name in ('up', 'pgup'): scroll = max(0, scroll - (1 if k.name == 'up' else 10))


# ---- screen 2: the pick

class Pick:
    """The browser screen's state: the browser, the places, which side has the keys, the path field, the message under the list"""
    def __init__(self, state, start=None):
        self.state = state
        first = Path(start) if start else Path(state.get('last') or Path.home())
        if not first.is_dir(): first = Path.home()
        self.b = browser.Browser(first)
        if self.b.error: self.b = browser.Browser(Path.home())
        self.places = browser.places(state)
        self.pcur, self.focus = 0, 'list'
        self.path = None   # the path field's text while it is open
        self.msg, self.msg_style = '', 0

    def say(self, text, style=0): self.msg, self.msg_style = text, style


def draw_pick(sc, p):
    g = sc.g
    title(sc, T('where.pick'))
    sc.put(2, 1, scr.fit(T('pick.head'), sc.w - 4, g.dots), BOLD)
    sc.put(2, 2, scr.fit(str(p.b.folder), sc.w - 4, g.dots, left=True), CYAN | BOLD)
    if p.path is not None:
        lab = T('pick.path')
        x = sc.put(2, 3, lab, BOLD)
        sc.put(x + 1, 3, scr.fit(p.path, sc.w - x - 4, g.dots, left=True) + '_', REV)
    elif p.b.filter: sc.put(2, 3, scr.fit(T('pick.filter', p.b.filter), sc.w - 4, g.dots), YELLOW)
    else: sc.put(2, 3, scr.fit(T('pick.hint'), sc.w - 4, g.dots), DIM)
    top, rows = 5, sc.h - 9
    lx = 2
    if sc.w >= 70:   # the places on the left
        pw = 22
        sc.put(2, top - 1, T('pick.places'), BOLD | (REV if p.focus == 'places' else 0))
        for i, (lab, _) in enumerate(p.places[:rows]):
            st = REV if p.focus == 'places' and i == p.pcur else 0
            sc.put(2, top + i, scr.pad(scr.fit(lab, pw - 3, g.dots), pw - 3), st)
        lx = pw + 1
        for i in range(rows + 1): sc.put(lx - 1, top - 1 + i, g.v, DIM)
    sc.put(lx + 1, top - 1, T('pick.list'), BOLD | (REV if p.focus == 'list' else 0))
    first, items = p.b.window(rows)
    lw = sc.w - lx - 2
    for i, e in enumerate(items):
        sel = p.focus == 'list' and first + i == p.b.cur
        if e.kind == 'up': name, tag, st = g.up + ' ..', '', DIM
        elif e.kind == 'dir': name, tag, st = g.folder + ' ' + e.name + os.sep, '', 0
        elif e.kind == 'game': name, tag, st = '* ' + e.name + os.sep, T('pick.tag_dump'), GREEN | BOLD
        else: name, tag, st = '* ' + e.name, model.gb(e.size), GREEN | BOLD
        tw = scr.width(tag)
        sc.put(lx + 1, top + i, scr.pad(scr.fit(name, lw - tw - 2, g.dots), lw - tw - 1) + tag, (st | REV) if sel else st)
    if not items: sc.put(lx + 1, top, T('pick.empty') if not p.b.filter else T('pick.nomatch'), DIM)
    if p.b.error: p.say(p.b.error, RED)
    for i, l in enumerate(scr.wrap(p.msg, sc.w - 4)[:2]): sc.put(2, sc.h - 3 + i, l, p.msg_style)
    footer(sc, T('keys.path') if p.path is not None else T('keys.pick'))


def screen_pick(ui, p, check):
    """The browser until a pick passes check(path) (→ (path, what check returned)); check raises model.Bad for a pick that cannot be packed"""
    while True:
        ui.frame(lambda sc: draw_pick(sc, p))
        for k in ui.keys():
            picked = pick_key(p, k)
            if picked is QUIT_ASK:   # (Esc: a lone one, or the first half of an arrow key that came late, or Alt+key)
                if ui.ask(lambda sc: draw_pick(sc, p), T('pick.quit_ask')): raise Quit()
                continue
            if picked is None: continue
            p.say(T('pick.reading', picked), CYAN)
            ui.frame(lambda sc: draw_pick(sc, p))
            try: return picked, check(picked)
            except model.Bad as e: p.say(model.plain_error(e.code, e.args_, e.msg), RED)


QUIT_ASK = object()   # pick_key's answer to Esc: leave, once the user says so


def pick_key(p, k):
    """One key on the pick screen → a path picked, None, or QUIT_ASK"""
    b = p.b
    if k.name == 'ctrl-c': raise Quit()
    if p.path is not None:   # the path field
        if k.name in ('char', 'paste'): p.path += k.text.strip('\r\n') if k.name == 'paste' else k.text
        elif k.name == 'backspace': p.path = p.path[:-1]
        elif k.name == 'ctrl-u': p.path = ''
        elif k.name == 'tab': p.path = browser.complete(p.path)
        elif k.name == 'esc': p.path = None
        elif k.name == 'enter':
            t = Path(os.path.expanduser(browser.unquote(p.path)))
            p.path = None
            if t.is_dir() and not browser.is_dump(t):
                if b.go(t): p.say(b.error, RED)
                return None
            return t
        return None
    if k.name == 'paste':   # a path pasted while browsing: taken as the path field's text
        p.path = k.text.strip(); return None
    if k.name == 'tab': p.focus = 'places' if p.focus == 'list' else 'list'; return None
    if k.name in ('ctrl-l', 'f4'): p.path = str(b.folder) + os.sep; return None
    if p.focus == 'places':
        if k.name == 'up': p.pcur = max(0, p.pcur - 1)
        elif k.name == 'down': p.pcur = min(len(p.places) - 1, p.pcur + 1)
        elif k.name in ('enter', 'right'):
            if b.go(p.places[p.pcur][1]): p.say(b.error, RED)
            else: p.focus = 'list'; p.say('')
        elif k.name == 'esc': return QUIT_ASK
        return None
    if k.name in ('up', 'down', 'home', 'end', 'pgup', 'pgdn'): b.move(k.name); p.say(''); return None
    if k.name == 'enter':
        e = b.current()
        if e is None: return None
        r = b.enter()
        if b.error: p.say(b.error, RED)
        else: p.say('')
        return r
    if k.name == 'right':
        e = b.current()
        if e and e.kind in ('dir', 'game'):
            if b.go(e.path): p.say(b.error, RED)
        return None
    if k.name == 'left': b.up(); return None
    if k.name == 'backspace': b.erase(); return None
    if k.name == 'esc':
        if b.filter: b.filter = ''; return None
        return QUIT_ASK
    if k.name == 'char':
        if not b.filter and k.text in '/\\~' or (len(b.filter) == 1 and k.text == ':' and b.filter.isalpha() and os.name == 'nt'):
            p.path = b.filter + k.text; b.filter = ''; return None   # a path typed from its start: the path field
        b.type(k.text)
    return None


# ---- screen 3: the confirmation

def versions(x):
    """The versions v cycles through: the dump's updates, and the base game (0) when the dump has one (pack_android.py checks the pick again)"""
    return sorted(set(x.vers) | ({0} if x.ver == 0 or (x.folder / 'base').is_dir() or x.folder.is_file() else set()))


def plan_lines(plan, keys=True):
    """[(label, value, style)] of the confirmation, the same in both modes (keys: with the full-screen mode's key hints)"""
    x, g = plan.x, plan.g
    out = [(T('confirm.game'), f'{g.get("name", "?")} ({x.app:016X})', BOLD)]   # (label, value, style); a value that is a path is a Path (cut at its start)
    vers = sorted(x.vers)
    vtxt = T('confirm.version_base') if x.ver == 0 else str(x.ver)
    if x.ver and x.ver == max(vers): vtxt += '   ' + T('confirm.version_newest')
    if keys and len(versions(x)) > 1: vtxt += '   ' + T('confirm.version_keys')
    out.append((T('confirm.version'), vtxt, 0))
    if x.other: out.append(('', T('confirm.other_build', ', '.join(x.other)), YELLOW))
    out.append((T('confirm.file'), x.folder, 0))
    out.append((T('confirm.out'), plan.out, 0))
    mem = model.free_memory()
    out.append((T('confirm.workers'), T('confirm.workers_value', plan.J, os.cpu_count() or '?', model.gb(mem) if mem else '?') + ('  ' + T('confirm.workers_keys') if keys else ''), 0))   # (the hint has no space: a narrow screen moves it whole)
    sp = plan.space()
    if not sp: out.append((T('confirm.disk'), T('confirm.disk_none'), 0))   # (everything is kept from an earlier run)
    for e, need, free in sp:
        style = RED | BOLD if 0 <= free < need else 0
        out.append((T('confirm.disk'), T('confirm.disk_value', model.gb(need), model.gb(free) if free >= 0 else '?'), style))
        if len(sp) > 1: out.append(('', e, DIM))   # which drive, when the run writes on more than one
    kept = []
    if plan.kept_mods: kept.append(T('confirm.kept_code', len(plan.kept_mods), len(x.mods)))
    if plan.lib_current: kept.append(T('confirm.kept_lib'))
    elif plan.translated: kept.append(T('confirm.kept_shaders', fmt(plan.translated), fmt(plan.shaders)))
    if not plan.split and plan.build: kept.append(T('confirm.kept_pkg'))
    out.append((T('confirm.resume'), '; '.join(kept) if kept else T('confirm.fresh'), GREEN if kept else 0))
    r = plan.rough()
    words = model.minutes_words(r) if eta.rnd(r) == 0 else T('confirm.about', model.minutes_words(r))   # ('less than a minute', 'about 26 minutes')
    out.append((T('confirm.time'), T('confirm.time_value', words, time.strftime('%H:%M', time.localtime(time.time() + r))), 0))
    return out


def draw_confirm(sc, plan):
    title(sc, T('where.confirm'))
    sc.put(2, 2, T('confirm.head'), BOLD)
    y = 4
    lw = max(scr.width(l) for l, _, _ in plan_lines(plan)) + 2
    for lab, val, st in plan_lines(plan):
        if y >= sc.h - 3: break
        vals = [scr.fit(str(val), sc.w - 3 - lw, sc.g.dots, left=True)] if isinstance(val, Path) else scr.wrap(val, sc.w - 3 - lw)[:2]
        for i, l in enumerate(vals):
            if i == 0: sc.put(2, y, lab, DIM)
            sc.put(2 + lw, y, l, st); y += 1
    if plan.short(): sc.put(2, sc.h - 3, scr.fit(T('confirm.short'), sc.w - 4, sc.g.dots), RED | BOLD)
    footer(sc, T('keys.confirm'))


def screen_confirm(ui, plan, remake):
    """The plan until Enter (→ the plan to run) or b (→ None: back to the pick). remake(plan, **changes) makes the plan again with another version or folder"""
    while True:
        ui.frame(lambda sc, plan=plan: draw_confirm(sc, plan))
        for k in ui.keys():
            if k.name == 'enter':
                if plan.short() and not ui.ask(lambda sc, plan=plan: draw_confirm(sc, plan), T('confirm.short_ask')): continue
                if why := plan.ready(): ui.tell(lambda sc, plan=plan: draw_confirm(sc, plan), why); continue
                return plan
            if k.name == 'ctrl-c' or k.name == 'char' and k.text in 'qQ': raise Quit()
            if k.name in ('esc', 'backspace') or k.name == 'char' and k.text in 'bB': return None
            if k.name == 'char' and k.text in '+=': plan.J = min(256, plan.J + 1)
            if k.name == 'char' and k.text in '-_': plan.J = max(1, plan.J - 1)
            if k.name == 'char' and k.text in 'vV':
                vs = versions(plan.x)
                nxt = vs[(vs.index(plan.x.ver) + 1) % len(vs)] if plan.x.ver in vs else vs[0]
                try: plan = remake(plan, version=nxt)
                except model.Bad as e: ui.tell(lambda sc, plan=plan: draw_confirm(sc, plan), model.plain_error(e.code, e.args_, e.msg))
            if k.name == 'char' and k.text in 'oO':
                new = edit_line(ui, lambda sc, plan=plan: draw_confirm(sc, plan), T('confirm.out_ask'), str(plan.out))
                if new: plan = remake(plan, out=new)


def edit_line(ui, under, label, text):
    """A one-line field over the screen; → its text on Enter, None on Esc"""
    while True:
        def draw(sc, text=text):
            under(sc)
            y = sc.h - 3
            sc.fill(0, y, sc.w, 0)
            x = sc.put(2, y, label + ' ', BOLD)
            sc.put(x, y, scr.fit(text, sc.w - x - 3, sc.g.dots, left=True) + '_', REV)
            footer(sc, T('keys.path'))
        ui.frame(draw)
        for k in ui.keys():
            if k.name == 'enter': return text.strip()
            if k.name in ('esc', 'ctrl-c'): return None
            if k.name in ('char', 'paste'): text += k.text.strip('\r\n') if k.name == 'paste' else k.text
            if k.name == 'backspace': text = text[:-1]
            if k.name == 'ctrl-u': text = ''
            if k.name == 'tab': text = browser.complete(text)


# ---- screen 4: the run

def step_status(live, i):
    """'done', 'now', 'todo', 'skip' (not needed: kept from before, or nothing to do) for step i"""
    st, p = live.state, live.plan
    if i == st.step: return 'now'
    if i < st.step: return 'done' if i in st.started else 'skip'
    planned = [True, p.rewrite, p.split, p.build, True][i]
    return 'todo' if planned else 'skip'


def step_detail(live, i):
    st = live.state
    if i not in st.prog: return ''
    d, t, stage = st.prog[i]
    if i == 1: return T('run.modules', d, t)
    if i == 2: return T('run.files', fmt(d), fmt(t)) if stage != 'main' else T('run.split_start')
    if i == 3:
        if stage in ('library', 'check', 'done'): return T('run.lib_' + stage)
        return T('run.shaders', fmt(d), fmt(t))
    return ''


def run_lines(live, now):
    """The run's status lines, the same in both modes: [(text, style)]"""
    out = []
    i = live.state.step
    el = now - live.t0
    r = live.rate(now)
    s = T('run.elapsed', model.duration(el))
    if r is not None and i in (2, 3) and live.state.prog.get(i, (0, 0, ''))[2] not in ('library', 'check', 'done', 'main'):
        s += '   ' + T('run.rate', f'{r:,.1f}', T('run.unit_shaders') if i == 3 else T('run.unit_files'))
    out.append((s, 0))
    m = live.minutes
    out.append((model.eta_words(m, guess=live.guess) if m >= 0 else T('run.measuring'), BOLD if m >= 0 and not live.guess else DIM if m < 0 else 0))
    if live.state.warnings: out.append((T('run.warnings', live.state.warnings), YELLOW))
    if live.log_error: out.append((T('run.log_error', live.log_error.strerror or live.log_error), RED))
    return out


def draw_run(sc, live, log, now, stopping=None):
    g = sc.g
    title(sc, T('where.run'))
    p = live.plan
    sc.put(2, 1, scr.fit(f'{p.g.get("name", "?")} ({p.x.app:016X}), ' + (T('confirm.version_base') if p.x.ver == 0 else T('run.version', p.x.ver)), sc.w - 4, g.dots), BOLD)
    y = 3
    col = min(6 + max(scr.width(T(k)) for k in STEP_KEYS), sc.w // 2)   # the steps' details
    for i, k in enumerate(STEP_KEYS):
        s = step_status(live, i)
        mark, st = {'done': (g.ok, GREEN), 'now': (g.now, CYAN | BOLD), 'todo': (g.todo, DIM), 'skip': (g.skip, DIM)}[s]
        sc.put(2, y, mark, st)
        sc.put(4, y, scr.fit(T(k), col - 6, g.dots), BOLD if s == 'now' else (DIM if s in ('todo', 'skip') else 0))
        det = step_detail(live, i) if s in ('now', 'done') else T('run.skipped') if s == 'skip' else ''
        if det: sc.put(col, y, scr.fit(det, sc.w - col - 1, g.dots), DIM if s != 'now' else 0)
        y += 1
    y += 1
    bw = max(10, sc.w - 26)
    f = live.step_frac()
    sc.put(2, y, T('run.bar_step'), DIM)
    if f is None: sc.put(14, y, T('run.starting'), DIM)
    else: sc.bar(14, y, bw, f, CYAN); sc.put(15 + bw, y, f'{f * 100:5.1f}%')
    y += 1
    o = live.overall()
    sc.put(2, y, T('run.bar_all'), DIM); sc.bar(14, y, bw, o, GREEN); sc.put(15 + bw, y, f'{o * 100:5.1f}%')
    y += 2
    for t, st in run_lines(live, now):
        sc.put(2, y, scr.fit(t, sc.w - 4, g.dots), st); y += 1
    sc.put(2, y, T('run.log', scr.fit(str(log), sc.w - 4 - scr.width(T('run.log', '')), g.dots, left=True)), DIM); y += 1   # (the path cut at its start: its file name stays)
    rows = sc.h - 2 - y
    if rows > 0:
        tail = [l for l in live.state.lines if not progress.PROGRESS.match(l) and not progress.STEP.match(l)][-rows:]
        for i, l in enumerate(tail): sc.put(2, y + i, scr.fit(l, sc.w - 4, g.dots), RED if l.startswith(('[RED] ', 'error: ')) else DIM)
    if stopping is not None: dialog(sc, stopping)
    footer(sc, T('keys.run'))


def screen_run(ui, plan, start):
    """The run on screen until it ends; → Outcome"""
    proc, live, log = start(plan)
    while True:
        lines, _ = proc.lines()
        for t, l in lines: live.feed(live.t0 + t, l)
        now = time.monotonic()
        live.tick(now)
        live.log_error = proc.log_error
        if proc.finished(): return outcome(proc, live, plan, log, now)
        ui.frame(lambda sc, now=now: draw_run(sc, live, log, now))
        for k in ui.keys(0.25):
            if k.name == 'ctrl-c' or k.name == 'char' and k.text in 'qQ':
                if ui.ask(lambda sc: draw_run(sc, live, log, time.monotonic()), T('run.stop_ask')):
                    proc.stop(on_wait=lambda left: ui.frame(lambda sc: draw_run(sc, live, log, time.monotonic(), T('run.stopping', int(left) + 1))))


# ---- screen 5: the end

def end_lines(o):
    """The end's lines, the same in both modes: [(text, style)]"""
    live, plan = o.live, o.plan
    out = []
    if o.kind == 'done':
        pack = Path(live.state.pack or plan.out)
        out += [(T('end.done', model.duration(o.took)), GREEN | BOLD), ('', 0), (T('end.pack', pack, model.gb(model.pack_size(pack))), 0)]
        if live.state.warnings: out.append((T('end.warnings', live.state.warnings), YELLOW))
        out.append((T('end.log', o.log), DIM))
        out.append(('', 0))
        out.append((T('end.next'), BOLD))
        x = plan.x
        holder = x.folder.name
        dest = f'{x.app:016X}-work/{pack.name}'
        for i, k in enumerate(('end.next1', 'end.next2', 'end.next3')): out.append((T(k, pack, holder, dest), 0))
        out.append((T('end.same_name', holder), DIM))
    elif o.kind == 'stopped':
        out += [(T('end.stopped'), YELLOW | BOLD), ('', 0), (T('end.resume'), 0), (T('end.took', model.duration(o.took)), 0), (T('end.log', o.log), DIM)]
    else:
        code, red = live.state.failure()
        if o.rc is not None and o.rc < 0: code, red = signal_failure(-o.rc)   # killed: no line of its own says why (an earlier [RED] line is not it)
        perm = None if code else no_permission(live.state.lines)
        out += [(T('end.failed'), RED | BOLD), ('', 0)]
        if code and code[0] == 'no_space': code = (code[0], [tool_folder(plan, a) for a in code[1]])
        said = code and model.plain_error(code[0], code[1], '')   # the code's own text, which says what to do ('' for a code this interface has no text for)
        if said: out.append((said, BOLD))
        elif perm: out.append((T('end.no_permission', perm), BOLD))
        elif red: out.append((tool_log(plan, red), BOLD))
        else: out.append((T('end.exit', o.rc), BOLD))
        if said and red: out.append((T('end.detail', tool_log(plan, red)), DIM))
        if not said and not perm: out.append((T('end.retry') if code else T('end.report'), 0))
        out.append((T('end.log', o.log), DIM))
        tail = plain_tail(live.state.lines)[-8:]
        if tail: out.append(('', 0)); out.append((T('end.last'), BOLD))
        out += [(tool_log(plan, l), RED if l.startswith('[RED] ') else DIM) for l in tail]
    if live.log_error: out.insert(2, (T('run.log_error', live.log_error.strerror or live.log_error), RED))
    return out


# The folder (a Plan attribute) each tool writes in: a 'no_space' failure whose tool's output named no path has the tool's name as its argument (pack_errors)
TOOL_FOLDER = {'patch_nso.py': 'out', 'shader_split.py': 'pkg', 'vk_android_build.py': 'cache', 'pack_android.py': 'out'}


def tool_folder(plan, a):
    """A failure code's argument as the end shows it: a tool's name, which the packer gives when the tool's output names no path ('no_space patch_nso.py'), as
    the folder that tool writes in"""
    f = getattr(plan, TOOL_FOLDER.get(a, ''), None)
    return str(f) if f is not None else a


def tool_log(plan, red):
    """The packer's sentence with its pointer to lines 'above' (in its own output, which the end does not show whole) made the tool's log in the pack folder"""
    return re.sub(r' \(the last lines of (\S+) above\)', lambda m: ' ' + T('end.tool_log', Path(plan.out) / m.group(1)), red)


def plain_tail(lines):
    """The packer's lines as the end shows them: no progress, step or error: lines (read above), and of a Python traceback only its last line, what was raised
    (its frames say nothing the user can act on; the log has them all)"""
    out, tb = [], False
    for l in lines:
        if progress.PROGRESS.match(l) or progress.STEP.match(l) or progress.pack_errors.parse(l) is not None: continue
        if l.startswith(('Traceback (most recent call last):', 'During handling of the above exception', 'The above exception was the direct cause')):
            tb = True; continue
        if tb and (not l.strip() or l.startswith((' ', '\t'))): continue   # its frames, their source lines and markers
        tb = False
        out.append(l)
    return out


def signal_failure(sig):
    """(code, sentence) for a packer ended by signal sig: SIGKILL is what the out-of-memory killer sends (pack_errors.of_exit)"""
    if sig == 9: return ('out_of_memory', ['pack_android.py']), T('end.signal', 'SIGKILL')
    try: name = signal.Signals(sig).name
    except ValueError: name = str(sig)
    return None, T('end.signal', name)


PERMISSION = re.compile(r"PermissionError: \[Errno 13\] [^:']*: '([^']+)'|OSError: \[Errno 30\] Read-only file system: '([^']+)'")


def no_permission(lines):
    """The path of a PermissionError or read-only file system in the packer's last lines (a traceback: its failure has no code), or None"""
    for l in reversed(lines[-30:]):
        if m := PERMISSION.search(l): return m.group(1) or m.group(2)
    return None


def draw_end(sc, o, scroll=0):
    """The end's lines from line scroll on (→ the scroll used: at most what still fills the screen)"""
    title(sc, T('where.end'))
    lines = [(l, st) for t, st in end_lines(o) for l in (scr.wrap(t, sc.w - 4) if t else [''])]
    rows = sc.h - 3
    scroll = max(0, min(scroll, len(lines) - rows))
    for i, (l, st) in enumerate(lines[scroll:scroll + rows]): sc.put(2, 2 + i, l, st)
    if scroll + rows < len(lines): sc.put(sc.w - 3, sc.h - 2, sc.g.more, DIM)
    footer(sc, T('keys.end'))
    return scroll


def screen_end(ui, o):
    scroll = 0
    while True:
        ui.frame(lambda sc, scroll=scroll: draw_end(sc, o, scroll))
        scroll = ui.drawn   # (draw_end keeps it within the lines)
        for k in ui.keys():
            if k.name in ('enter', 'esc', 'ctrl-c') or k.name == 'char' and k.text in 'qQ': return
            if k.name in ('down', 'pgdn'): scroll += 1 if k.name == 'down' else 10
            if k.name in ('up', 'pgup'): scroll = max(0, scroll - (1 if k.name == 'up' else 10))


# ---- the plain mode

class Plain:
    """Questions as input() lines, progress as print() lines. yes: the confirmation's answer is yes (no question asked)"""
    def __init__(self, out=None, inp=None, yes=False):
        self.out, self.inp, self.yes = out or sys.stdout, inp or input, yes

    def say(self, s=''): print(s, file=self.out, flush=True)

    def ask(self, q, default='', eof=None):
        try: a = self.inp(q)
        except EOFError: return default if eof is None else eof
        return a.strip() or default


def plain_checks(ui, run_checks):
    ui.say(T('checks.head'))
    res = run_checks()
    for c in res:
        ui.say(f'  [{"ok" if c.ok else "missing"}] {c.name}: {c.found}')
        for d in c.detail: ui.say('        ' + d)
        if not c.ok and fix_shown(res, c): ui.say('        ' + T('checks.same_fix'))
        elif not c.ok and c.fix:
            ui.say('        ' + T('checks.fix'))
            for f in c.fix: ui.say('          ' + f)
    bad = sum(1 for c in res if not c.ok)
    ui.say(T('checks.missing_plain', bad) if bad else T('checks.ok'))
    if bad: raise SystemExit(1)
    return res


def plain_pick(ui, path, state, check):
    while True:
        last = state.get('last', '')
        if not (last and Path(last).is_dir()): last = ''
        asked = path is None
        if asked:
            path = ui.ask(T('plain.pick', last) if last else T('plain.pick_none'), '')
            if not path: raise Quit()
        p = Path(os.path.expanduser(browser.unquote(path)))
        if asked and last and not p.is_absolute(): p = Path(last) / p   # a name typed at the question: in the last folder, as the question says
        ui.say(T('pick.reading', p))
        try: return p, check(p)
        except model.Bad as e:
            ui.say('  ' + model.plain_error(e.code, e.args_, e.msg))
            if ui.yes: raise SystemExit(1) from None
            path = None


def plain_confirm(ui, plan):
    ui.say(''); ui.say(T('confirm.head'))
    for lab, val, _ in plan_lines(plan, keys=False): ui.say(f'  {lab:<14}{val}')
    if plan.short(): ui.say('  ' + T('confirm.short'))
    if not ui.yes:
        a = ui.ask(T('plain.workers', plan.J), str(plan.J))
        if a.isdigit() and int(a) > 0: plan.J = int(a)
        a = ui.ask(T('plain.start'), 'y', eof='n')   # Enter is yes; no answer at all (the input ended) is no
        if a.lower() not in ('y', 'yes'): raise Quit()
    if why := plan.ready():
        ui.say('  ' + why); raise SystemExit(1)
    return plan


def plain_run(ui, plan, start):
    proc, live, log = start(plan)
    ui.say(T('run.log', log))
    return plain_run_on(ui, plan, proc, live, log)


def plain_run_on(ui, plan, proc, live, log):
    last_step, last_said = live.state.step, time.monotonic()
    try:
        while True:
            lines, _ = proc.lines()
            for t, l in lines:
                e = live.feed(live.t0 + t, l)
                if e.kind == 'red': ui.say('  ' + tool_log(plan, l))   # (an error: line is for the end, which says its code in words)
                if e.kind == 'step' and live.state.step != last_step:   # each step as it starts
                    last_step, last_said = live.state.step, time.monotonic()
                    ui.say(f'[{model.duration(t)}] {T(STEP_KEYS[last_step])}')
            now = time.monotonic()
            live.tick(now)
            i = live.state.step
            if i >= 0 and now - last_said >= PLAIN_EVERY:
                det = step_detail(live, i)
                eta_s = model.eta_words(live.minutes, guess=live.guess) if live.minutes >= 0 else ''
                ui.say(f'[{model.duration(now - live.t0)}] {T(STEP_KEYS[i])}' + (f': {det}' if det else '') + (f'; {eta_s}' if eta_s else ''))
                last_said = now
            if proc.log_error and not live.log_error: live.log_error = proc.log_error; ui.say('  ' + T('run.log_error', proc.log_error.strerror or proc.log_error))
            if proc.finished(): return outcome(proc, live, plan, log, now)
            time.sleep(0.2)
    except KeyboardInterrupt:
        if not ui.yes and sys.stdin.isatty():   # asked, as the full-screen mode asks; no answer (the input ended, Ctrl+C again) is yes
            try: go_on = ui.inp(T('plain.stop_ask')).strip().lower() in ('n', 'no')
            except (EOFError, KeyboardInterrupt): go_on = False
            if go_on: return plain_run_on(ui, plan, proc, live, log)
        ui.say(T('run.stopping_plain'))
        proc.stop()
        return outcome(proc, live, plan, log, time.monotonic())


def plain_end(ui, o):
    ui.say('')
    for t, _ in end_lines(o): ui.say(t)


# ---- the flow

class App:
    def __init__(self, a):
        self.a = a
        self.state, self.state_problem = browser.load_state()
        self.proc = None

    def check(self, path, version=None):
        return model.identify(path, version)

    def plan(self, x, out=None):
        a = self.a
        return model.Plan(x, out or a.out, a.shader_cache, a.shader_pkg, a.J, a.j)

    def remake(self, plan, version=None, out=None):
        x = self.check(plan.x.folder, version) if version is not None else plan.x
        p = self.plan(x, out or (plan.out if self.a.out or out else None))
        p.J = plan.J
        return p

    def start(self, plan):
        """Starts the packer for plan → (runner.Run, model.Live, the log's path); model.Bad when its log cannot be made (nothing was started)"""
        log = plan.log_path()
        try: proc = runner.Run(plan.argv(), log, cwd=str(model.REPO))
        except OSError as e:
            why = model.writable(log.parent) or T('confirm.not_writable', log, e.strerror or e)
            raise model.Bad(None, [], why) from None
        self.proc = proc
        live = model.Live(plan, proc.t0)
        return proc, live, log

    def remember(self, picked):
        self.state = browser.remember(self.state, picked.parent)   # the folder that holds the game (a .nup file or a dump folder): where the next pick starts
        return browser.save_state(self.state)

    def emergency(self):
        """The window closes (Windows' close event): the child and its tools end now"""
        if self.proc is not None and self.proc.poll() is None: self.proc.kill()

    def full(self, ui):
        screen_checks(ui, checks.run_all)
        p = Pick(self.state, self.a.start_folder)
        if self.state_problem: p.say(T('pick.state_bad', self.state_problem), YELLOW)
        picked = None
        if self.a.game:
            try: picked, x = Path(self.a.game), self.check(self.a.game, self.a.version)
            except model.Bad as e: p.say(model.plain_error(e.code, e.args_, e.msg), RED); picked = None
        while True:
            if picked is None: picked, x = screen_pick(ui, p, lambda q: self.check(q, self.a.version))
            prob = self.remember(picked)
            if prob: p.say(T('pick.state_bad', prob), YELLOW)
            plan = self.plan(x)
            while (plan := screen_confirm(ui, plan, self.remake)) is not None:
                try: o = screen_run(ui, plan, self.start); break
                except model.Bad as e: ui.tell(lambda sc, plan=plan: draw_confirm(sc, plan), e.msg)   # (the log could not be made: back to the plan)
            if plan is not None: break
            picked = None
            p.b.go(p.b.folder); p.say('')
        screen_end(ui, o)
        return o

    def plain(self, ui):
        plain_checks(ui, checks.run_all)
        picked, x = plain_pick(ui, self.a.game, self.state, lambda q: self.check(q, self.a.version))
        prob = self.remember(picked)
        if prob: ui.say(T('pick.state_bad', prob))
        plan = plain_confirm(ui, self.plan(x))
        try: o = plain_run(ui, plan, self.start)
        except model.Bad as e:
            ui.say('  ' + e.msg); raise SystemExit(1) from None
        plain_end(ui, o)
        return o


def on_signal(sig, _frame): raise SystemExit(128 + sig)   # SIGTERM, SIGHUP: the finally blocks stop the run and put the terminal back


def main(a):
    """→ the exit code: 0 the pack is ready, 1 the run failed or something is missing, 2 left before a run, 3 the run was stopped (128 + the signal: ended by
    SIGHUP, a closed window, or SIGTERM, its run stopped)"""
    if sys.stdout is None: return 1   # no output at all (pythonw, or started with its output closed): nothing could be shown or asked
    sys.stdout.reconfigure(errors='replace')   # pyright: ignore[reportAttributeAccessIssue]  # a path the console cannot print comes out with a mark, not an exception
    for s in ('SIGTERM', 'SIGHUP'):
        if hasattr(signal, s): signal.signal(getattr(signal, s), on_signal)
    app = App(a)
    t, why = (None, 'plain') if a.plain else term.open_terminal()
    color = not os.environ.get('NO_COLOR') and not a.no_color
    try:
        if t is None:
            if not a.plain and why: print(T('plain.why', why), flush=True)
            o = app.plain(Plain(yes=a.yes))
        else:
            g = scr.Glyphs(False) if a.ascii or not term.unicode_glyphs() else scr.glyphs_for(getattr(sys.stdout, 'encoding', 'ascii'))
            if isinstance(t, term.WindowsTerminal): t.on_close = app.emergency
            try:
                t.enter()
                o = app.full(Full(t, g, color))
            finally: t.restore()
            if o is not None:
                for line, _ in end_lines(o): print(line)
    except Quit as q:
        print(T('app.quit'))
        return q.code
    except KeyboardInterrupt:   # Ctrl+C in the plain mode's questions (a run's Ctrl+C is asked about in plain_run_on)
        print(); print(T('app.quit') if app.proc is None else T('end.stopped'))
        return 2 if app.proc is None else 3
    finally:   # a run still going here is ending with the interface (SIGTERM, SIGHUP, an exception): its packer is stopped as q stops it
        if app.proc is not None and app.proc.poll() is None:
            say(T('run.stopping_plain'))
            app.proc.stop(wait=10)
            app.proc.close()
    return {'done': 0, 'failed': 1, 'stopped': 3}[o.kind]


def say(s):
    """A line on the terminal, when there still is one (after SIGHUP its window is gone: the line is in no one's view, and the stop goes on)"""
    try: print(s, flush=True)
    except OSError: return

