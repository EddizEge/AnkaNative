# What the packer needs on a computer (android/README.md, "The environment"), each found or missing with the fix for this system: the Python (3.14, which has the
# zstd the shader tools read), Capstone 5, lz4, the ZBIC decoder of system 22.0.0+ games (libzbicdec, which dm/tools/nso.py builds with this computer's C compiler
# the first time and loads by ctypes: checked by building it with nso.py's own function and decoding a small stream with it), the .NET 10 runtime with the committed translator really
# starting and being the one dmsh_chain.lock names (tool_lock.py), spirv-val, and the source tree's line ends (the chain version this copy computes is the
# committed one: a checkout or zip with CRLF line ends gives another, and the libraries it would build would not load).
# Each check runs in this Python (the one that runs pack_android.py, which starts the tools with it) and never takes more than the translator's start or the
# decoder's first build (a few seconds; once built, it is loaded from its folder at once).
import base64, hashlib, os, platform, re, shutil, struct, subprocess, sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
for d in ('dm/gfx/tools', 'scripts', 'dm/games', 'dm/tools'): sys.path.insert(0, str(REPO / d))
from prepare_tui.texts import T   # noqa: E402
import nso   # noqa: E402  (dm/tools/nso.py: the ZBIC decoder's build and load)
PIP = '-m pip install "capstone>=5,<6" lz4'


class Check:
    def __init__(self, name, ok, found, fix=(), detail=()):
        self.name, self.ok, self.found, self.fix = name, ok, found, list(fix)   # fix: lines, commands as they are typed on this system
        self.detail = list(detail)   # lines a tool printed (a compiler's last lines), shown under what was found

    def __repr__(self): return f'Check({self.name}, {self.ok}, {self.found!r})'


def system():
    """'windows', 'mac' or the Linux family from /etc/os-release ('debian', 'fedora', 'arch', 'suse', or 'linux' when it is none of them)"""
    if os.name == 'nt': return 'windows'
    if sys.platform == 'darwin': return 'mac'
    try: rel = Path('/etc/os-release').read_text()
    except OSError: return 'linux'
    ids = ' '.join(re.findall(r'^(?:ID|ID_LIKE)="?([^"\n]*)"?', rel, re.M)).split()
    for fam, names in (('debian', ('debian', 'ubuntu')), ('fedora', ('fedora', 'rhel', 'centos')), ('arch', ('arch',)), ('suse', ('suse', 'opensuse'))):
        if any(n in ids for n in names): return fam
    return 'linux'


def in_venv(): return sys.prefix != sys.base_prefix or bool(os.environ.get('CONDA_PREFIX'))


def py_cmd():   # this Python as it is typed
    return f'"{sys.executable}"' if ' ' in sys.executable else sys.executable


def venv_fix(sysname):
    """The commands that make the packer's environment (android/README.md) and start this interface with it"""
    if sysname == 'windows':
        return [T('fix.python_win'), r'py -3.14 -m venv D:\Switch2\.venv-anka',
                rf'D:\Switch2\.venv-anka\Scripts\python {PIP}', r'D:\Switch2\.venv-anka\Scripts\python scripts\prepare.py']
    if sysname == 'mac':
        return ['micromamba create -p <dump folder>/.venv-anka python=3.14 -c conda-forge', f'<dump folder>/.venv-anka/bin/python {PIP}',
                '<dump folder>/.venv-anka/bin/python scripts/prepare.py']
    return ['python3.14 -m venv <dump folder>/.venv-anka   ' + T('fix.python_unix'), f'<dump folder>/.venv-anka/bin/python {PIP}',
            '<dump folder>/.venv-anka/bin/python scripts/prepare.py']


def pkg_fix(sysname):
    if in_venv(): return [f'{py_cmd()} {PIP}']
    return [T('fix.not_venv')] + venv_fix(sysname)


def install(sysname, what):
    """The command that installs what ('dotnet', 'spirv' or 'cc', a C compiler: android/README.md's gcc or clang on Linux, Xcode's command line tools on the
    Mac, LLVM-MinGW on Windows) on this system. Debian's is build-essential: its gcc only recommends libc6-dev, the C library's headers the decoder needs"""
    if sysname == 'windows': return [T({'dotnet': 'fix.dotnet_win', 'spirv': 'fix.spirv_win', 'cc': 'fix.cc_win'}[what])]
    if sysname == 'mac': return {'dotnet': ['brew install --cask dotnet-sdk'], 'spirv': ['brew install spirv-tools'], 'cc': ['xcode-select --install']}[what]
    pkgs = {'debian': ('sudo apt install dotnet-runtime-10.0', 'sudo apt install spirv-tools', 'sudo apt install build-essential'),
            'fedora': ('sudo dnf install dotnet-runtime-10.0', 'sudo dnf install spirv-tools', 'sudo dnf install gcc'),
            'arch': ('sudo pacman -S dotnet-runtime', 'sudo pacman -S spirv-tools', 'sudo pacman -S gcc'),
            'suse': ('sudo zypper install dotnet-runtime-10.0', 'sudo zypper install spirv-tools', 'sudo zypper install gcc')}
    if sysname in pkgs: return [pkgs[sysname][('dotnet', 'spirv', 'cc').index(what)]]
    return [T({'dotnet': 'fix.dotnet_other', 'spirv': 'fix.spirv_other', 'cc': 'fix.cc_other'}[what])]


def check_python(sysname):
    v = sys.version_info
    ok = v >= (3, 14)
    return Check(T('check.python'), ok, f'{v.major}.{v.minor}.{v.micro} ({sys.executable})', [] if ok else venv_fix(sysname))


def check_module(sysname, name, label, test):
    try:
        found = test()
        return Check(label, True, found)
    except ImportError as e: return Check(label, False, T('check.missing', e.name or name), pkg_fix(sysname))
    except ValueError as e: return Check(label, False, str(e), pkg_fix(sysname))


def capstone_version():
    import capstone   # pyright: ignore[reportMissingImports]
    if capstone.cs_version()[0] != 5: raise ValueError(f'Capstone {capstone.__version__}: the rewriter needs Capstone 5')
    return capstone.__version__


def lz4_version():
    import lz4.block   # pyright: ignore[reportMissingImports]  # noqa: F401  (the NSO segments: nso.py)
    import lz4
    return getattr(lz4, '__version__', 'found')


def zbic_libs():   # the decoder libraries built in this copy of the source so far (nso.zbic_build's folder)
    root = Path(nso.ZBIC_SRC) / 'build'
    return {str(p) for p in root.glob('*/*') if p.is_file()} if root.is_dir() else set()


# A ZBIC stream the check decodes with the library it loaded: 600 bytes of test_nso.py's synthetic(600, 7), compressed by zstd at level 19 and turned into ZBIC
# by test_nso.zstd_to_zbic (four FSE tables in ZBIC's coding, a checksum). A library that loads but does not decode ZBIC fails the check, as the rewrite would.
ZBIC_SAMPLE = base64.b64decode(
    'WkJJQ2RYAUUHAOKFFBwJAE/f4gQAjL9BDAOwQteWdbFxU7QPI6cwSGIE3AfSlfuv//9Z/ru7cusPDuwkAmTJ/0rVjiVuFVxhqeJrSMIV2eA+EK5Wph8PJCZL7hQJUwFEqAwAANNuTfh06rgx1QoLBzrF'
    'DvhvADCNTyAMFEFRq6b4PzBNjfkZayYoydHk5+28tsMtX7vhXJm8U7zfInCRnPcIJXGL9uL2FWGoV2MlamlzDw5OjI9EhIGfq4bE8AW5UKT9Ef9Dt1vUlZIhgSR4cgbqOLYHp1Njp9np4ipQ0ryjEc+i'
    'EynCz3ERwLcmlyWXIwG0CZPY')
ZBIC_SAMPLE_SIZE, ZBIC_SAMPLE_SHA256 = 600, 'ea54c9ec2a43bb39962cea2ec46c11fbf5ec59e0830907f302f6e87abb621d14'


def zbic_decodes(decode):
    """Raises nso.Error unless decode turns ZBIC_SAMPLE into its 600 bytes"""
    out, err = decode(ZBIC_SAMPLE, ZBIC_SAMPLE_SIZE)
    if err: raise nso.Error(T('check.zbic_no_decode', f'{err[1]}, zstd error {err[0]}'))
    if hashlib.sha256(bytes(out)).hexdigest() != ZBIC_SAMPLE_SHA256: raise nso.Error(T('check.zbic_no_decode', T('check.zbic_wrong_bytes')))


ZBIC_TRY = """import sys; sys.path.insert(0, sys.argv[1]); import nso; from prepare_tui import checks
try: checks.zbic_decodes(nso.zbic_decoder())
except (ValueError, OSError, AttributeError) as e: print(e); sys.exit(1)"""


def zbic_try(lib):
    """'' when the library lib (a path, or the bare name DM_ZBIC_LIB may give) loads and decodes ZBIC_SAMPLE as nso.zbic_decoder loads it for the rewriter, else
    why not. In a Python of its own, as the packer's: a library that crashes when it is loaded ends only that one, and the file there now is what is loaded (the
    dynamic linker would hand this process a library it loaded earlier from the same path again, after a build replaced it)"""
    env = dict(os.environ, **{nso.ZBIC_LIB_ENV: str(lib)})
    try: r = subprocess.run([sys.executable, '-c', ZBIC_TRY, str(REPO / 'dm/tools')], env=env, capture_output=True, text=True, errors='replace', timeout=60)
    except (OSError, subprocess.SubprocessError) as e: return T('check.zbic_no_try', e)
    if r.returncode == 0: return ''
    out = (r.stdout.strip() or r.stderr.strip()).splitlines()
    return out[-1] if r.returncode == 1 and out else T('check.zbic_crash', r.returncode)


def check_zbic(sysname):
    """The ZBIC decoder as the rewriter and the shader split get it (nso.zbic_decoder): the library DM_ZBIC_LIB names; else this computer's build, made now by
    nso.zbic_build with the C compiler it finds ($CC, else cc, clang or gcc) when there is none yet. A build already there takes no compiler. Either way the
    library is loaded and decodes a small ZBIC stream (zbic_try). A build in the folder that does not (made by a compiler for another CPU or word size, or one
    that wrote no real library) is removed, since nso.zbic_build takes any file there as done: one an earlier compiler made is built again at once with the
    compiler found now. Missing: no compiler (the system's install line), a $CC that cannot be read, a build that fails (the compiler's last lines), a library
    that does not load or decode, a build folder that cannot be written"""
    name, env = T('check.zbic'), os.environ.get(nso.ZBIC_LIB_ENV)
    if env:
        why = zbic_try(env)
        if why: return Check(name, False, why, [T('fix.zbic_env', nso.ZBIC_LIB_ENV)])
        return Check(name, True, T('check.zbic_env', env, nso.ZBIC_LIB_ENV))
    try:
        while True:   # (twice at most: an earlier build that is no good here is built again once)
            before = zbic_libs()
            lib = nso.zbic_build()
            cached = lib in before
            why = zbic_try(lib).replace(f'; {nso.ZBIC_LIB_ENV} names the library', '')   # (nso's words for a library named by it: zbic_try names it so)
            if not why: return Check(name, True, T('check.zbic_cached' if cached else 'check.zbic_built', lib))
            shutil.rmtree(Path(lib).parent)   # so that the next build is a real one
            if not cached:
                return Check(name, False, T('check.zbic_unusable', why), [T('fix.cc_target', platform.machine() or '?', struct.calcsize('P') * 8)]
                             + install(sysname, 'cc') + [T('fix.cc_or', nso.ZBIC_LIB_ENV)])
    except OSError as e: return Check(name, False, str(e), [T('fix.zbic_write', Path(nso.ZBIC_SRC) / 'build', nso.ZBIC_LIB_ENV)])
    except ValueError as e:   # nso.Error, or shlex's on a $CC with a quote that is not closed
        why = str(e)
        if not isinstance(e, nso.Error): return Check(name, False, T('check.cc_unreadable', os.environ.get('CC', ''), why), [T('fix.cc_quotes')])
        if 'no C compiler' in why:   # nso.zbic_build's words when it finds none (test_prepare_tui.py ZbicCheck.test_missing holds them to it)
            return Check(name, False, T('check.no_cc'), [T('fix.cc')] + install(sysname, 'cc') + [T('fix.cc_or', nso.ZBIC_LIB_ENV)])
        found, lines = why, ''
        if m := re.match(r'(libzbicdec does not build with .* \(exit code -?\d+\)): (.*)$', why, re.S): found, lines = m.groups()   # nso's words, then the compiler's
        return Check(name, False, found, [T('fix.cc_broken')] + install(sysname, 'cc') + [T('fix.cc_or', nso.ZBIC_LIB_ENV)],   # last lines joined by ' | '
                     lines.split(' | ') if lines else ())


def dotnet_runtimes():
    """The .NET runtimes `dotnet --list-runtimes` lists (Microsoft.NETCore.App versions), or None when there is no dotnet"""
    exe = shutil.which('dotnet')
    if not exe: return None
    try: r = subprocess.run([exe, '--list-runtimes'], capture_output=True, text=True, errors='replace', timeout=30)
    except (OSError, subprocess.SubprocessError): return None
    return re.findall(r'^Microsoft\.NETCore\.App (\S+)', r.stdout, re.M)


def check_dotnet(sysname):
    """The .NET 10 runtime and the committed translator (the launcher pack_android.py starts, XLATE) starting and being the lock's"""
    import tool_lock, dmsh_chain, pack_android   # pyright: ignore[reportMissingImports]  # scripts/, on sys.path above
    rts = dotnet_runtimes()
    env = os.environ.get('XLATE')
    try: x = env or pack_android.xlate_program()
    except pack_android.Error as e: return Check(T('check.dotnet'), False, str(e), install(sysname, 'dotnet'))
    old = os.environ.get('XLATE')
    if x is not None: os.environ['XLATE'] = str(x)
    try: emb = tool_lock.xlate_embedded()
    finally:
        if old is None: os.environ.pop('XLATE', None)
        else: os.environ['XLATE'] = old
    want = dict(l.split('\t', 1) for l in Path(dmsh_chain.LOCK).read_text().splitlines() if '\t' in l and not l.startswith('#')).get('xlate_source')
    rt = ('.NET ' + (', '.join(v for v in rts if v.startswith('10.')) or '10 -')) if rts is not None else T('check.no_dotnet')
    if emb.startswith(tool_lock.NO_RUN): return Check(T('check.dotnet'), False, T('check.no_run', rt, emb), install(sysname, 'dotnet'))
    if emb != want: return Check(T('check.dotnet'), False, T('check.other_xlate', rt, emb, want), [T('fix.xlate')])
    # the translator starts: its launcher found the runtime (a dotnet that is not on the PATH, in its usual folder) when `dotnet` itself is not on the PATH
    return Check(T('check.dotnet'), True, T('check.runs', rt, emb) if rts is not None else T('check.runs_found', emb))


def check_spirv(sysname):
    exe = os.environ.get('SPIRV_VAL') or shutil.which('spirv-val')
    if not exe: return Check(T('check.spirv'), False, T('check.no_path'), install(sysname, 'spirv'))
    try: r = subprocess.run([exe, '--version'], capture_output=True, text=True, errors='replace', timeout=30)
    except (OSError, subprocess.SubprocessError) as e: return Check(T('check.spirv'), False, f'{exe}: {e}', install(sysname, 'spirv'))
    first = (r.stdout.strip().splitlines() or ['?'])[0]
    return Check(T('check.spirv'), r.returncode == 0, f'{first} ({exe})', [] if r.returncode == 0 else install(sysname, 'spirv'))


def check_tree():
    """The shader chain version this copy of the source computes is the committed one (line ends: android/README.md)"""
    import dmsh_chain
    m = re.search(r'#define DMSH_CHAIN "([0-9a-f]+)"', (REPO / 'dm/gfx/nvn/dmsh_chain.h').read_text())
    want, got = m.group(1) if m else '?', dmsh_chain.version()
    ok = got == want
    return Check(T('check.tree'), ok, T('check.tree_ok', got) if ok else T('check.tree_bad', got, want), [] if ok else [T('fix.tree')])


def run_all(sysname=None):
    s = sysname or system()
    out = [check_python(s), check_module(s, 'capstone', T('check.capstone'), capstone_version), check_module(s, 'lz4', T('check.lz4'), lz4_version),
           check_zbic(s), check_dotnet(s), check_spirv(s), check_tree()]
    return out
