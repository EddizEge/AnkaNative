# The file browser's logic, apart from its drawing (app.py): a folder's entries (folders, .nup files, and the dump folders pack_android.py takes, marked as games),
# the filter typed over them, the cursor, the places to jump to (the home folder, recently used folders, the drives on Windows, mounted volumes elsewhere), and
# the state file that keeps the last folder and the recent ones between runs.
# The state file is the user's, never the repository's: <config>/anka-native/prepare.json, <config> being %APPDATA% on Windows, ~/Library/Application Support on
# the Mac and $XDG_CONFIG_HOME (else ~/.config) elsewhere; ANKA_PREPARE_STATE names another file (the tests). It is written to a temporary name and renamed.
import json, os, string, sys
from pathlib import Path

RECENT = 6   # recent folders kept


def state_path():
    if os.environ.get('ANKA_PREPARE_STATE'): return Path(os.environ['ANKA_PREPARE_STATE'])
    if os.name == 'nt': base = Path(os.environ.get('APPDATA') or Path.home() / 'AppData' / 'Roaming')
    elif sys.platform == 'darwin': base = Path.home() / 'Library' / 'Application Support'
    else: base = Path(os.environ.get('XDG_CONFIG_HOME') or Path.home() / '.config')
    return base / 'anka-native' / 'prepare.json'


def load_state(path=None):
    """The state file's contents ({} when there is none); (state, problem) with problem '' or what was wrong with the file"""
    p = path or state_path()
    try: d = json.loads(p.read_text(encoding='utf-8'))
    except FileNotFoundError: return {}, ''
    except (OSError, ValueError) as e: return {}, f'{p}: {e}'
    return (d, '') if isinstance(d, dict) else ({}, f'{p}: not a JSON object')


def save_state(state, path=None):
    """Writes the state; → '' or the reason it could not be written (the interface says so and goes on)"""
    p = path or state_path()
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + '.tmp'); tmp.write_text(json.dumps(state, indent=1), encoding='utf-8'); os.replace(tmp, p)
    except OSError as e: return f'{p}: {e}'
    return ''


def remember(state, folder):
    """The state with folder as the last one used and first among the recent ones"""
    f = str(folder)
    rec = [f] + [r for r in state.get('recent', []) if r != f]
    return dict(state, last=f, recent=rec[:RECENT])


def is_dump(p):
    """A folder pack_android.py takes as a game (pack_dump.is_dump's layout: a main.npdm in base/exefs or an update's exefs, and a RomFS), checked by its names only"""
    try:
        ups = [u for u in (p / 'updates').iterdir() if u.is_dir() and u.name.isdigit()] if (p / 'updates').is_dir() else []
        code = (p / 'base/exefs/main.npdm').is_file() or any((u / 'exefs/main.npdm').is_file() for u in ups)
        return code and ((p / 'base/romfs').is_dir() or any((u / 'romfs').is_dir() for u in ups))
    except OSError: return False   # a folder that cannot be read is no game (it shows as a folder, and entering it says why)


class Entry:
    def __init__(self, path, kind, size=0):
        self.path, self.kind, self.size = path, kind, size   # kind: 'up', 'dir', 'game' (a dump folder), 'nup'
        self.name = '..' if kind == 'up' else path.name

    def __repr__(self): return f'Entry({self.kind}, {self.name})'


def list_folder(folder, hidden=False):
    """The entries of a folder: '..' (unless it is a root), its folders and dump folders, its .nup files; sorted by name, folders first. OSError when it cannot be read"""
    out = []
    if folder.parent != folder: out.append(Entry(folder.parent, 'up'))
    items = []
    with os.scandir(folder) as it:
        for d in it:
            if not hidden and d.name.startswith('.'): continue
            try:
                if d.is_dir():
                    p = Path(d.path)
                    items.append(Entry(p, 'game' if is_dump(p) else 'dir'))
                elif d.name.lower().endswith('.nup') and d.is_file(): items.append(Entry(Path(d.path), 'nup', d.stat().st_size))
            except OSError: continue   # an entry that vanished or cannot be read while listing: not shown
    items.sort(key=lambda e: (e.kind == 'nup', e.name.lower()))
    return out + items


def drives():
    """The drives on Windows (C:\\ ...); none elsewhere"""
    if os.name != 'nt': return []
    import ctypes
    mask = ctypes.windll.kernel32.GetLogicalDrives()   # pyright: ignore[reportAttributeAccessIssue]
    return [Path(f'{c}:\\') for i, c in enumerate(string.ascii_uppercase) if mask >> i & 1]


def volumes():
    """Mounted volumes outside Windows: /Volumes/* on the Mac, /run/media/<user>/*, /media/<user>/* and /mnt/* on Linux (where removable and second drives appear)"""
    user = os.environ.get('USER') or os.environ.get('LOGNAME') or ''
    roots = [Path('/Volumes')] if sys.platform == 'darwin' else [Path('/run/media') / user, Path('/media') / user, Path('/mnt')] if user else [Path('/mnt')]
    out = []
    for r in roots:
        try: out += sorted(p for p in r.iterdir() if p.is_dir() and not p.name.startswith('.'))
        except OSError: continue   # no such folder on this system
    return out


def places(state):
    """[(label, path)] to jump to: home, the recent folders that still exist, the drives or volumes"""
    out = [('Home', Path.home())]
    for r in state.get('recent', []):
        if Path(r).is_dir() and all(Path(r) != p for _, p in out): out.append((Path(r).name or r, Path(r)))
    for d in drives() + volumes():
        if all(d != p for _, p in out): out.append((str(d) if os.name == 'nt' else d.name, d))
    return out


class Browser:
    """A folder, its entries, the filter typed over them and the cursor on the filtered list"""
    def __init__(self, folder):
        self.error = ''
        self.entries, self.filter, self.cur, self.top = [], '', 0, 0
        self.folder = Path(folder)
        self.go(self.folder)

    def go(self, folder, select=None):
        """Into folder (its entries listed; the cursor on select when given). → '' or why it cannot be read (the browser then stays where it was)"""
        folder = Path(folder)
        try: entries = list_folder(folder)
        except OSError as e: self.error = f'{folder}: {e.strerror or e}'; return self.error
        self.folder, self.entries, self.filter, self.cur, self.top, self.error = folder, entries, '', 0, 0, ''
        if select is not None:
            for i, e in enumerate(self.shown()):
                if e.path == select and e.kind != 'up': self.cur = i
        return ''

    def shown(self):
        """The entries the filter keeps: those whose name holds the typed text, case ignored ('..' only with no filter)"""
        f = self.filter.lower()
        return [e for e in self.entries if (e.kind == 'up' and not f) or (e.kind != 'up' and f in e.name.lower())]

    def type(self, c):
        self.filter += c; self.cur = 0; self.top = 0

    def erase(self):
        """Backspace: the filter's last character; with no filter, the folder above. → whether something changed"""
        if self.filter: self.filter = self.filter[:-1]; self.cur = 0; self.top = 0; return True
        return self.up()

    def up(self):
        if self.folder.parent == self.folder: return False
        return not self.go(self.folder.parent, select=self.folder)

    def move(self, d, page=10):
        n = len(self.shown())
        if not n: self.cur = 0; return
        if d == 'up': self.cur = max(0, self.cur - 1)
        elif d == 'down': self.cur = min(n - 1, self.cur + 1)
        elif d == 'home': self.cur = 0
        elif d == 'end': self.cur = n - 1
        elif d == 'pgup': self.cur = max(0, self.cur - page)
        elif d == 'pgdn': self.cur = min(n - 1, self.cur + page)

    def current(self):
        s = self.shown()
        return s[self.cur] if 0 <= self.cur < len(s) else None

    def window(self, rows):
        """The entries to draw in rows lines, the cursor kept in view: (first index, entries)"""
        s = self.shown()
        if self.cur < self.top: self.top = self.cur
        if self.cur >= self.top + rows: self.top = self.cur - rows + 1
        self.top = max(0, min(self.top, max(0, len(s) - rows)))
        return self.top, s[self.top:self.top + rows]

    def enter(self):
        """Enter on the cursor's entry: a folder is opened; a game (a .nup or a dump folder) is returned as the pick. → (path picked or None)"""
        e = self.current()
        if e is None: return None
        if e.kind in ('up', 'dir'):
            if e.kind == 'up': self.up()
            else: self.go(e.path)
            return None
        return e.path


def complete(text):
    """Tab in the path field: text completed to the longest common start of the names it can continue to (folders get a separator). → the new text"""
    p = Path(os.path.expanduser(text))
    base, part = (p, '') if text.endswith(('/', os.sep)) else (p.parent, p.name)
    try: names = [n for n in os.listdir(base) if n.startswith(part) and not (n.startswith('.') and not part.startswith('.'))]
    except OSError: return text
    if not names: return text
    common = os.path.commonprefix(names)
    head = text[:len(text) - len(part)] if part else text
    out = head + common
    if len(names) == 1 and (base / common).is_dir(): out += os.sep
    return out
