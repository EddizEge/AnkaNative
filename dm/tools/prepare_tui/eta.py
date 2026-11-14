# The time a run of the packer has left, for scripts/prepare.py's run screen: the phone's estimator (android/app ondevice Eta.java, which Prepare's time left on the
# launcher's card uses) ported to the steps a computer's run has (pack_android.py in one process: identify, rewrite, split, shaders, pack) and to the computer's
# figures. It estimates the whole run from the step in hand to the end, never only that step. Times are seconds of the interface's monotonic clock.
# Running time only: a run is one process from start to end here, so the clock is the time since it started; a stopped run that is started again is a new run, whose
# first progress line of a part is a baseline (what was done before it is not this run's work) and whose first step forward after it starts the timing (the tool's
# set-up before it is no translating).
# Each part of the run is one of:
#   counted  the split's scan of the dump's files and the shaders step's "translate" stage. Its rate is the work done in the last WINDOW_S of working time, and the
#            finish time is smoothed over SMOOTH_S. Working time is running time with each gap between two steps forward cut to GAP_LINES of the usual gaps, so a
#            stall is not slow work. The translation runs the non-vertex shaders first and the vertex ones after (vk_android_build.py's two phases); a vertex shader
#            counts as VERTEX_COST of another when the profile's shader_map.tsv gives the count of each (shader_mix) and the tool translates all of them
#   timed    the shaders step's "library" and "check" stages and the pack step (the library's copy into the pack): a fixed time each, counted down
#   unknown  identify, the rewrite (three modules of very different sizes) and the split's start before its scan ("main"): no estimate
# The steps still to come are added from what is known: the shaders after a split at shader_s each, the library at LIBRARY_S an entry, the pack at PACK_S. A counted
# part says nothing until it has its own measurement (MIN_S of running time with progress, or MIN_SMALL_S and MIN_SMALL_SHARE of it for a small job), unless an
# earlier part of this run had one: then the fixed figure stands in meanwhile. While the translation's time is the figure (the split, and the translation before its
# own rate), the estimate is marked a guess (Eta.guess), and the screens say so: a computer busier than the figures' was 40 % slower. The finish time is smoothed
# from the guess to the measured time, and stays called a guess until it is within GUESS_NEAR of the measured one. It says nothing either when progress stalls (STALL_S), when a part's total is 0,
# changes or is passed, or its count goes back, when a timed part has taken twice its figure, or when more than MAX_S is left.
# The figures are those of a run on a computer (FIGURES below); dm/tools/tests/test_prepare_tui.py replays that run's trace (prepare_data/prepare-pc-oot.trace.txt)
# with them and checks the estimate's error along it.
import math
from collections import deque

WINDOW_S = 300.0       # the window of working time the rate is measured over
MIN_S = 120.0          # a counted part's own rate is used after this much running time from its first step forward
MIN_SMALL_S, MIN_SMALL_SHARE = 30.0, 0.2   # ... or after this much, once this share of its work is done
STALL_S = 180.0        # no progress for this long: no estimate
SMOOTH_S = 120.0       # the finish time follows a new estimate with this time constant
GAP_LINES, GAP_MIN_S, GAP_START_S = 4.0, 20.0, 60.0   # the most a gap between two steps forward adds to the working time (Eta.java)
MAX_S = 10 * 3600.0    # more than this left: no estimate
GUESS_NEAR = 0.1      # a smoothed time within this share of the measured one is no longer called a guess
LATE_S = 30.0          # a timed part over its figure says this much is left (at most its figure) until it has taken twice its figure

# FIGURES: Ocarina of Time from its .nup on Linux, 12 CPU threads, -J 11, other builds running beside it (2026-11-14, the trace's header): the rewrite 68 s, the
# split 158 s (46 s before its scan), the translation 1,309 s from the step's start (non-vertex shaders 26.6 a second, vertex ones 58.5), the library's write
# and check 21 s, the pack 3 s; 26 min in all. A computer's own speed replaces them as soon as it is measured: they only say something before that (the
# confirm screen's rough time, the shaders after the split, the translation's first minutes) and the steps after the translation.
VERTEX_COST = 0.45     # a vertex shader's translation against another's (26.6 / 58.5 a second; the phone's 0.37-0.43)
LIBRARY_S = 0.0005     # the library's write and check, seconds per entry (21 s for 42,854)
PACK_S = 5.0           # the pack step: the library copied into the pack, pack.tsv (3 s)
SHADER_CPU_S = 0.34    # seconds of one worker per shader, both phases and the set-up (1,309 s x 11 workers / 42,854): shader_s = SHADER_CPU_S / workers, the workers
                       # counted up to the CPU threads (model.Plan.shader_s)
SPLIT_S = 160.0        # the split, start to end (the confirm screen's rough time)
REWRITE_S = 70.0       # the rewrite of every module (the confirm screen's rough time)

IDENTIFY, REWRITE, SPLIT, SHADERS, PACK = range(5)   # progress.STEPS' indexes
UNKNOWN, COUNTED, LIBRARY, PACKING = 'unknown', 'counted', 'library', 'pack'


def shader_mix(path):
    """The profile's shader_map.tsv as vk_android_build.py counts it: (shaders, non-vertex shaders), or None when it has none. A row of more than four columns
    whose package file ends in .bin is a shader, each key (the fourth column) once; a vertex shader's package file starts with "vs" (Eta.shaderMix)"""
    keys, slow = set(), 0
    with open(path, encoding='utf-8') as f:
        for l in f:
            c = l.rstrip('\n').split('\t')
            if len(c) <= 4 or not c[0].endswith('.bin') or c[3] in keys: continue
            keys.add(c[3])
            if not c[0].startswith('vs'): slow += 1
    return (len(keys), slow) if keys else None


def kind_of(i, s):
    if i == SPLIT: return UNKNOWN if s == 'main' else COUNTED
    if i == SHADERS: return LIBRARY if s in ('library', 'check', 'done') else COUNTED
    if i == PACK: return PACKING
    return UNKNOWN


def timed(budget, el):
    """A timed part's seconds left: its budget counted down, then LATE_S (at most the budget) until it has taken twice it (and a minute more): then none"""
    if el > max(2 * budget, budget + 60): return math.nan
    return max(budget - el, min(budget, LATE_S))


class Eta:
    """shaders: what the shaders step will translate as far as known (0: no shaders step to come); library: the entries of the library it writes (0: none written);
    shader_s: seconds a shader before measuring; split: whether a split comes (its later steps then are added from the figures)"""
    def __init__(self, shaders, library, shader_s, library_s=LIBRARY_S, pack_s=PACK_S):
        self.shaders, self.library, self.shader_s, self.library_s, self.pack_s = max(0, shaders), max(0, library), shader_s, library_s, pack_s
        self.map_rows = self.slow = -1
        self.t0 = None; self.run = 0.0
        self.step, self.stage, self.kind = -1, '', UNKNOWN
        self.phase_start = 0.0
        self.done, self.total = 0, -1
        self.baseline, self.started = True, False
        self.last_units = self.work = 0.0
        self.increments = 0
        self.first_inc, self.last_inc = -1.0, 0.0
        self.work_s, self.last_work_at = 0.0, 0.0
        self.awaiting = True
        self.samples = deque()
        self.disturbed = self.told = self.known = False
        self.finish, self.finish_at = math.nan, 0.0
        self.shown = -1
        self.last_seconds = math.nan
        self.guess = False
        self.carried = False   # the finish time is still on its way from a guess to the measured time (seconds)

    def mix(self, m):
        """The profile map's (shaders, non-vertex shaders) from shader_mix, or None"""
        self.map_rows, self.slow = m if m else (-1, -1)

    def clock(self, now):
        if self.t0 is None: self.t0 = now
        self.run = max(self.run, now - self.t0)

    def start(self, now):
        """The run starts (its clock with it)"""
        self.t0 = None; self.clock(now)

    def enter(self, i, s):
        self.step, self.stage, self.kind = i, s, kind_of(i, s)
        self.phase_start = self.run
        self.done, self.total, self.started = 0, -1, False
        self.baseline = self.awaiting = True
        self.told = False
        self.first_inc, self.last_inc, self.last_work_at = -1.0, self.run, self.run
        self.work, self.increments = 0.0, 0
        self.samples.clear(); self.disturbed = False
        if self.kind == LIBRARY: self.known = True

    def step_to(self, i, now):
        """Step i starts (a "step" line)"""
        self.clock(now)
        s = 'translate' if i == SHADERS else 'main' if i == SPLIT else ''
        self.enter(i, s)

    def progress(self, i, d, t, st, now):
        """A progress line of step i. → True once a part when the translation's total is the whole game's but not the map's count (its vertex shaders cannot be
        weighted)"""
        self.clock(now)
        s = st or ''
        if i != self.step or kind_of(i, s) != self.kind or self.kind == UNKNOWN and s != self.stage: self.enter(i, s)
        self.stage = s
        if self.kind != COUNTED: return False
        if self.started and (t != self.total or d < self.done):   # another total, or the count went back: start over, say nothing meanwhile
            self.disturbed = self.baseline = True
            self.first_inc = -1.0; self.samples.clear(); self.work = 0.0; self.increments = 0; self.told = False
        self.started = True
        self.done, self.total = d, t
        unweighted = False
        if not self.told and i == SHADERS and self.map_rows >= 0 and t != self.map_rows and (t == self.library or t > self.map_rows): self.told = unweighted = True
        u = self.units(d)
        if self.baseline:
            self.baseline = False; self.awaiting = True
            self.last_units, self.last_inc, self.last_work_at = u, self.run, self.run
            return unweighted
        if u <= self.last_units: return unweighted
        if self.awaiting:   # the first step forward after a baseline: the timing starts again here
            self.awaiting = False
            if self.first_inc < 0: self.first_inc = self.run
            if not self.samples: self.samples.append((self.work_s, self.work))
        else:
            self.work_s += min(self.run - self.last_work_at, self.gap_cap())
            self.work += u - self.last_units
            self.increments += 1
            self.samples.append((self.work_s, self.work))
            while len(self.samples) > 2 and self.samples[0][0] < self.work_s - WINDOW_S and self.samples[1][0] <= self.work_s - WINDOW_S: self.samples.popleft()
        self.last_units, self.last_inc, self.last_work_at = u, self.run, self.run
        return unweighted

    def gap_cap(self):
        if len(self.samples) < 3: return GAP_START_S
        return max(GAP_MIN_S, GAP_LINES * (self.samples[-1][0] - self.samples[0][0]) / (len(self.samples) - 1))

    def units(self, d):
        """Work units of a count: in a full build's translation a vertex shader is VERTEX_COST of another"""
        if self.step != SHADERS or self.slow < 0 or self.total != self.map_rows or d <= self.slow: return float(d)
        return self.slow + (d - self.slow) * VERTEX_COST

    def library_left(self): return self.library * self.library_s

    def raw(self):
        """Seconds left in the whole run, unsmoothed; nan for none. Sets guess: whether most of it is still the fixed figure (the translation, before it has a rate
        of its own on this computer)"""
        self.guess = False
        since_inc = self.run - max(self.last_inc, self.phase_start)
        if self.kind == LIBRARY: return timed(self.library_left(), self.run - self.phase_start) + self.pack_s
        if self.kind == PACKING: return timed(self.pack_s, self.run - self.phase_start) if self.known else math.nan
        if self.kind != COUNTED: return math.nan
        if since_inc > STALL_S: return math.nan
        if self.started and (self.total <= 0 or self.done > self.total): return math.nan
        span = 0.0 if self.first_inc < 0 else self.run - self.first_inc
        own = self.increments >= 3 and (span >= MIN_S or span >= MIN_SMALL_S and self.work >= MIN_SMALL_SHARE * self.units(max(self.total, 0)))
        if own:
            a, b = self.samples[0], self.samples[-1]
            rate = (b[1] - a[1]) / max(1.0, b[0] - a[0])
            if rate <= 0: return math.nan
            since = 0.0 if self.awaiting or self.baseline else min(self.run - self.last_work_at, self.gap_cap())
            rest = max(0.0, (self.units(self.total) - self.units(self.done)) / rate - since)
            self.known = True
        elif self.known and not self.disturbed and self.step == SHADERS:   # the translation's start: the figure until its own rate
            t = self.total if self.started else self.shaders
            rest = max(0, t - (self.done if self.started else 0)) * self.shader_s
            self.guess = True
        else: return math.nan
        if self.step == SPLIT: rest += self.shaders * self.shader_s; self.guess = self.shaders > 0
        rest += self.library_left() + self.pack_s
        return math.nan if rest > MAX_S else rest

    def seconds(self, now):
        """Seconds left in the whole run, smoothed; nan when there is no estimate"""
        self.clock(now)
        r = self.raw()
        if math.isnan(r): self.finish = math.nan; return r
        f = self.run + r
        if math.isnan(self.finish): self.finish = f; self.carried = False
        else:
            dt = max(0.0, self.run - self.finish_at)
            self.finish += (f - self.finish) * dt / (SMOOTH_S + dt)
        self.finish_at = self.run
        left = max(0.0, self.finish - self.run)
        # a guess smoothed into the measured time is still called one until it is within GUESS_NEAR of the measured time (once there, never again)
        if self.guess: self.carried = True
        elif self.carried and abs(left - r) <= GUESS_NEAR * r: self.carried = False
        self.guess = self.guess or self.carried
        return left

    def minutes(self, now):
        """The minutes to show (0: less than a minute), -1 for none, kept while the estimate stays near them"""
        s = self.seconds(now)
        self.last_seconds = s
        self.shown = -1 if math.isnan(s) else keep(s, self.shown)
        return self.shown


def rnd(s):
    """Seconds as minutes to show: 0 under a minute, whole minutes under 20 (at least 1), then multiples of 5"""
    if not s >= 60: return 0
    m = min(s, MAX_S) / 60
    if m < 19.5: return max(1, int(m + 0.5))
    return int(m / 5 + 0.5) * 5


def keep(s, last):
    """rnd(s), or last while s is within a tenth of last's rounding step of rounding to it (no flicker at a boundary)"""
    r = rnd(s)
    if last < 0 or r == last: return r
    d = (60 if last < 20 else 300) * 0.1
    return last if rnd(s + d) == last or rnd(s - d) == last else r


def rough(rewrite, split, shaders, library, shader_s):
    """The confirm screen's time before anything is measured: the steps to run at the fixed figures (rewrite and split: whether they run)"""
    return (REWRITE_S if rewrite else 0) + (SPLIT_S if split else 0) + shaders * shader_s + library * LIBRARY_S + PACK_S
