# The packer's error codes (docs/i18n.md "Errors of the packer"): for a failure the user can fix, scripts/pack_android.py and scripts/pack_dump.py print, just before the
# failure's [RED] line, one line
#   error: <code>\t<argument>\t<argument>...
# that the phone's launcher reads (android/app ondevice JobErrors.java: JobErrors.parse, JobErrors.message) to show the failure in the user's language; the [RED] line
# stays the English sentence for logs and job.json's `error`. A code is lower-case words joined by `_`; each argument is a string (a path, a title ID, a count),
# never a sentence, and holds no tab, CR or newline (line() replaces them by spaces; an empty argument is written as `-`).
# The codes, with their arguments (the same list as JobErrors.CODES / ARGS, which dm/tools/tests/test_pack_errors.py compares):
#   dump_incomplete       <folder>                 the dump lacks its code (exefs with main / main.npdm) or its RomFS
#   module_missing        <exefs folder> <names>   a code module of the profile is not in the dump (names: space-separated)
#   version_missing       <version>                the update version asked for is not in the dump
#   wrong_title           <folder> <found> <base>  a version's program ID is not the base game's (16 hex digits each)
#   not_base_title        <where> <title ID>       the dump's program ID is not a base game's title ID
#   no_profile            <title ID>               this tree (the app) has no profile for the game
#   shaders_other_build   <missing> <total>        the dump's shaders lack some of the profile's shader_map.tsv: another game build, or incomplete
#   unreadable_file       <path>                   a file of the dump cannot be read or is not what its name says (an NPDM, an NSO, a NUP file)
#   no_space              <path>                   no space left on the device that holds the path (the tool's name when its output names no path)
#   out_of_memory         <tool>                   a tool stopped for want of memory: a MemoryError in it, or (a program the packer started) killed with SIGKILL, the
#                                                  signal of Linux's and Android's out-of-memory killer (patch_nso.py, shader_split.py, vk_android_build.py, pack_android.py)
#   translator_crashed                             the shader translator crashed. Not printed yet: on the phone vk_android_build.py runs as its own step (PackService),
#                                                  not under pack_android.py, and its exit status does not tell a translator crash from its other failures; the code
#                                                  and its text are ready for when vk_android_build.py prints it (a change to a DMSH_VK_CHAIN input)
# This module has no effect on any tool's output but the error: lines; it is not an input of the shader chain (DMSH_CHAIN, DMSH_VK_CHAIN) or of the rewrite key.
import errno, re, sys

CODES = {
    'dump_incomplete': 1,
    'module_missing': 2,
    'version_missing': 1,
    'wrong_title': 3,
    'not_base_title': 2,
    'no_profile': 1,
    'shaders_other_build': 2,
    'unreadable_file': 1,
    'no_space': 1,
    'out_of_memory': 1,
    'translator_crashed': 0,
}
CODE = re.compile(r'^[a-z][a-z0-9]*(_[a-z0-9]+)*$')
PREFIX = 'error: '


class Coded(Exception):
    """An error whose message is the English sentence of its [RED] line, with an optional code and its arguments for the error: line before it"""
    def __init__(self, msg, code=None, *args):
        super().__init__(msg)
        if code is not None: check(code, args)
        self.code, self.code_args = code, tuple(str(a) for a in args)


def check(code, args):
    if code not in CODES: raise ValueError(f'unknown error code {code!r} (dm/tools/pack_errors.py CODES)')
    if len(args) != CODES[code]: raise ValueError(f'error code {code} takes {CODES[code]} arguments, not {len(args)}')


def clean(a):   # an argument as the line carries it: no tab, CR or newline, never empty
    return re.sub(r'[\t\r\n]', ' ', str(a)) or '-'


def line(code, *args):
    """The error: line of a code and its arguments (no newline)"""
    check(code, args)
    return PREFIX + '\t'.join([code] + [clean(a) for a in args])


def parse(s):
    """[code, arg...] of an error: line (a trailing newline is dropped), else None; the same rule as JobErrors.parse"""
    s = s.rstrip('\r\n')
    if not s.startswith(PREFIX): return None
    p = s[len(PREFIX):].split('\t')
    return p if CODE.match(p[0]) else None


def emit(code, *args):
    """Prints the error: line to stderr (where sys.exit puts the [RED] line that follows), stdout flushed first so the log keeps the order"""
    ln = line(code, *args)
    sys.stdout.flush()
    print(ln, file=sys.stderr, flush=True)


def fail(e):
    """Exits with e's [RED] line, its error: line first when e carries a code"""
    if getattr(e, 'code', None): emit(e.code, *e.code_args)
    sys.exit(f'[RED] {e}')


def of_os_error(e):
    """(code, args) for an OSError the user can act on, else None: no space left (ENOSPC, EDQUOT), a read error of the device (EIO) on a named file"""
    if e.errno in (errno.ENOSPC, getattr(errno, 'EDQUOT', errno.ENOSPC)): return 'no_space', (e.filename if e.filename is not None else '-',)
    if e.errno == errno.EIO and e.filename is not None: return 'unreadable_file', (e.filename,)
    return None


def of_log(text, tool):
    """(code, args) for a failed tool's output, from what Python prints for it, else None: a MemoryError, or an OSError of no space left (`[Errno 28] No space
    left on device: '<path>'`, ENOSPC's number on Linux, Android and the Mac; the path, else the tool)"""
    if re.search(r'^MemoryError\b', text, re.M): return 'out_of_memory', (tool,)
    if m := re.search(r"\[Errno 28\] No space left on device(?:: '([^'\n]+)')?|No space left on device", text): return 'no_space', (m.group(1) or tool,)
    return None


def of_exit(rc, tool):
    """(code, args) for a program's exit status, else None: killed with SIGKILL (-9 from subprocess), as the out-of-memory killer does"""
    return ('out_of_memory', (tool,)) if rc == -9 else None


def flat(c):
    """A (code, args) pair of of_os_error / of_log / of_exit as Coded's trailing arguments: () for None"""
    return () if c is None else (c[0], *c[1])


def fail_os(e):
    """For an OSError a main() caught: its error: and [RED] lines and exit 1 when of_os_error codes it; else returns (the caller re-raises: a traceback, as before)"""
    c = of_os_error(e)
    if c is None: return
    emit(c[0], *c[1])
    sys.exit(f'[RED] {e}')


def fail_memory(tool):
    """For a MemoryError a main() caught: out_of_memory's error: line, a [RED] line, exit 1"""
    emit('out_of_memory', tool)
    sys.exit(f'[RED] {tool}: out of memory')
