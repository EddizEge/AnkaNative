# An in-memory screen of cells (a character and a style each) that the views draw into, and its output as escape sequences: each frame is compared with the one
# on the terminal and only the rows that changed are written. The tests draw into it and read it back as text (Screen.text), at any size, without a terminal.
# Glyphs: the box, bar and mark characters in Unicode, or in ASCII when the console's encoding cannot print them (glyphs_for).
# Styles: bold, dim and reverse always (the selection is reverse video, so it shows without colour), the colours only when colour is on (NO_COLOR off, app.py).
# Widths: a character of the East Asian wide or full-width classes takes two cells, a combining one (or a variation selector) none: it goes into the cell of the
# character before it, after the text is composed (NFC: a name the Mac stores decomposed keeps its accents). Format characters (bidirectional controls, joiners) are
# not drawn, and control characters (C0, DEL, C1: a file name may hold any of them, and C1's CSI would start an escape sequence) are drawn as '?'.
# Columns are padded by cells (pad), never by str.ljust, which counts characters.
import unicodedata

BOLD, DIM, REV, UNDER = 1, 2, 4, 8
GREEN, RED, YELLOW, CYAN = 16, 32, 48, 64   # a colour is one of these in bits 4-6
COLOR_MASK = 112
SGR_COLOR = {GREEN: '32', RED: '31', YELLOW: '33', CYAN: '36'}


class Glyphs:
    def __init__(self, unicode):
        u = unicode
        self.unicode = u
        self.h, self.v = ('─', '│') if u else ('-', '|')
        self.tl, self.tr, self.bl, self.br = ('┌', '┐', '└', '┘') if u else ('+', '+', '+', '+')
        self.full, self.empty = ('█', '░') if u else ('#', '.')
        self.now, self.ok, self.bad, self.todo, self.skip = ('▶', '✓', '✗', '·', '–') if u else ('>', '+', 'x', '.', '-')
        self.dots = '…' if u else '...'
        self.sep = ' · ' if u else ' - '
        self.folder, self.up, self.more = ('▸', '↑', '↓') if u else ('>', '^', 'v')   # more: lines below the screen

    def all(self):
        return ''.join([self.h, self.v, self.tl, self.tr, self.bl, self.br, self.full, self.empty, self.now, self.ok, self.bad, self.todo, self.skip, self.dots, self.sep,
                        self.folder, self.up, self.more])


def glyphs_for(encoding):
    """Unicode glyphs when the encoding can write every one of them, else ASCII"""
    try: Glyphs(True).all().encode(encoding or 'ascii')
    except (UnicodeEncodeError, LookupError): return Glyphs(False)
    return Glyphs(True)


def cw(c):
    """The cells a character takes"""
    if unicodedata.combining(c) or unicodedata.category(c) in ('Mn', 'Me', 'Cf'): return 0
    return 2 if unicodedata.east_asian_width(c) in 'WF' else 1


def width(s): return sum(cw(c) for c in s)


def pad(s, n):
    """s with spaces after it to n cells"""
    return s + ' ' * max(0, n - width(s))


def fit(s, n, dots='…', left=False):
    """s cut to at most n cells, with dots where it was cut (at the start when left: a path keeps its end)"""
    if width(s) <= n: return s
    if n <= width(dots): return dots[:n]
    room = n - width(dots)
    if left:
        out, w = '', 0
        for c in reversed(s):
            if w + cw(c) > room: break
            out, w = c + out, w + cw(c)
        return dots + out
    out, w = '', 0
    for c in s:
        if w + cw(c) > room: break
        out, w = out + c, w + cw(c)
    return out + dots


def wrap(s, n):
    """s in lines of at most n cells, broken at spaces where it can be (a long word is cut)"""
    out = []
    for para in s.split('\n'):
        line = ''
        for word in para.split(' '):
            cand = word if not line else line + ' ' + word
            if width(cand) <= n: line = cand; continue
            if line: out.append(line)
            while width(word) > n:   # a word longer than a line (a path): cut after its last separator that fits, else at the line's end
                part = fit(word, n, '')
                cut = max(part.rfind('/'), part.rfind('\\'))
                if cut > 0: part = part[:cut + 1]
                out.append(part); word = word[len(part):]
            line = word
        out.append(line)
    return out


def wrap_cmd(s, n, cont):
    """A command in lines of at most n cells: whole when it fits, else broken at spaces outside single quotes (a backslash and a newline inside them would
    stay as they are), each line but the last ending in cont, the shell's line continuation, so that the lines copied together run as one command. A word
    longer than a line (a URL) is cut after a separator (never at a space), with no mark: no continuation can go inside a word"""
    if width(s) <= n: return [s]
    words, cur, quoted = [], '', False
    for ch in s:
        if ch == "'": quoted = not quoted
        if ch == ' ' and not quoted: words.append(cur); cur = ''
        else: cur += ch
    words.append(cur)
    out, line = [], ''
    for i, word in enumerate(words):
        last = i == len(words) - 1
        cand = word if not line else line + ' ' + word
        if width(cand) + (0 if last else width(cont)) <= n: line = cand; continue
        if line: out.append(line + cont)
        if width(word) + (0 if last else width(cont)) <= n: line = word; continue
        while width(word) > n - width(cont):   # (cut after its last separator that fits, as wrap cuts a path, but never at a space: the last part leaves room for cont)
            part = fit(word, n - width(cont), '')
            cut = max(part.rfind('/'), part.rfind('\\'))
            if cut > 0: part = part[:cut + 1]
            out.append(part); word = word[len(part):]
        line = word
    if line: out.append(line)
    return out


class Screen:
    def __init__(self, w, h, glyphs=None):
        self.w, self.h = w, h
        self.g = glyphs or Glyphs(True)
        self.rows = [[(' ', 0)] * w for _ in range(h)]

    def put(self, x, y, s, style=0, maxw=None):
        """s at column x of row y, cut at the screen's edge (or at maxw cells); → the column after it"""
        if not 0 <= y < self.h: return x
        end = self.w if maxw is None else min(self.w, x + maxw)
        row = self.rows[y]
        last = -1   # the cell of the character before (where a combining one goes)
        for c in unicodedata.normalize('NFC', s):
            if c < ' ' or '\x7f' <= c <= '\x9f': c = '?'
            n = cw(c)
            if n == 0:
                if unicodedata.category(c) != 'Cf' and last >= 0: row[last] = (row[last][0] + c, row[last][1])
                continue
            if x + n > end: break
            if x >= 0:
                row[x] = (c, style); last = x
                if n == 2: row[x + 1] = ('', style)   # the second cell of a wide character
            x += n
        return x

    def fill(self, x, y, n, style=0, c=' '):
        for i in range(max(0, x), min(self.w, x + n)):
            if 0 <= y < self.h: self.rows[y][i] = (c, style)

    def bar(self, x, y, n, frac, style=0):
        """A bar of n cells, frac of it full"""
        frac = max(0.0, min(1.0, frac))
        k = int(round(frac * n))
        self.put(x, y, self.g.full * k, style); self.put(x + k, y, self.g.empty * (n - k), DIM)

    def box(self, x, y, w, h, title='', style=0):
        g = self.g
        self.put(x, y, g.tl + g.h * (w - 2) + g.tr, style)
        for i in range(1, h - 1): self.put(x, y + i, g.v, style); self.put(x + w - 1, y + i, g.v, style)
        self.put(x, y + h - 1, g.bl + g.h * (w - 2) + g.br, style)
        if title: self.put(x + 2, y, f' {fit(title, w - 6, g.dots)} ', style | BOLD)

    def text(self):
        """The screen as text, one line a row, trailing spaces dropped (the tests' view of it)"""
        return '\n'.join(''.join(c for c, _ in r).rstrip() for r in self.rows)


def sgr(style, color):
    p = ['0']
    if style & BOLD: p.append('1')
    if style & DIM: p.append('2')
    if style & UNDER: p.append('4')
    if style & REV: p.append('7')
    if color and style & COLOR_MASK: p.append(SGR_COLOR[style & COLOR_MASK])
    return '\x1b[' + ';'.join(p) + 'm'


def render(new, old=None, color=True):
    """The escape sequences that turn the terminal showing old (None: anything) into new: each row that differs written whole"""
    out = []
    for y, row in enumerate(new.rows):
        if old is not None and old.w == new.w and old.h == new.h and old.rows[y] == row: continue
        out.append(f'\x1b[{y + 1};1H')
        cur = None
        for c, st in row:
            if c == '': continue
            if st != cur: out.append(sgr(st, color)); cur = st
            out.append(c)
        out.append('\x1b[0m')
    return ''.join(out)
