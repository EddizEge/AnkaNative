# What the packer needs on a computer (android/README.md, "The environment"), each found or missing with the fix for this system: the Python (3.14, which has the
# zstd the shader tools read), Capstone 5, lz4 and zbic (the ZBIC decoder of system 22.0.0+ games, as dm/tools/nso.py imports it), the .NET 10 runtime with the
# committed translator really starting and being the one dmsh_chain.lock names (tool_lock.py), spirv-val, and the source tree's line ends (the chain version this
# copy computes is the committed one: a checkout or zip with CRLF line ends gives another, and the libraries it would build would not load).
# Each check runs in this Python (the one that runs pack_android.py, which starts the tools with it) and never takes more than the translator's start.
import os, re, shutil, subprocess, sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
for d in ('dm/gfx/tools', 'scripts', 'dm/games', 'dm/tools'): sys.path.insert(0, str(REPO / d))
from prepare_tui.texts import T   # noqa: E402
PIP = '-m pip install "capstone>=5,<6" lz4 zbic'


class Check:
    def __init__(self, name, ok, found, fix=()):
        self.name, self.ok, self.found, self.fix = name, ok, found, list(fix)   # fix: lines, commands as they are typed on this system

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
    """The command that installs what ('dotnet' or 'spirv') on this system"""
    if sysname == 'windows':
        return [T('fix.dotnet_win' if what == 'dotnet' else 'fix.spirv_win')]
    if sysname == 'mac': return {'dotnet': ['brew install --cask dotnet-sdk'], 'spirv': ['brew install spirv-tools']}[what]
    pkgs = {'debian': ('sudo apt install dotnet-runtime-10.0', 'sudo apt install spirv-tools'), 'fedora': ('sudo dnf install dotnet-runtime-10.0', 'sudo dnf install spirv-tools'),
            'arch': ('sudo pacman -S dotnet-runtime', 'sudo pacman -S spirv-tools'), 'suse': ('sudo zypper install dotnet-runtime-10.0', 'sudo zypper install spirv-tools')}
    if sysname in pkgs: return [pkgs[sysname][0 if what == 'dotnet' else 1]]
    return [T('fix.dotnet_other' if what == 'dotnet' else 'fix.spirv_other')]


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


def zbic_version():
    import zbic   # pyright: ignore[reportMissingImports]  # as dm/tools/nso.py imports it
    if not callable(getattr(zbic, 'decompress', None)): raise ValueError('zbic has no decompress()')
    return getattr(zbic, '__version__', 'found')


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
           check_module(s, 'zbic', T('check.zbic'), zbic_version), check_dotnet(s), check_spirv(s), check_tree()]
    return out
