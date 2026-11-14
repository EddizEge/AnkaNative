"""A pseudo-terminal for scripts/prepare.py's full-screen mode (POSIX only): the interface runs on the pty's slave side with a window size set, its output is read
into a small virtual screen (the escape sequences screen.py and term.py write: cursor moves, clear, SGR, the private modes), and keys are written to the master
side. test_prepare_tui.py drives it; the real run's recording (android/README.md) used it too. The modes it tracks: the alternate screen, the cursor, bracketed
paste and autowrap, so a test can check they are put back."""
import os, re, select, subprocess, sys, threading, time

CSI = re.compile(r'\x1b\[([0-9;?]*)([A-Za-z~@])')


class VT:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.rows = [[' '] * w for _ in range(h)]
        self.x = self.y = 0
        self.modes = {}   # private mode number → True (set) / False (reset)
        self.buf = ''
        self.saved = None

    def feed(self, s):
        self.buf += s
        i, b = 0, self.buf
        while i < len(b):
            c = b[i]
            if c == '\x1b':
                m = CSI.match(b, i)
                if not m:
                    if len(b) - i < 16 and not re.match(r'\x1b[^\[]', b[i:]): break   # an unfinished sequence: wait for the rest
                    i += 2; continue
                self.csi(m.group(1), m.group(2)); i = m.end(); continue
            if c == '\r': self.x = 0
            elif c == '\n':   # (the pty's line discipline adds the CR before it)
                if self.y == self.h - 1: self.rows = self.rows[1:] + [[' '] * self.w]
                else: self.y += 1
            elif c == '\x08': self.x = max(0, self.x - 1)
            elif c >= ' ':
                if self.x < self.w: self.rows[self.y][self.x] = c
                self.x = min(self.w - 1, self.x + 1) if self.modes.get(7) is False else self.x + 1
                if self.x >= self.w and self.modes.get(7) is not False: self.x = 0; self.y = min(self.h - 1, self.y + 1)
            i += 1
        self.buf = b[i:]

    def csi(self, p, f):
        if p.startswith('?') and f in 'hl':
            for n in p[1:].split(';'):
                on = f == 'h'
                if int(n) == 1049 and on != self.modes.get(1049, False):   # the alternate screen: the normal one and its cursor saved, then put back
                    if on: self.saved = ([r[:] for r in self.rows], self.x, self.y); self.rows = [[' '] * self.w for _ in range(self.h)]
                    elif self.saved:   # (at this size: a resize meanwhile cuts or pads it, as a terminal reflows its normal screen)
                        rows, x, y = self.saved
                        rows = [(r + [' '] * self.w)[:self.w] for r in rows][-self.h:]
                        self.rows = rows + [[' '] * self.w for _ in range(self.h - len(rows))]
                        self.x, self.y = min(x, self.w - 1), min(y, self.h - 1)
                self.modes[int(n)] = on
            return
        a = [int(x) if x else 0 for x in p.split(';')] if p else []
        if f == 'H':   # (clamped to the screen: a frame drawn for the size before a resize may still come)
            self.y, self.x = min(self.h - 1, max(0, (a[0] if a else 1) - 1)), min(self.w - 1, max(0, (a[1] if len(a) > 1 else 1) - 1))
        elif f == 'J' and (a[:1] == [2]): self.rows = [[' '] * self.w for _ in range(self.h)]
        elif f == 'K': self.rows[self.y][self.x:] = [' '] * (self.w - self.x)

    def text(self):
        return '\n'.join(''.join(r).rstrip() for r in self.rows)

    def resized(self, w, h):
        v = VT(w, h); v.modes, v.saved = dict(self.modes), self.saved
        return v


class Pty:
    """argv run on a pty of w x h with env; its screen in self.vt, everything it wrote in self.raw. ctty: the pty is the program's controlling terminal, as a
    terminal window's is (hang_up then closes the window: the kernel sends SIGHUP, and the terminal is gone for good)"""
    def __init__(self, argv, w=80, h=24, env=None, cwd=None, ctty=False):
        import fcntl, struct, termios
        self.master, self.slave = os.openpty()
        fcntl.ioctl(self.slave, termios.TIOCSWINSZ, struct.pack('HHHH', h, w, 0, 0))
        self.before = termios.tcgetattr(self.slave)
        if ctty:   # (the new session's leader takes its stdin as the controlling terminal, then becomes the program: no preexec_fn in a process with threads)
            argv = [sys.executable, '-c', 'import fcntl, os, sys, termios; fcntl.ioctl(0, termios.TIOCSCTTY, 0); os.execv(sys.argv[1], sys.argv[1:])', *argv]
        self.p = subprocess.Popen(argv, stdin=self.slave, stdout=self.slave, stderr=self.slave, env=env, cwd=cwd, start_new_session=True, close_fds=True)
        self.vt, self.raw, self.lock = VT(w, h), [], threading.Lock()
        self.t = threading.Thread(target=self.read, daemon=True); self.t.start()

    def read(self):
        while True:
            if self.master is None: break
            try: r, _, _ = select.select([self.master], [], [], 0.1)
            except (OSError, ValueError): break   # (hang_up closed it)
            if not r:
                if self.p.poll() is not None: break
                continue
            try: b = os.read(self.master, 65536)
            except (OSError, TypeError): break   # the slave side closed (the program ended), or the master (hang_up)
            if not b: break
            s = b.decode('utf-8', 'replace')
            with self.lock: self.raw.append(s); self.vt.feed(s)

    def send(self, s):
        assert self.master is not None, 'the window was closed (hang_up)'
        os.write(self.master, s.encode() if isinstance(s, str) else s)

    def resize(self, w, h):
        """The window's new size (the program reads it before its next frame); the virtual screen starts blank at that size"""
        import fcntl, struct, termios
        with self.lock: self.vt = VT(w, h) if not self.vt.modes else self.vt.resized(w, h)
        fcntl.ioctl(self.slave, termios.TIOCSWINSZ, struct.pack('HHHH', h, w, 0, 0))

    def screen(self):
        with self.lock: return self.vt.text()

    def wait_for(self, text, timeout=30.0):
        """Waits until the screen shows text (a str, or a compiled pattern); → the screen; AssertionError with the screen on timeout"""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            s = self.screen()
            if (text.search(s) if hasattr(text, 'search') else text in s): return s
            if self.p.poll() is not None: time.sleep(0.2); s = self.screen(); break
            time.sleep(0.05)
        s = self.screen()
        if (text.search(s) if hasattr(text, 'search') else text in s): return s
        raise AssertionError(f'{text!r} not on the screen (exit {self.p.poll()}):\n{s}')

    def attrs(self):
        import termios
        return termios.tcgetattr(self.slave)

    def hang_up(self):
        """The window closes: the master side is closed (with ctty, the kernel hangs the terminal up and sends the program SIGHUP)"""
        if self.master is not None: os.close(self.master); self.master = None

    def close(self):
        if self.p.poll() is None: self.p.kill(); self.p.wait()
        self.t.join(timeout=5)
        if self.master is not None: os.close(self.master)
        os.close(self.slave)
