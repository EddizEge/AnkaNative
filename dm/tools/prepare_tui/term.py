# The terminal of scripts/prepare.py's full-screen mode: keys in, escape sequences out, and the terminal always put back as it was.
# Keys come in two families, both decoded by Decoder:
#   - escape sequences (Linux and Mac terminals, Windows Terminal with virtual terminal input): CSI (ESC [ ... final) and SS3 (ESC O x) forms of the arrows, Home / End,
#     Insert / Delete, PgUp / PgDn and F1-F12 (the xterm, rxvt and Linux console spellings, modifiers ignored), and bracketed paste (ESC [200~ ... ESC [201~)
#   - the Windows console's scan codes, as msvcrt.getwch returns them: a 0x00 or 0xE0 character and then the key's scan code (H up, P down, ...), only on a console
#     that does not take virtual terminal input (older than Windows 10). There 0xE0 is also the character à (French and Italian keyboards): it is taken as a scan
#     code only when a known one follows it
# A lone ESC is the Escape key once nothing follows it for ESC_WAIT seconds (Decoder.flush): long enough for an arrow key's sequence that ssh or a busy computer
# delivers in two parts.
# PosixTerminal: termios (no echo, no line editing, no signals from Ctrl+C / Ctrl+Z, no flow control, the output's newline translation kept), select for input with a
# timeout, SIGWINCH needs no handler (the size is read before each frame). A stop from outside (kill -TSTP; SIGSTOP) and `fg` after it: SIGTSTP puts the terminal
# back before the process stops, SIGCONT takes it again and has the screen drawn whole (dirty).
# WindowsTerminal: kernel32's console modes through ctypes (virtual terminal processing for the output; for the input virtual terminal input, so keys come as
# escape sequences, processed / line / echo input off, so Ctrl+C is a key, and quick edit off, so a stray click does not start a selection that holds the screen),
# msvcrt for the keys, and a console control handler that closes the job when the window closes.
# enter() switches to the alternate screen with the cursor hidden and bracketed paste on; restore() puts back the modes and the screen, once, however it is reached
# (the caller's finally, atexit, SIGTERM / SIGHUP turned into SystemExit by app.py, the console's close event).
import atexit, codecs, os, signal, sys, time
from collections import namedtuple
from typing import Callable

Key = namedtuple('Key', 'name text')   # name: 'char' (text: the character), 'paste' (text: what was pasted), else the key's name ('up', 'enter', 'f5', 'ctrl-c', ...)
ESC_WAIT = 0.15

# CSI <n> ~ keys (xterm, rxvt, the Linux console)
TILDE = {1: 'home', 2: 'insert', 3: 'delete', 4: 'end', 5: 'pgup', 6: 'pgdn', 7: 'home', 8: 'end', 11: 'f1', 12: 'f2', 13: 'f3', 14: 'f4', 15: 'f5', 17: 'f6', 18: 'f7',
         19: 'f8', 20: 'f9', 21: 'f10', 23: 'f11', 24: 'f12'}
# CSI and SS3 final letters
FINAL = {'A': 'up', 'B': 'down', 'C': 'right', 'D': 'left', 'H': 'home', 'F': 'end', 'P': 'f1', 'Q': 'f2', 'R': 'f3', 'S': 'f4', 'Z': 'backtab', 'M': 'enter'}
# the Linux console's F1-F5: ESC [ [ A..E
LINUX_F = {'A': 'f1', 'B': 'f2', 'C': 'f3', 'D': 'f4', 'E': 'f5'}
# The Windows console's scan codes after 0x00 / 0xE0 (Ctrl+ forms as the plain key)
SCAN = {'H': 'up', 'P': 'down', 'K': 'left', 'M': 'right', 'G': 'home', 'O': 'end', 'I': 'pgup', 'Q': 'pgdn', 'S': 'delete', 'R': 'insert',
        ';': 'f1', '<': 'f2', '=': 'f3', '>': 'f4', '?': 'f5', '@': 'f6', 'A': 'f7', 'B': 'f8', 'C': 'f9', 'D': 'f10', '\x85': 'f11', '\x86': 'f12',
        's': 'left', 't': 'right', 'w': 'home', 'u': 'end', '\x84': 'pgup', 'v': 'pgdn', '\x8d': 'up', '\x91': 'down', '\x93': 'delete'}
CONTROL = {'\r': 'enter', '\n': 'enter', '\t': 'tab', '\x7f': 'backspace', '\x08': 'backspace', '\x1b': 'esc'}


class Decoder:
    """Characters in (feed), keys out. scancodes: the Windows console family is on (a 0x00 or 0xE0 character starts a scan code), as msvcrt.getwch returns keys"""
    def __init__(self, scancodes=False):
        self.scancodes, self.buf, self.paste = scancodes, '', None

    def feed(self, s):
        self.buf += s
        out = []
        while self.buf:
            n, k = self.take(self.buf)
            if n == 0: break   # an unfinished sequence: wait for more (or flush)
            self.buf = self.buf[n:]
            if k is None: continue
            if self.paste is not None:   # inside a bracketed paste every key is text until its end
                if k.name == 'paste-end': out.append(Key('paste', self.paste)); self.paste = None
                elif k.name == 'char': self.paste += k.text
                elif k.name == 'enter': self.paste += '\n'
                elif k.name == 'tab': self.paste += '\t'
                continue
            if k.name == 'paste-start': self.paste = ''; continue
            if k.name == 'paste-end': continue
            out.append(k)
        return out

    def flush(self):
        """What is pending when no more characters came (ESC_WAIT): a lone ESC is the Escape key; an unfinished sequence after it is taken as its characters.
        With scan codes, a lone 0xE0 is the character à (the console gives a scan code's two characters together), a lone 0x00 Ctrl+Space"""
        out = []
        while self.buf:
            c = self.buf[0]
            if c == '\x1b': out.append(Key('esc', ''))
            elif self.scancodes and c == '\xe0': out.append(Key('char', c))
            elif self.scancodes and c == '\x00': out.append(Key('ctrl-space', ''))
            else: out += self.feed(''); break
            self.buf = self.buf[1:]; out += self.feed('')
        return out

    def take(self, b):
        """(characters used, Key or None) at the start of b; (0, None) when b holds only the start of a sequence"""
        c = b[0]
        if self.scancodes and c in '\x00\xe0':
            if len(b) < 2: return 0, None
            if c == '\xe0' and b[1] not in SCAN: return 1, Key('char', c)   # the character à, and what follows it is another key
            return 2, Key(SCAN.get(b[1], 'unknown'), '')
        if c == '\x1b':
            if len(b) < 2: return 0, None
            if b[1] == '[':
                if len(b) < 3: return 0, None
                if b[2] == '[':   # the Linux console's F1-F5
                    if len(b) < 4: return 0, None
                    return 4, Key(LINUX_F.get(b[3], 'unknown'), '')
                i = 2
                while i < len(b) and (b[i].isdigit() or b[i] in ';:<=>?'): i += 1
                if i == len(b): return (0, None) if i < 24 else (i, Key('unknown', ''))   # an endless sequence is dropped
                final, params = b[i], b[2:i]
                if final == '~':
                    n = int(params.split(';')[0] or 0) if params.split(';')[0].isdigit() else 0
                    if n == 200: return i + 1, Key('paste-start', '')
                    if n == 201: return i + 1, Key('paste-end', '')
                    return i + 1, Key(TILDE.get(n, 'unknown'), '')
                return i + 1, Key(FINAL.get(final, 'unknown'), '')
            if b[1] == 'O':
                if len(b) < 3: return 0, None
                return 3, Key(FINAL.get(b[2], 'unknown'), '')
            return 1, Key('esc', '')   # ESC and another key (Alt+key): the Escape key, then that key
        if c in CONTROL: return 1, Key(CONTROL[c], '')
        if c < ' ': return 1, Key(f'ctrl-{chr(ord(c) + 96)}', '')
        return 1, Key('char', c)


# Escape sequences of the full-screen mode
ENTER = '\x1b[?1049h\x1b[?25l\x1b[?2004h\x1b[?7l\x1b[H\x1b[2J'   # alternate screen, cursor hidden, bracketed paste, no autowrap, cleared
# cleared first (a terminal without the alternate screen, such as the Linux console, keeps no frame under the lines printed after it), then the modes put back
LEAVE = '\x1b[0m\x1b[H\x1b[2J\x1b[?7h\x1b[?2004l\x1b[?25h\x1b[?1049l'


class Terminal:
    """The common part: the output stream, restore() at most once, atexit as the last resort"""
    def __init__(self, out=None):
        self.out = out or sys.stdout
        self.active = False
        self.dirty = False   # the screen must be drawn whole (the terminal was given back and taken again)

    def write(self, s):
        self.out.write(s); self.out.flush()

    def modes_on(self): raise NotImplementedError   # the system's terminal modes: PosixTerminal, WindowsTerminal

    def modes_off(self): raise NotImplementedError

    def keys(self, timeout): raise NotImplementedError

    def size(self):
        try: c, r = os.get_terminal_size(self.out.fileno())
        except (OSError, ValueError): c, r = 80, 24   # not a terminal (a test's stream): the smallest size the screens are made for
        return max(c, 20), max(r, 8)

    def enter(self):
        try: self.modes_on()
        except BaseException:
            self.modes_off(); raise   # what modes_on had changed before it failed is put back
        self.active = True
        atexit.register(self.restore)
        self.write(ENTER)

    def restore(self):
        if not self.active: return
        self.active = False
        try: self.write(LEAVE)
        except (OSError, ValueError): pass   # the terminal is gone (a closed window, a hang-up): nothing left to put back on it
        self.modes_off()


class PosixTerminal(Terminal):
    def __init__(self, fd=None, out=None):
        super().__init__(out)
        self.fd = sys.stdin.fileno() if fd is None else fd
        self.dec = Decoder(); self.utf8 = codecs.getincrementaldecoder('utf-8')('replace'); self.saved = None

    def modes_on(self):
        import termios
        self.saved = termios.tcgetattr(self.fd)
        self.raw()

    def raw(self):
        import termios
        a = termios.tcgetattr(self.fd)
        a[0] &= ~(termios.IXON | termios.ICRNL | termios.INLCR | termios.IGNCR | termios.ISTRIP)
        a[3] &= ~(termios.ECHO | termios.ICANON | termios.ISIG | termios.IEXTEN)
        a[6][termios.VMIN], a[6][termios.VTIME] = 1, 0
        termios.tcsetattr(self.fd, termios.TCSADRAIN, a)

    def cooked(self):
        """The modes as they were before enter (kept for the next raw)"""
        import termios
        if self.saved is None: return
        try: termios.tcsetattr(self.fd, termios.TCSADRAIN, self.saved)
        except (OSError, termios.error): pass   # the terminal is gone (a closed window): its modes went with it

    def modes_off(self):
        if self.saved is None: return
        self.cooked()
        self.saved = None
        for s in ('SIGTSTP', 'SIGCONT'): signal.signal(getattr(signal, s), signal.SIG_DFL)

    def enter(self):
        super().enter()
        signal.signal(signal.SIGTSTP, self.on_tstp); signal.signal(signal.SIGCONT, self.on_cont)

    def on_tstp(self, _sig, _frame):
        """A stop from outside (kill -TSTP, a job control): the terminal given back as it was, then the process stops as it would have; on_cont takes it again"""
        if self.active:
            try: self.out.write(LEAVE); self.out.flush()
            except (OSError, ValueError): pass   # the terminal is gone: nothing to give back on it
            self.cooked()
        signal.signal(signal.SIGTSTP, signal.SIG_DFL)
        os.kill(os.getpid(), signal.SIGTSTP)   # (stopped here until continued)
        signal.signal(signal.SIGTSTP, self.on_tstp)

    def on_cont(self, _sig, _frame):
        """Continued (fg, or after a SIGSTOP that could not be caught): the full-screen modes again and the screen drawn whole, once in the foreground (in the
        background the terminal's modes cannot be set: the next read stops the process, and the fg that follows continues it here again)"""
        if not self.active or not foreground(self.fd): return
        import termios
        try: self.raw(); self.out.write(ENTER); self.out.flush()
        except (OSError, ValueError, termios.error): return   # the terminal is gone: the next read says so
        self.dirty = True

    def keys(self, timeout):
        """The keys typed within timeout seconds (an empty list when none)"""
        import select
        r, _, _ = select.select([self.fd], [], [], timeout)
        if not r: return self.dec.flush()
        try: data = os.read(self.fd, 4096)
        except OSError as e:
            raise SystemExit(f'[RED] the terminal cannot be read: {e}') from None   # (EIO: the window was closed)
        if not data: raise SystemExit('[RED] the terminal closed')
        out = self.dec.feed(self.utf8.decode(data))
        if self.dec.buf:   # the rest of a sequence comes within ESC_WAIT, else it was a lone ESC
            r, _, _ = select.select([self.fd], [], [], ESC_WAIT)
            if r: return out + self.keys(0)
            out += self.dec.flush()
        return out


class WindowsTerminal(Terminal):
    """The Windows console (Windows Terminal, PowerShell's and cmd.exe's conhost on Windows 10 and 11)"""
    VT_OUT = 0x0004   # ENABLE_VIRTUAL_TERMINAL_PROCESSING
    IN_OFF = 0x0001 | 0x0002 | 0x0004 | 0x0040   # ENABLE_PROCESSED_INPUT (Ctrl+C as a signal), ENABLE_LINE_INPUT, ENABLE_ECHO_INPUT, ENABLE_QUICK_EDIT_MODE
    IN_ON = 0x0080   # ENABLE_EXTENDED_FLAGS (quick edit's bit is taken only with it)
    VT_IN = 0x0200   # ENABLE_VIRTUAL_TERMINAL_INPUT

    def __init__(self, out=None):
        super().__init__(out)
        import ctypes
        from ctypes import wintypes
        self.k32 = ctypes.WinDLL('kernel32', use_last_error=True)   # pyright: ignore[reportAttributeAccessIssue]
        self.ct, self.wt = ctypes, wintypes
        self.hin, self.hout = self.k32.GetStdHandle(-10), self.k32.GetStdHandle(-11)
        self.dec = Decoder(scancodes=True); self.saved = None; self._handler = None
        self.on_close: Callable[[], None] | None = None

    def mode(self, h):
        m = self.wt.DWORD()
        if not self.k32.GetConsoleMode(h, self.ct.byref(m)): return None
        return m.value

    def vt_ok(self):
        """Whether the console takes escape sequences: virtual terminal processing can be turned on (Windows 10 1511 on); put back as it was"""
        m = self.mode(self.hout)
        if m is None: return False
        if m & self.VT_OUT: return True
        ok = bool(self.k32.SetConsoleMode(self.hout, m | self.VT_OUT))
        if ok: self.k32.SetConsoleMode(self.hout, m)
        return ok

    def modes_on(self):
        self.saved = (self.mode(self.hin), self.mode(self.hout), self.k32.GetConsoleOutputCP())
        if self.saved[0] is not None:   # keys as escape sequences where the console can (Windows 10 on), else as msvcrt's scan codes
            vt = bool(self.k32.SetConsoleMode(self.hin, (self.saved[0] & ~self.IN_OFF) | self.IN_ON | self.VT_IN))
            if not vt: self.k32.SetConsoleMode(self.hin, (self.saved[0] & ~self.IN_OFF) | self.IN_ON)
            self.dec = Decoder(scancodes=not vt)
        if self.saved[1] is not None and not self.k32.SetConsoleMode(self.hout, self.saved[1] | self.VT_OUT | 0x0001):
            raise OSError('the console does not take escape sequences')
        self.k32.SetConsoleOutputCP(65001)
        proto = self.ct.WINFUNCTYPE(self.wt.BOOL, self.wt.DWORD)   # pyright: ignore[reportAttributeAccessIssue]

        def handler(kind):   # CTRL_CLOSE_EVENT 2, CTRL_LOGOFF_EVENT 5, CTRL_SHUTDOWN_EVENT 6: the job is stopped (on_close) and the console put back
            if kind in (2, 5, 6):
                if self.on_close: self.on_close()
                self.restore()
                return True
            return kind in (0, 1)   # Ctrl+C / Ctrl+Break: a key here (processed input is off), never the end of the interface
        self._handler = proto(handler)
        self.k32.SetConsoleCtrlHandler(self._handler, True)

    def modes_off(self):
        if self.saved is None: return
        i, o, cp = self.saved; self.saved = None
        if i is not None: self.k32.SetConsoleMode(self.hin, i)
        if o is not None: self.k32.SetConsoleMode(self.hout, o)
        if cp: self.k32.SetConsoleOutputCP(cp)
        if self._handler is not None: self.k32.SetConsoleCtrlHandler(self._handler, False); self._handler = None

    def keys(self, timeout):
        import msvcrt
        end = time.monotonic() + timeout
        chars = ''
        while True:
            while msvcrt.kbhit(): chars += msvcrt.getwch()   # pyright: ignore[reportAttributeAccessIssue]
            if chars or time.monotonic() >= end: break
            time.sleep(0.02)
        out = self.dec.feed(chars)
        if self.dec.buf:
            time.sleep(ESC_WAIT)
            more = ''
            while msvcrt.kbhit(): more += msvcrt.getwch()   # pyright: ignore[reportAttributeAccessIssue]
            out += self.dec.feed(more) if more else self.dec.flush()
        return out


def foreground(fd):
    """Whether this process is in the foreground of the terminal fd (in the background, setting its modes would stop it)"""
    try: return os.tcgetpgrp(fd) == os.getpgrp()
    except OSError: return True   # not this process's controlling terminal (a test's pseudo-terminal): no job control on it to stop the process


def unicode_glyphs():
    """Whether the console's font is likely to have the marks (✓ ▶ ✗): Windows Terminal and other terminals that fall back to other fonts do; conhost's own window
    (Consolas, no fallback) does not"""
    return os.name != 'nt' or bool(os.environ.get('WT_SESSION') or os.environ.get('TERM_PROGRAM'))


def open_terminal():
    """The terminal of this system for the full-screen mode, or (None, why not) when only the plain mode can work"""
    if not (sys.stdin.isatty() and sys.stdout.isatty()): return None, 'not a terminal'
    if os.environ.get('TERM') == 'dumb': return None, 'TERM=dumb'
    if os.name == 'nt':
        t = WindowsTerminal()
        if not t.vt_ok(): return None, 'this console does not take escape sequences'
        return t, ''
    if not os.environ.get('TERM'): return None, 'TERM is not set'
    if not foreground(sys.stdin.fileno()): return None, 'started in the background'
    return PosixTerminal(), ''
