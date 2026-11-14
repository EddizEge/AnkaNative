"""The Android launcher's texts and their translations (docs/i18n.md): the string resources (android/app/src/main/res/values*/strings_<area>.xml), the keys the Java
names, Tr's JVM backend (TrJvmTest), and the ratchet that counts the English texts still written in the Java (dm/tools/tests/i18n_ratchet/<area>.tsv).

  python3 dm/tools/tests/test_android_i18n.py                     the rules and the ratchet (prints each language's completeness)
  python3 dm/tools/tests/test_android_i18n.py --list File.java    the texts of that file the ratchet counts, with their lines
  python3 dm/tools/tests/test_android_i18n.py --ratchet <area>    writes that area's current counts (lower only: it refuses a count that grew)
"""
import collections
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
APP = REPO / 'android/app/src'
RES = APP / 'main/res'
JAVA_DIRS = [APP / 'main/java/dev/nxnative/android', APP / 'ondevice/java/dev/nxnative/android']
RATCHET = Path(__file__).resolve().parent / 'i18n_ratchet'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={RES}'
TEST = APP / 'jvmtest/java/dev/nxnative/android/TrJvmTest.java'

# One file per area (work package); every language folder has the same files. Keys start with "<area>_"
AREAS = ('common', 'home', 'prepare', 'readiness', 'game', 'saves', 'packs', 'settings', 'errors')
LANGS = {'tr': 'values-tr', 'pt-BR': 'values-pt-rBR', 'es-419': 'values-b+es+419'}
KEY = re.compile(r'^[a-z][a-z0-9]*(_[a-z0-9]+)+$')
PLACEHOLDER = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(]*\d*(?:\.\d+)?)([a-zA-Z%])')

# The ratchet's extractor (the i18n scout's, 2026-11-14): Java literals that read as text shown to a user, by the call they are passed to
TECH = {'Zstd.java', 'Native.java', 'Outputs.java', 'ShaderResults.java', 'LogProvider.java', 'PackEngine.java', 'FakeEngine.java',   # failure details: stay English
        'Tr.java', 'TrAndroid.java', 'Msg.java', 'LocalizedIOException.java'}   # and the texts' own machinery (its errors are for developers)
SKIPCALL = {'equals', 'equalsIgnoreCase', 'startsWith', 'endsWith', 'contains', 'indexOf', 'getString', 'putString', 'getBoolean', 'putBoolean', 'getInt', 'putInt',
            'getLong', 'putLong', 'getFloat', 'putFloat', 'remove', 'optString', 'optInt', 'optLong', 'optBoolean', 'optJSONObject', 'optJSONArray', 'put',
            'getJSONObject', 'getJSONArray', 'has', 'compile', 'matches', 'replaceAll', 'replace', 'split', 'getenv', 'getProperty', 'putExtra', 'getStringExtra',
            'getBooleanExtra', 'getIntExtra', 'hasExtra', 'File', 'loadLibrary', 'parse', 'setAction', 'Intent', 'getSystemService', 'SuppressWarnings', 'setType',
            'getColumnIndex', 'exec', 'ProcessBuilder', 'command', 'setMimeType', 'getParcelableExtra', 'gameKey', 'open', 'Prefs', 'getSharedPreferences',
            'newPlainText', 'lastIndexOf', 'Thread', 'getQueryParameter', 'setDataAndType', 'buildDocumentUriUsingTree', 'resolve', 'Paths', 'get', 'of'}
LOGCALL = {'i', 'w', 'e', 'd', 'v', 'wtf'}
SHOWN = {'makeText', 'setContentTitle', 'setContentText', 'setSubText', 'bigText', 'setTicker', 'NotificationChannel', 'setDescription', 'Action', 'setTitle',
         'setMessage', 'sheet', 'confirmDanger', 'warnChip', 'readinessDialog', 'confirm', 'ask', 'info', 'alert', 'message', 'showMessage', 'setPositiveButton',
         'setNegativeButton', 'setNeutralButton', 'button', 'action', 'iconButton', 'more', 'setContentDescription', 'Switch', 'fail', 'refuse', 'refused', 'why',
         'problem', 'reject', 'err', 'error', 'Check', 'Req', 'note', 'line', 'createChooser', 'heading', 'body', 'small', 'title', 'text', 'chip', 'section', 'status',
         'header', 'Setting', 'setText', 'value', 'setHint', 'append', 'tile', 'add', 'addView'}   # calls whose text is shown: the innermost one decides
TOKEN = re.compile(r'[A-Za-z_$][\w$]*|\d[\w.]*')


def tokens(src):
    """Java tokens (kind, text, line); comments dropped"""
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1; i += 1; continue
        if c.isspace():
            i += 1; continue
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); j = n if j < 0 else j + 2
            line += src.count('\n', i, j); i = j; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); j = n if j < 0 else j + 3
            yield ('str', src[i + 3:j - 3], line); line += src.count('\n', i, j); i = j; continue
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == '\\' else 1
            yield ('str' if c == '"' else 'chr', src[i + 1:j], line); i = j + 1; continue
        m = TOKEN.match(src, i)
        if m:
            yield ('id' if not c.isdigit() else 'num', m.group(), line); i = m.end(); continue
        yield ('op', c, line); i += 1


def visible(s):
    """A literal that reads as text: a space, or one capitalized word ("Allow", "Back…")"""
    if not re.search(r'[A-Za-zÀ-ÿ]', s):
        return False
    if re.match(r'^\[[A-Za-z]+\]', s):
        return True
    if ' ' not in s:
        return bool(re.match(r'^[A-Z][a-z]+([.!…:?]|\.\.\.)?$', s)) or s in ('OK', 'GPU', 'FPS', 'RAM')
    return not (re.match(r'^[\w./\-]+=', s) or re.match(r'^[a-z_]+(\.[a-z_]+)+ ', s))


def texts(path):
    """The literals of a Java file the ratchet counts: [(line, text)]. Left out: log lines (Log.*, Native.note, log(), [RED]-style tags), the readiness report's
    English lines, literals on a line marked // i18n-keep, and the files of failure details (TECH)"""
    if path.name in TECH:
        return []
    src = path.read_text(encoding='utf-8')
    lines = src.split('\n')
    toks = list(tokens(src))
    stack, out = [], []
    for k, (t, v, ln) in enumerate(toks):
        if t == 'op' and v == '(':
            name = toks[k - 1][1] if k and toks[k - 1][0] == 'id' else ''
            qual = toks[k - 3][1] if k >= 3 and toks[k - 2][1] == '.' and toks[k - 3][0] == 'id' else ''
            stack.append((name, qual, k >= 2 and toks[k - 2][1] == 'new'))
            continue
        if t == 'op' and v == ')':
            if stack:
                stack.pop()
            continue
        if t != 'str' or not visible(v) or 'i18n-keep' in lines[ln - 1]:
            continue
        prev = toks[k - 1] if k else ('', '', 0)
        nxt = toks[k + 1] if k + 1 < len(toks) else ('', '', 0)
        if prev[1] == 'case' or (nxt[1] == '.' and k + 2 < len(toks) and toks[k + 2][1] in SKIPCALL):
            continue
        kind = None
        for name, qual, isnew in reversed(stack):
            if (qual == 'Log' and name in LOGCALL) or (qual == 'Native' and name == 'note') or (name in ('log', 'logLine') and qual in ('', 'this', 'job', 'j')):
                kind = 'log'; break
            if name in SHOWN or (isnew and re.search(r'(Exception|Error|Failure)$', name)):
                break
            if name in SKIPCALL:
                kind = 'skip'; break
        if kind in ('log', 'skip') or re.match(r'^\[(RED|YELLOW|GREEN|pack|overlay|ondevice)\]', v):
            continue
        if re.match(r'^(\\n)?--|.*(Anka Native readiness report|Signer: |\\nPhone: |\\nGame: )', v):
            continue
        out.append((ln, v))
    return out


def java_files():
    return sorted(p for d in JAVA_DIRS for p in d.glob('*.java'))


def read_ratchet():
    """{file name: (area, count)}; an error when a file is in two areas"""
    out = {}
    for f in sorted(RATCHET.glob('*.tsv')):
        for row in f.read_text(encoding='utf-8').splitlines():
            if not row.strip() or row.startswith('#'):
                continue
            name, count = row.split('\t')
            if name in out:
                raise ValueError(f'{name} is in {out[name][0]}.tsv and {f.stem}.tsv')
            out[name] = (f.stem, int(count))
    return out


def string_files(folder):
    return sorted((RES / folder).glob('strings*.xml'))


def parse(path):
    """{key: ('string', text, attrs) | ('plurals', {quantity: text}, attrs)} of one strings file, raw (as written, before aapt2's unescaping)"""
    root = ET.parse(path).getroot()
    out = {}
    for e in root:
        if e.tag not in ('string', 'plurals'):
            raise ValueError(f'{path}: <{e.tag}> (only <string> and <plurals>)')
        name = e.get('name')
        if name in out:
            raise ValueError(f'{path}: {name} twice')
        if e.tag == 'string':
            if len(e):
                raise ValueError(f'{path}: {name} has markup (plain text only)')
            out[name] = ('string', e.text or '', dict(e.attrib))
        else:
            items = {}
            for it in e:
                if it.tag != 'item' or len(it):
                    raise ValueError(f'{path}: {name}: only plain <item>s')
                items[it.get('quantity')] = it.text or ''
            out[name] = ('plurals', items, dict(e.attrib))
    return out


def placeholders(text):
    """The text's arguments as {'1$s', '2$d'}; ValueError for one without a position"""
    out = set()
    for m in PLACEHOLDER.finditer(text):
        if m.group(3) in '%n':
            continue
        if not m.group(1):
            raise ValueError(f'"{text}": %{m.group(2)}{m.group(3)} needs a position (%1${m.group(3)})')
        out.add(f'{m.group(1)}${m.group(3)}')
    return out


def escape_problems(text):
    """aapt2's traps: an unescaped ' (the build fails) or " (silently dropped), a leading @ or ? (read as a reference)"""
    out, quoted, i = [], False, 0
    while i < len(text):
        c = text[i]
        if c == '\\':
            i += 2; continue
        if c == "'" and not quoted:
            out.append("unescaped ' (write \\')")
        if c == '"':
            out.append('unescaped " (write \\")')
            quoted = not quoted
        i += 1
    if text.strip()[:1] in ('@', '?'):
        out.append('leading @ or ? (write \\@ or \\?)')
    return out


def java_keys():
    """{(type, key): [where]} named in the Java (comments left out): Tr.s/english (string), Tr.p/englishP (plurals), new Msg / new LocalizedIOException (string),
    Msg.plural (plurals), R.string.x / R.plurals.x (the app's R, not android.R)"""
    out = collections.defaultdict(list)
    for p in java_files():
        t = list(tokens(p.read_text(encoding='utf-8')))
        v = [x[1] for x in t]
        for k in range(len(t)):
            hit = None
            if v[k:k + 4] in (['Tr', '.', 's', '('], ['Tr', '.', 'english', '(']) and k + 4 < len(t) and t[k + 4][0] == 'str':
                hit = ('string', v[k + 4])
            elif v[k:k + 4] in (['Tr', '.', 'p', '('], ['Tr', '.', 'englishP', '('], ['Msg', '.', 'plural', '(']) and k + 4 < len(t) and t[k + 4][0] == 'str':
                hit = ('plurals', v[k + 4])
            elif v[k:k + 3] in (['new', 'Msg', '('], ['new', 'LocalizedIOException', '(']) and k + 3 < len(t) and t[k + 3][0] == 'str':
                hit = ('string', v[k + 3])
            elif v[k] == 'R' and v[k + 1:k + 2] == ['.'] and k + 4 < len(t) and v[k + 2] in ('string', 'plurals') and v[k + 3] == '.' and (k == 0 or v[k - 1] != '.'):
                hit = (v[k + 2], v[k + 4])
            if hit:
                out[hit].append(f'{p.name}:{t[k][2]}')
    return out


class Resources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = {}   # key -> (type, value, attrs, file name)
        for f in string_files('values'):
            for k, (typ, val, attrs) in parse(f).items():
                if k in cls.base:
                    raise AssertionError(f'{k} in {cls.base[k][3]} and {f.name}')
                cls.base[k] = (typ, val, attrs, f.name)

    def test_files_per_area(self):
        """values/ holds strings_<area>.xml for each area and nothing else of strings; each translation has the same files"""
        want = [f'strings_{a}.xml' for a in AREAS]
        self.assertEqual(sorted(f.name for f in string_files('values')), sorted(want))
        for lang, folder in LANGS.items():
            self.assertEqual(sorted(f.name for f in string_files(folder)), sorted(want), lang)
        strays = [p for p in RES.glob('values*/strings*.xml') if p.parent.name not in ('values', *LANGS.values())]
        self.assertEqual(strays, [], 'a language folder docs/i18n.md does not list: add it to LANGS and Lang.TAGS together')

    def test_keys_named_by_area(self):
        for k, (typ, val, attrs, fname) in self.base.items():
            area = fname[len('strings_'):-len('.xml')]
            self.assertRegex(k, KEY, f'{fname}: {k}: lower case words joined by _')
            self.assertTrue(k.startswith(area + '_'), f'{fname}: {k} must start with {area}_')

    def test_english(self):
        for k, (typ, val, attrs, fname) in self.base.items():
            vals = [val] if typ == 'string' else list(val.values())
            if typ == 'plurals':
                self.assertIn('other', val, f'{k}: plurals need an "other" item')
                self.assertTrue(set(val) <= {'zero', 'one', 'two', 'few', 'many', 'other'}, k)
            for v in vals:
                self.assertEqual(escape_problems(v), [], f'{fname}: {k}: "{v}"')
                placeholders(v)
            if k.startswith('readiness_req_'):
                for v in vals:
                    self.assertIn(': ', v, f'{k}: a readiness requirement is "title: why" (Readiness.Req splits on ": ")')

    def test_translations(self):
        for lang, folder in LANGS.items():
            for f in string_files(folder):
                for k, (typ, val, attrs) in parse(f).items():
                    where = f'{folder}/{f.name}: {k}'
                    self.assertIn(k, self.base, f'{where}: not in values/ (a translation never adds a key)')
                    btyp, bval, battrs, bfile = self.base[k]
                    self.assertEqual(bfile, f.name, f'{where}: the English is in {bfile}')
                    self.assertEqual(typ, btyp, where)
                    self.assertNotEqual(battrs.get('translatable'), 'false', f'{where}: translatable="false" in values/')
                    if typ == 'string':
                        self.assertEqual(escape_problems(val), [], f'{where}: "{val}"')
                        self.assertEqual(placeholders(val), placeholders(bval), f'{where}: the same arguments as the English "{bval}"')
                    else:
                        self.assertIn('other', val, f'{where}: plurals need an "other" item')
                        want = set().union(*(placeholders(v) for v in bval.values()))
                        got = [placeholders(v) for v in val.values()]
                        for v in val.values():
                            self.assertEqual(escape_problems(v), [], f'{where}: "{v}"')
                        self.assertEqual(set().union(*got), want, f'{where}: the same arguments as the English')
                        if k.startswith('readiness_req_'):
                            for v in val.values():
                                self.assertIn(': ', v, where)
                    if typ == 'string' and k.startswith('readiness_req_'):
                        self.assertIn(': ', val, f'{where}: "title: why"')

    def test_java_keys_exist(self):
        missing = []
        for (typ, k), where in sorted(java_keys().items()):
            if k == 'app_name':
                continue   # build.gradle's resValue
            if k not in self.base or self.base[k][0] != typ:
                missing.append(f'{typ} {k} ({", ".join(where)})')
        self.assertEqual(missing, [], 'keys the Java names that values/ lacks')

    def test_java_keys_are_literals(self):
        """A key is a string literal directly in the call (docs/i18n.md), so test_java_keys_exist sees it: a key built at run time could name a text that
        does not exist, and the app would throw MissingResourceException. Tr, TrAndroid, Msg and LocalizedIOException pass keys on and are left out"""
        machinery = {'Tr.java', 'TrAndroid.java', 'Msg.java', 'LocalizedIOException.java'}
        bad = []
        for p in java_files():
            if p.name in machinery:
                continue
            t = list(tokens(p.read_text(encoding='utf-8')))
            v = [x[1] for x in t]
            for k in range(len(t)):
                at = None
                if v[k:k + 2] in (['Tr', '.'], ['Msg', '.']) and k + 3 < len(t) and v[k + 3] == '(' and (v[k], v[k + 2]) in (
                        ('Tr', 's'), ('Tr', 'p'), ('Tr', 'english'), ('Tr', 'englishP'), ('Msg', 'plural')):
                    at, ok = k + 4, t[k + 4][0] == 'str' if k + 4 < len(t) else False
                elif v[k:k + 3] == ['new', 'Msg', '(']:
                    at, ok = k + 3, k + 3 < len(t) and t[k + 3][0] == 'str'
                elif v[k:k + 3] == ['new', 'LocalizedIOException', '(']:   # a key, or a Msg built right there
                    at, ok = k + 3, k + 3 < len(t) and (t[k + 3][0] == 'str' or v[k + 3:k + 5] == ['new', 'Msg'])
                elif v[k] == 'getIdentifier':
                    at, ok = k, False
                if at is not None and ok and t[at][0] == 'str' and v[at + 1:at + 2] not in ([','], [')']):
                    ok = False   # a literal with more glued on: "readiness_req_" + fact
                if at is not None and not ok:
                    bad.append(f'{p.name}:{t[k][2]}: {" ".join(v[k:k + 6])}')
        self.assertEqual(bad, [], 'a key that is not a string literal in the call (or a getIdentifier outside TrAndroid)')

    def test_completeness(self):
        """Not a failure: how much of the English each language has (docs/i18n.md: untranslated is absent, never English copied)"""
        todo = [k for k, v in self.base.items() if v[2].get('translatable') != 'false']
        for lang, folder in LANGS.items():
            have = {}
            for f in string_files(folder):
                have.update(parse(f))
            same = [k for k in todo if k in have and have[k][0] == 'string' and have[k][1] == self.base[k][1]]
            print(f'{lang}: {sum(k in have for k in todo)} of {len(todo)} texts ({100 * sum(k in have for k in todo) // max(len(todo), 1)} %)'
                  + (f', {len(same)} the same as the English: {", ".join(same)}' if same else ''), file=sys.stderr)


class Ratchet(unittest.TestCase):
    def test_no_new_english_in_java(self):
        """A file's count of English texts in the Java never grows; a file in no area's list must have none (docs/i18n.md)"""
        base = read_ratchet()
        grew, lower = [], []
        for p in java_files():
            n = len(texts(p))
            area, b = base.get(p.name, ('(none)', 0))
            if n > b:
                grew.append(f'{p.name}: {n} texts, the ratchet ({area}) allows {b}. Move them to strings_<area>.xml, or mark a line that must stay English '
                            f'with // i18n-keep (--list {p.name} shows them)')
            elif n < b:
                lower.append(f'{p.name} {b} -> {n} (--ratchet {area})')
        if lower:
            print('ratchet can go lower: ' + '; '.join(lower), file=sys.stderr)
        self.assertEqual(grew, [])

    def test_ratchet_lists_files_that_exist(self):
        names = {p.name for p in java_files()}
        self.assertEqual(sorted(set(read_ratchet()) - names), [], 'ratchet rows of files that are gone')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class TrJvm(unittest.TestCase):
    def test_tr(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, TR), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (d / 'run').mkdir()
            r = subprocess.run(['java', TR_RES, '-Dstdout.encoding=UTF-8', '-cp', str(d / 'classes'), 'dev.nxnative.android.TrJvmTest', str(d / 'run')],
                               capture_output=True, text=True, encoding='utf-8')
            self.assertEqual((r.returncode, r.stdout.strip()), (0, 'ok'), r.stdout + r.stderr)


def write_ratchet(area):
    """--ratchet <area>: the area's files at their current counts; refuses a count above the list's"""
    f = RATCHET / f'{area}.tsv'
    if not f.is_file():
        sys.exit(f'no {f}')
    rows, head = [], [r for r in f.read_text(encoding='utf-8').splitlines() if r.startswith('#')]
    byname = {p.name: p for p in java_files()}
    for row in f.read_text(encoding='utf-8').splitlines():
        if not row.strip() or row.startswith('#'):
            continue
        name, count = row.split('\t')
        n = len(texts(byname[name])) if name in byname else 0
        if n > int(count):
            sys.exit(f'{name}: {n} texts, more than the {count} allowed: the ratchet only goes down')
        rows.append(f'{name}\t{n}')
    f.write_text('\n'.join(head + rows) + '\n', encoding='utf-8')
    print('\n'.join(rows))


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--list':
        p = next((p for p in java_files() if p.name == sys.argv[2]), None)
        if p is None:
            sys.exit(f'no {sys.argv[2]} in {", ".join(str(d) for d in JAVA_DIRS)}')
        for ln, v in texts(p):
            print(f'{p.name}:{ln}\t{v}')
    elif len(sys.argv) == 3 and sys.argv[1] == '--ratchet':
        write_ratchet(sys.argv[2])
    else:
        unittest.main()
