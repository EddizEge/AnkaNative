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


def os_release():
    """/etc/os-release's fields (ID, ID_LIKE, VERSION_ID, PRETTY_NAME, ...), or {} where there is none"""
    try: rel = Path('/etc/os-release').read_text()
    except OSError: return {}
    return {k: v.strip().strip('"\'') for k, v in re.findall(r'^([A-Z_]+)=(.*)$', rel, re.M)}


def system():
    """'windows', 'mac' or the Linux family from /etc/os-release ('debian', 'fedora', 'arch', 'suse', or 'linux' when it is none of them)"""
    if os.name == 'nt': return 'windows'
    if sys.platform == 'darwin': return 'mac'
    rel = os_release()
    ids = (rel.get('ID', '') + ' ' + rel.get('ID_LIKE', '')).split()
    for fam, names in (('debian', ('debian', 'ubuntu')), ('fedora', ('fedora', 'rhel', 'centos')), ('arch', ('arch',)), ('suse', ('suse', 'opensuse'))):
        if any(n in ids for n in names): return fam
    return 'linux'


def in_venv():
    """Whether this Python is an environment's of its own: a venv, or a conda / micromamba environment, activated (CONDA_PREFIX) or not (its conda-meta
    folder: its prefix is its base prefix)"""
    return sys.prefix != sys.base_prefix or bool(os.environ.get('CONDA_PREFIX')) or (Path(sys.prefix) / 'conda-meta').is_dir()


def old_python(): return sys.version_info < (3, 14)


def py_cmd():   # this Python as it is typed
    return f'"{sys.executable}"' if ' ' in sys.executable else sys.executable


# A fix never leaves a newcomer at "command not found": a command that needs a tool the system may lack (Homebrew, micromamba, python3.14, sudo) is shown as
# it is only when that tool is found on the PATH; else the fix says how to get the tool first (its own page and install line), or takes a way that does not
# need it (an installer from the official download page). test_prepare_tui.py Prereq holds every system's fixes to that, with and without each tool.
VENV = '<dump folder>/.venv-anka'
BREW_PREFIXES = ('/opt/homebrew', '/usr/local')   # where Homebrew installs itself: Apple silicon, Intel Macs (docs.brew.sh/Installation)
BREW_GET = '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'   # as https://brew.sh gives it
MAMBA_GET = '"${SHELL}" <(curl -L micro.mamba.pm)'   # as https://mamba.readthedocs.io/en/latest/installation/micromamba-installation.html gives it
MAMBA_CREATE = f'micromamba create -p {VENV} python=3.14 -c conda-forge'
# The distribution's own Python 3.14 (Ubuntu 26.04 and later, Debian testing, Fedora, Arch's python, openSUSE Tumbleweed); Debian's venv module is a
# package of its own. Where the distribution has none, micromamba brings it.
PY_PKG = {'debian': 'apt install python3.14 python3.14-venv', 'fedora': 'dnf install python3.14', 'arch': 'pacman -S python', 'suse': 'zypper install python314'}
PKG_INSTALL = {'debian': 'apt install', 'fedora': 'dnf install', 'arch': 'pacman -S', 'suse': 'zypper install'}
DOTNET_LINUX = 'https://learn.microsoft.com/dotnet/core/install/linux'
DOTNET_NO_PKG = {'debian': 'Debian, Ubuntu before 24.04', 'suse': 'openSUSE Leap'}   # where dotnet-runtime-10.0 is not in the distribution's own source
DOTNET_SCRIPTED = DOTNET_LINUX + '-scripted-manual'   # Microsoft's script, or the runtime unpacked by hand: for a system no package source carries it for


def release():
    """This Linux by its own name: (ID, VERSION_ID as numbers, PRETTY_NAME) from /etc/os-release; () for a version it does not give (Debian testing)"""
    rel = os_release()
    try: ver = tuple(int(x) for x in rel.get('VERSION_ID', '').split('.')) if rel.get('VERSION_ID') else ()
    except ValueError: ver = ()
    return rel.get('ID', ''), ver, rel.get('PRETTY_NAME') or rel.get('NAME') or rel.get('ID', '')


def python_none(rid, ver):
    """Whether this release's own package source has no Python 3.14 (packages.debian.org: forky and sid only; packages.ubuntu.com: 26.04 on; openSUSE
    Leap 16.0 has python313 only)"""
    return rid == 'debian' and (0,) < ver <= (13,) or rid == 'ubuntu' and (0,) < ver < (26, 4) or rid == 'opensuse-leap'


def dotnet_page(rid, ver):
    """Microsoft's page that gives the package source this release needs for dotnet-runtime-10.0, which it does not carry itself (Debian 12 and 13:
    Microsoft's packages; Ubuntu before 24.04: ppa:dotnet/backports; openSUSE Leap and SLES: Microsoft's packages); None for one that carries it or that is
    not known"""
    if rid == 'debian' and (0,) < ver <= (13,): return DOTNET_LINUX + '-debian'
    if rid == 'ubuntu' and (0,) < ver < (24, 4): return DOTNET_LINUX + '-ubuntu-install'
    return {'opensuse-leap': DOTNET_LINUX + '-opensuse', 'sles': DOTNET_LINUX + '-sles'}.get(rid)


def found(tool): return shutil.which(tool) is not None


def root(cmd):
    """A package manager's command as root: with sudo when there is one, else with su (a Debian installed with a root password has no sudo)"""
    return f'sudo {cmd}' if found('sudo') else f"su -c '{cmd}'"


def brew(args):
    """The lines that run `brew args` on the Mac: the command when Homebrew is on the PATH; when it is installed but not on the PATH (its installer's last
    step not done), the line that puts it there first; else how to get Homebrew"""
    if found('brew'): return [f'brew {args}']
    for p in BREW_PREFIXES:
        b = Path(p) / 'bin' / 'brew'
        if b.is_file(): return [T('fix.brew_path', b), f'eval "$({b} shellenv)"', f'brew {args}']
    return [T('fix.brew_get'), BREW_GET, T('fix.brew_then'), f'brew {args}']


def mac_arm():
    """Whether this Mac is Apple silicon, also when this Python runs as Intel code under Rosetta (which says x86_64)"""
    if platform.machine() == 'arm64': return True
    try: return subprocess.run(['sysctl', '-n', 'hw.optional.arm64'], capture_output=True, text=True, timeout=10).stdout.strip() == '1'
    except (OSError, subprocess.SubprocessError): return False


def mamba(sysname):
    """micromamba's line that makes the environment, after how to get micromamba when it is not on the PATH (its installer is fetched with curl, which a
    small Linux install may lack: Debian's has wget only)"""
    if found('micromamba'): return [MAMBA_CREATE]
    curl = [] if found('curl') else [T('fix.curl'), root(PKG_INSTALL[sysname] + ' curl')] if sysname in PKG_INSTALL else [T('fix.curl_other')]
    return curl + [T('fix.mamba_get'), MAMBA_GET, T('fix.mamba_then'), MAMBA_CREATE]


def venv_fix(sysname):
    """The commands that make the packer's environment (android/README.md) and start this interface with it: with python3.14 when it is on the PATH, else
    micromamba when it is (first on the Mac, where a python3.14 may be an Intel Homebrew's, which starts the translator as Intel code), else how to get one"""
    if sysname == 'windows':   # (python.org's default download is the Python install manager, whose py installs 3.14 only when asked: py install)
        get = [] if found('pymanager') and found('py') else [T('fix.python_win')]
        return get + ['py install 3.14', r'py -3.14 -m venv D:\Switch2\.venv-anka',
                rf'D:\Switch2\.venv-anka\Scripts\python {PIP}', r'D:\Switch2\.venv-anka\Scripts\python scripts\prepare.py']
    venv = f'python3.14 -m venv {VENV}'
    if sysname == 'mac':
        head = [MAMBA_CREATE] if found('micromamba') else [venv] if found('python3.14') else [T('fix.python_mac'), venv]
    elif found('python3.14'): head = [venv]
    elif found('micromamba'): head = [MAMBA_CREATE]
    else:
        pkg, (rid, ver, name) = PY_PKG.get(sysname), release()
        if pkg and python_none(rid, ver): head = [T('fix.python_none', name)]   # (no line that is sure to fail first)
        else: head = [T('fix.python_pkg'), root(pkg), T('fix.python_mamba')] if pkg else [T('fix.python_other')]
        head += mamba(sysname)
    return head + [f'{VENV}/bin/python {PIP}', f'{VENV}/bin/python scripts/prepare.py']


def pkg_fix(sysname):
    """Capstone's and lz4's fix: pip in this Python's environment; in a Python older than 3.14 the environment's fix, the same as the Python check's (shown once)"""
    if old_python(): return venv_fix(sysname)
    if in_venv(): return [f'{py_cmd()} {PIP}']
    return [T('fix.not_venv')] + venv_fix(sysname)


def install(sysname, what):
    """The lines that install what ('dotnet', 'spirv' or 'cc', a C compiler: android/README.md's gcc or clang on Linux, Xcode's command line tools on the
    Mac, LLVM-MinGW on Windows) on this system. Debian's is build-essential: its gcc only recommends libc6-dev, the C library's headers the decoder needs.
    .NET 10 comes from the distribution's dotnet-runtime-10.0 (on Arch too: the versioned package stays .NET 10 when the unversioned dotnet-runtime, 10 today,
    takes the next .NET), which Debian, Ubuntu before 24.04, openSUSE Leap and SLES do not carry (the package source Microsoft's page for it names comes
    first, by release: dotnet_page; a system of the family that is not known gets the same as a condition before the line), and openSUSE Tumbleweed has no
    .NET package at all (Microsoft's script or a runtime unpacked by hand); on the Mac from Microsoft's installer (Homebrew's dotnet-sdk cask is .NET 11
    now, whose runtime does not run the translator, built for 10)"""
    if sysname == 'windows': return [T({'dotnet': 'fix.dotnet_win', 'spirv': 'fix.spirv_win', 'cc': 'fix.cc_win'}[what])]
    if sysname == 'mac':   # (Homebrew builds spirv-tools for Apple silicon only: formulae.brew.sh; an Intel Mac is its Tier 3, which builds it from source)
        spirv = ([] if mac_arm() else [T('fix.spirv_intel')]) + brew('install spirv-tools') if what == 'spirv' else []
        return {'dotnet': [T('fix.dotnet_mac')], 'spirv': spirv, 'cc': ['xcode-select --install']}[what]
    if sysname in PKG_INSTALL:
        pkg = {'dotnet': 'dotnet-runtime-10.0', 'spirv': 'spirv-tools', 'cc': 'build-essential' if sysname == 'debian' else 'gcc'}[what]
        out = [root(f'{PKG_INSTALL[sysname]} {pkg}')]
        if what != 'dotnet': return out
        rid, ver, name = release()
        if rid == 'opensuse-tumbleweed': return [T('fix.dotnet_script', name, DOTNET_SCRIPTED)]
        if page := dotnet_page(rid, ver): return [T('fix.dotnet_source_first', name, page)] + out
        known = rid in ('debian', 'ubuntu') and ver or rid in ('opensuse-leap', 'sles')   # (a release dotnet_page knows whether to name a source for)
        return [T('fix.dotnet_source_if', DOTNET_NO_PKG[sysname], DOTNET_LINUX)] + out if sysname in DOTNET_NO_PKG and not known else out
    return [T({'dotnet': 'fix.dotnet_other', 'spirv': 'fix.spirv_other', 'cc': 'fix.cc_other'}[what])]


def check_python(sysname):
    v = sys.version_info
    ok = not old_python()
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
