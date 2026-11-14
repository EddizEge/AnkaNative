# The packer's output as the interface reads it (scripts/pack_android.py's header): "step <name>" as each step starts, the tools' progress lines in their three
# spellings, read with the phone's one pattern (android/app ondevice PackService PROGRESS):
#   progress <done>/<total> <module>     pack_android.py, as each module is rewritten
#   progress: <done>/<total> [stage]     shader_split.py (stage "main" before its scan, none during it)
#   progress <done> <total> <stage>      vk_android_build.py (translate, library, check, done)
# and a failure's "error: <code>\t<argument>..." line before its "[RED] " line (dm/tools/pack_errors.py). Every other "[RED] " line of a run that goes on counts as a
# warning. "done: <pack> (...)" is the packer's last line.
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pack_errors   # noqa: E402

PROGRESS = re.compile(r'^progress:? (\d+)(?: |/)(\d+)(?: (\S+))?')   # PackService.PROGRESS
STEP = re.compile(r'^step ([a-z]+)\s*$')
DONE = re.compile(r'^done: (.+?) \([\d.]+ MiB\)')
STEPS = ('identify', 'rewrite', 'split', 'shaders', 'pack')   # pack_android.py's steps in their order; their indexes are the phone's (Eta.java SPLIT 2, SHADERS 3, PACK 4)


class Event:
    """One line read: kind 'step' (name), 'progress' (done, total, stage: '' for none), 'error' (code, args), 'red' (text), 'done' (path) or 'line'"""
    def __init__(self, kind, line, **kv):
        self.kind, self.line = kind, line
        self.name = self.stage = self.text = self.path = self.code = ''
        self.done = self.total = 0
        self.args = []
        self.__dict__.update(kv)

    def __repr__(self): return f'Event({self.kind}, {self.line!r})'


def parse(line):
    """The Event of one output line (its line end dropped)"""
    s = line.rstrip('\r\n')
    if m := STEP.match(s): return Event('step', s, name=m.group(1))
    if m := PROGRESS.match(s): return Event('progress', s, done=int(m.group(1)), total=int(m.group(2)), stage=m.group(3) or '')
    if (e := pack_errors.parse(s)) is not None: return Event('error', s, code=e[0], args=e[1:])
    if s.startswith('[RED] '): return Event('red', s, text=s[6:])
    if m := DONE.match(s): return Event('done', s, path=m.group(1))
    return Event('line', s)


class State:
    """The run as the lines so far tell it: the step (an index of STEPS, -1 before the first), each step's last progress, the [RED] lines (warnings while the run
    goes on; the last one is a failure's sentence), the finished pack's path, the last 200 lines"""
    def __init__(self):
        self.step, self.started = -1, set()
        self.prog = {}   # step index → (done, total, stage)
        self.warnings, self.reds = 0, []
        self.red = None
        self.pack = None
        self.lines = []

    def feed(self, line):
        e = parse(line)
        self.lines.append(e.line); del self.lines[:-200]
        if e.kind == 'step' and e.name in STEPS:
            self.step = STEPS.index(e.name); self.started.add(self.step)
        elif e.kind == 'progress' and self.step >= 0: self.prog[self.step] = (e.done, e.total, e.stage)
        elif e.kind == 'red':
            self.warnings += 1; self.reds.append(e.text); self.red = e.text
        elif e.kind == 'done': self.pack = e.path
        return e

    def failure(self):
        """(the code and its arguments, or None; the [RED] sentence, or None) of a failed run: its last [RED] line and the error: line right before it, as the
        phone pairs them (JobErrors.Watch)"""
        for i in range(len(self.lines) - 1, -1, -1):
            if self.lines[i].startswith('[RED] '):
                prev = pack_errors.parse(self.lines[i - 1]) if i else None
                return ((prev[0], prev[1:]) if prev else None), self.lines[i][6:]
        return None, None
