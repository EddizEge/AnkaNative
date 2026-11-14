# What the screens show, apart from how they draw it (app.py draws it full-screen or as lines): the game a pick holds (pack_android.identify, the packer's own
# checks), the plan of a run (where the pack goes, the workers, the disk space it needs, what an earlier run left that this one keeps, a rough time), the run as
# its lines tell it (progress.State and eta.Eta), and the finished pack. No logic of the packer is copied here: what it decides, it is asked (identify, default_out,
# shader_dirs, library_current, rewrite_keys).
import os, shutil, sys, time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
for d in ('dm/gfx/tools', 'dm/tools', 'dm/games', 'scripts'): sys.path.insert(0, str(REPO / d))
from prepare_tui import eta, progress   # noqa: E402
from prepare_tui.texts import T, TEXTS, plural   # noqa: E402

PACK_ANDROID = REPO / 'scripts' / 'pack_android.py'
# Disk space a run takes, bytes per shader of the profile's map (Ocarina of Time's full run on this computer, 2026-11-14: FIGURES in eta.py): the Switch shader package
# the split writes, each shader's result file, the build cache's entries, and the library (written once in the cache and copied into the pack)
PKG_B, RESULT_B, CACHE_B, LIB_B = 12_300, 30_000, 55_000, 19_700
MODULE_X = 3   # a rewritten module and its tables against the module's size in the dump (compressed)
MEM_WORKER = 0.5 * 2**30   # memory one shader worker takes, with room: a vk_android_build.py worker process and the translator it runs, 0.12-0.14 and about 0.1 GiB
                           # resident on this computer's Ocarina of Time run (2026-11-14, 11 workers)
MEM_KEEP = 1.5 * 2**30     # memory left to the rest of the computer when the workers are counted


class Bad(Exception):
    """A pick that cannot be packed: the packer's code and arguments (or None) and its English sentence"""
    def __init__(self, code, args, msg):
        super().__init__(msg); self.code, self.args_, self.msg = code, list(args), msg


def plain_error(code, args, msg):
    """A failure in plain words: the code's text with what the user can do, else the packer's sentence"""
    if code and f'error.{code}' in TEXTS:
        try: return T(f'error.{code}', *args)
        except (IndexError, KeyError): return msg   # an error: line with fewer arguments than its text (a packer of another version)
    return msg


def identify(path, version=None):
    """The game a .nup file or dump folder holds, as pack_android.py checks it → its Game; Bad when it cannot be packed"""
    import pack_android, pack_dump, nup   # pyright: ignore[reportMissingImports]  # scripts/, on sys.path above
    p = Path(path).expanduser().resolve()
    if not p.exists(): raise Bad(None, [], T('pick.missing', p))
    if p.is_file() and not nup.is_nup(p): raise Bad(None, [], T('pick.not_game', p))
    # a folder with neither base/ nor updates/ is no dump at all (an incomplete dump has one of them, and the packer says what it lacks)
    if p.is_dir() and not (p / 'base').is_dir() and not (p / 'updates').is_dir(): raise Bad(None, [], T('pick.not_game', p))
    try: x = pack_android.identify(p, version)
    except (pack_android.Error, pack_dump.Error) as e: raise Bad(getattr(e, 'code', None), getattr(e, 'code_args', ()), str(e)) from None
    except nup.Error as e: raise Bad('unreadable_file', [p], str(e)) from None
    except OSError as e: raise Bad(None, [], f'{p}: {e.strerror or e}') from None
    # the path as picked, when it is a link (or under one): the packer works beside the file it points to (pack_android.py resolves it too), which the
    # confirm screen then says
    x.picked = Path(os.path.abspath(Path(path).expanduser()))
    return x


def free_memory():
    """Bytes of memory free for new work (Linux MemAvailable, Windows' available physical memory), else the total, else None"""
    try:
        if os.name == 'nt':
            import ctypes

            class MS(ctypes.Structure):
                _fields_ = [('len', ctypes.c_ulong), ('load', ctypes.c_ulong), ('total', ctypes.c_ulonglong), ('avail', ctypes.c_ulonglong), ('tp', ctypes.c_ulonglong),
                            ('ap', ctypes.c_ulonglong), ('tv', ctypes.c_ulonglong), ('av', ctypes.c_ulonglong), ('ext', ctypes.c_ulonglong)]
            m = MS(); m.len = ctypes.sizeof(MS)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)): return m.avail   # pyright: ignore[reportAttributeAccessIssue]
            return None
        try:
            with open('/proc/meminfo') as f:
                for l in f:
                    if l.startswith('MemAvailable:'): return int(l.split()[1]) * 1024
        except OSError: pass   # no /proc (the Mac): the total below
        return os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
    except (OSError, ValueError, AttributeError): return None


def default_workers():
    """Shaders translated at once: one a CPU thread, as many as the free memory holds (MEM_WORKER each, MEM_KEEP left over); the packer's default (8) when the CPU
    count is not known"""
    cpu = os.cpu_count()
    if not cpu: return 8
    mem = free_memory()
    n = cpu if mem is None else min(cpu, int((mem - MEM_KEEP) // MEM_WORKER))
    return max(1, n)


def existing(p):
    """p or the nearest folder above it that exists"""
    p = Path(p)
    while not p.exists() and p.parent != p: p = p.parent
    return p


def size_of(p):
    """Bytes of the files under p (0 when it is not there)"""
    p = Path(p)
    if p.is_file(): return p.stat().st_size
    n = 0
    for root, _, files in os.walk(p):
        for f in files:
            try: n += os.stat(os.path.join(root, f)).st_size
            except OSError: continue   # a file removed while counting
    return n


def results_done(lib):
    """Shader results an earlier build left in the library's result folder (vk_android_build.py's <output>/v-<V>/results/<key>.r): kept by the next build when
    they are of these tools"""
    try:
        with os.scandir(Path(lib) / 'results') as it: return sum(1 for e in it if e.name.endswith('.r'))
    except OSError: return 0


class Plan:
    """A run of the packer on a game: the folders, workers, what is kept from an earlier run, the space and time it needs"""
    def __init__(self, x, out=None, shader_cache=None, shader_pkg=None, J=None, j=None):
        import pack_android   # pyright: ignore[reportMissingImports]  # scripts/, on sys.path above
        self.x, self.g = x, x.g
        self.work = pack_android.work_dir(x.folder, x.app)
        self.out = Path(out).expanduser().resolve() if out else pack_android.default_out(x.folder, x.app).resolve()
        self.cache_arg, self.pkg_arg = shader_cache, shader_pkg
        self.cache, self.lib, self.pkg = pack_android.shader_dirs(self.work, Path(shader_cache).expanduser() if shader_cache else None,
                                                                 Path(shader_pkg).expanduser() if shader_pkg else None)
        self.J = J or default_workers(); self.j = j
        mp = self.g.dir / 'shader_map.tsv'
        self.mix = eta.shader_mix(mp) if mp.is_file() else None
        self.shaders = self.mix[0] if self.mix else 0
        self.survey()

    def survey(self):
        """What an earlier run left: the modules with a current key, a current library, the package, translated shaders"""
        import pack_android   # pyright: ignore[reportMissingImports]  # scripts/, on sys.path above
        self.kept_mods, self.replaced = [], []   # replaced: modules of a pack already in the folder that this run rewrites (its keys are not current)
        for m, src in self.x.srcs.items():
            kf = self.out / f'{m}.android.key'
            if (self.out / f'{m}.patched.bin').is_file() and kf.is_file() and kf.read_text().strip() in pack_android.rewrite_keys(src, self.g, self.out): self.kept_mods.append(m)
            elif (self.out / f'{m}.patched.bin').is_file() or kf.is_file(): self.replaced.append(m)
        self.rewrite = len(self.kept_mods) < len(self.x.mods)
        self.lib_current = pack_android.library_current(self.lib, self.g)
        self.split = not self.lib_current and not self.pkg.is_dir()
        self.build = not self.lib_current
        self.translated = min(results_done(self.lib), self.shaders) if self.build else 0
        dst = self.out / 'shaders' / self.lib.name / pack_android.VK_LIB
        self.lib_in_pack = dst.is_file() and self.lib_current and dst.stat().st_size == (self.lib / pack_android.VK_LIB).stat().st_size

    def need(self):
        """{folder that exists: bytes this run writes on its drive}, the drives told apart by their device"""
        parts = []
        if self.rewrite:
            msize = 0
            for m, src in self.x.srcs.items():
                if m in self.kept_mods: continue
                try: msize += src.stat().st_size
                except (OSError, AttributeError): msize += 64 * 2**20   # a module in a NUP file whose size its path does not give: a figure
            parts.append((self.out, msize * MODULE_X))
        if self.split: parts.append((self.pkg, self.shaders * PKG_B))
        if self.build:
            left = self.shaders - self.translated
            parts.append((self.cache, left * (RESULT_B + CACHE_B) + self.shaders * LIB_B))
        if not self.lib_in_pack: parts.append((self.out, self.shaders * LIB_B))
        by = {}
        for p, n in parts:
            e = existing(p)
            try: dev = os.stat(e).st_dev
            except OSError: dev = str(e)
            k = by.setdefault(dev, [e, 0]); k[1] += n
        return [(e, n) for e, n in by.values()]

    def space(self):
        """[(folder, bytes needed, bytes free)] for each drive the run writes on"""
        out = []
        for e, n in self.need():
            try: free = shutil.disk_usage(e).free
            except OSError: free = -1
            out.append((e, n, free))
        return out

    def short(self):
        """The drives where the run needs more than is free"""
        return [s for s in self.space() if 0 <= s[2] < s[1]]

    def shader_s(self): return eta.SHADER_CPU_S / max(1, min(self.J, os.cpu_count() or self.J))   # (workers beyond the CPU threads translate nothing faster)

    def rough(self):
        """Seconds the run takes at the fixed figures"""
        return eta.rough(self.rewrite, self.split, (self.shaders - self.translated) if self.build else 0, self.shaders if self.build else 0, self.shader_s())

    def argv(self, python=None):
        from prepare_tui import runner
        return runner.packer_argv(PACK_ANDROID, self.x.folder, self.x.ver, self.out, self.J, self.j, self.cache_arg and self.cache, self.pkg_arg and self.pkg, python)

    def log_dir(self): return self.out.parent / 'prepare-logs'

    def log_path(self):
        return self.log_dir() / f'prepare-{time.strftime("%Y%m%d-%H%M%S")}.log'

    def ready(self):
        """Before the run starts: the folders it writes in can be written (the pack folder, the log folder, the shader cache, the package's folder; each made when
        it is not there, but never the package itself, whose absence starts the split), and no other run of this interface uses the pack folder or the shader cache
        (their locks, held until this process ends). → '' or what stops the run, in plain words"""
        for d in (self.out, self.log_dir(), self.cache, self.pkg.parent):
            if why := writable(d): return why
        for d, f in ((self.out, self.log_dir() / f'{self.out.name}.lock'), (self.cache, self.cache / '.prepare.lock')):
            if f in HELD: continue
            k = Lock(f)
            if not k.take(): return T('confirm.busy', d)
            HELD[f] = k
        return ''


def writable(d):
    """'' when a file can be made in folder d (made first when it is not there), else why not in plain words"""
    import tempfile, pack_errors
    try:
        Path(d).mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryFile(dir=d): pass
    except OSError as e:
        c = pack_errors.of_os_error(e)
        if c and c[0] == 'no_space': return T('error.no_space', d)
        return T('confirm.not_writable', d, e.strerror or e)
    return ''


HELD = {}   # the locks this process holds, by their file: one each (this process's second flock of a file it holds, through another descriptor, is refused)


class Lock:
    """An exclusive lock on a file, released when this process ends however it ends (flock on POSIX, msvcrt.locking on Windows): two runs of the interface on the
    same pack folder or shader cache would translate the same shaders and write the same files. The file stays (removing it would let a run that opened it just
    before lock a file no other run sees)"""
    def __init__(self, path): self.path, self.fd = Path(path), None

    def take(self):
        """→ whether this process holds the lock now (False: another holds it)"""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)   # pyright: ignore[reportAttributeAccessIssue]
            else:
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)   # pyright: ignore[reportAttributeAccessIssue]
        except OSError as e:   # (BlockingIOError on POSIX, EACCES / EDEADLOCK from msvcrt): another run holds it
            os.close(fd)
            if os.name == 'nt' or isinstance(e, BlockingIOError): return False
            raise
        self.fd = fd
        return True


def gb(n): return f'{n / 1e9:.1f} GB'


def duration(s):
    """Seconds as '1 h 05 min', '12 min 05 s', '45 s'"""
    s = int(round(s))
    if s >= 3600: return T('time.hm', s // 3600, s % 3600 // 60)
    if s >= 60: return T('time.ms', s // 60, s % 60)
    return T('time.s', s)


def minutes_words(s):
    """Seconds as the estimate says them: 'less than a minute', '12 minutes', '1 hour 15 minutes' (rounded as eta.rnd)"""
    m = eta.rnd(s)
    if m == 0: return T('eta.less_plain')
    if m < 60: return plural(m, 'eta.n_minute', 'eta.n_minutes')
    h, mm = divmod(m, 60)
    return plural(h, 'eta.n_hour', 'eta.n_hours') + ('' if mm == 0 else ' ' + plural(mm, 'eta.n_minute', 'eta.n_minutes'))


def eta_words(minutes, now=None, guess=False):
    """'About 12 minutes left, done around 21:40' for the minutes to show (Eta.minutes), '' for none; guess: the time is still mostly the fixed figure (Eta.guess)"""
    if minutes < 0: return ''
    clock = time.strftime('%H:%M', time.localtime((now or time.time()) + max(minutes, 1) * 60))
    if minutes == 0: left = T('eta.less')
    elif minutes < 60: left = plural(minutes, 'eta.minute', 'eta.minutes')
    else:
        h, m = divmod(minutes, 60)
        left = plural(h, 'eta.hour', 'eta.hours') if m == 0 else T('eta.hours_minutes', plural(h, 'eta.hour', 'eta.hours'), m)
    return T('eta.line_guess' if guess else 'eta.line', left, clock)


class Live:
    """A run as its lines tell it: progress.State, the estimate, the rate of the step in hand, the steps' weights for the whole job's bar"""
    def __init__(self, plan, now):
        self.plan, self.state = plan, progress.State()
        self.t0 = now
        lib = plan.shaders if plan.build else 0
        self.eta = eta.Eta(plan.shaders - plan.translated if plan.build else 0, lib, plan.shader_s())
        self.eta.mix(plan.mix)
        self.eta.start(now)
        self.hist = []   # (time, done) of the step in hand's counted part, for its rate
        self.step_t = {}  # step → its start
        self.minutes, self.guess = -1, False
        self.log_error = None   # the run's log could not be written on (runner.Run.log_error)
        mods = max(1, len(plan.x.mods))
        left = (len(plan.x.mods) - len(plan.kept_mods)) / mods if plan.rewrite else 0.0
        self.weights = [2.0, eta.REWRITE_S * left, eta.SPLIT_S if plan.split else 0.0,
                        ((plan.shaders - plan.translated) * plan.shader_s() + plan.shaders * eta.LIBRARY_S) if plan.build else 0.0, eta.PACK_S]
        # what an earlier run finished, by the same figures, counted as done in the whole job's bar: a resumed run's bar goes on from where the stopped one was,
        # not back to the start (this run's own steps are what the bar's remaining part is made of)
        self.kept = eta.REWRITE_S * (1 - left) + (0.0 if plan.split else eta.SPLIT_S) + (
            plan.translated * plan.shader_s() if plan.build else plan.shaders * (plan.shader_s() + eta.LIBRARY_S))

    def feed(self, t, line):
        """A line of the run at time t (the interface's monotonic clock) → its progress.Event"""
        e = self.state.feed(line)
        if e.kind == 'step' and e.name in progress.STEPS:
            i = progress.STEPS.index(e.name)
            self.step_t[i] = t; self.hist = []
            self.eta.step_to(i, t)
        elif e.kind == 'progress' and self.state.step >= 0:
            self.eta.progress(self.state.step, e.done, e.total, e.stage, t)
            if self.hist and (e.stage != self.hist[-1][2] or e.done < self.hist[-1][1]): self.hist = []
            self.hist.append((t, e.done, e.stage)); del self.hist[:-400]
        return e

    def tick(self, t):
        self.minutes = self.eta.minutes(t)
        self.guess = self.minutes >= 0 and self.eta.guess
        return self.minutes

    def rate(self, t, span=60.0):
        """Units a second of the step in hand over the last span seconds, or None"""
        h = [x for x in self.hist if x[0] >= t - span]
        if len(h) < 2 or h[-1][0] - h[0][0] < 5: return None
        return (h[-1][1] - h[0][1]) / (h[-1][0] - h[0][0])

    def step_frac(self):
        """The step in hand's share done (None: not known, such as the split's start)"""
        s = self.state.step
        if s not in self.state.prog: return None if s in (progress.STEPS.index('split'), progress.STEPS.index('shaders')) else 0.0
        d, tot, st = self.state.prog[s]
        if s == progress.STEPS.index('shaders') and st in ('library', 'check', 'done'): return 1.0
        return d / tot if tot > 0 else None

    def overall(self):
        """The whole job's share done, by the steps' weights, with what an earlier run finished (kept) counted as done"""
        w = self.weights; s = self.state.step
        tot = sum(w) + self.kept
        if tot <= 0: return 0.0
        if s < 0: return self.kept / tot
        before = sum(w[:s]) + self.kept
        f = self.step_frac() or 0.0
        cur = w[s]
        if s == progress.STEPS.index('shaders') and self.plan.build:   # the translation, then the library's write and check
            tr = (self.plan.shaders - self.plan.translated) * self.plan.shader_s()
            d, n, st = self.state.prog.get(s, (0, 0, ''))
            k = self.plan.translated if n == self.plan.shaders else 0   # the count includes the shaders an earlier run translated: this run's share is the rest
            f = max(0, d - k) / (n - k) if n > k else f
            cur_done = tr + self.plan.shaders * eta.LIBRARY_S * 0.5 if st in ('library', 'check', 'done') else tr * f
            return min(1.0, (before + cur_done) / tot)
        return min(1.0, (before + cur * f) / tot)


def pack_size(out): return size_of(out)
