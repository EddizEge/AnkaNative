# pack_android.py as a child process of the interface: its output (stdout and stderr as one stream, in order) read line by line on a thread, each line into the
# log file with the time since the start, and stopped on request with what lets it end cleanly and resume later:
#   POSIX: the child leads a process group of its own (start_new_session: the terminal's signals never reach it); stop() sends SIGINT to the group (the tools
#     stop as on Ctrl+C: pack_android.py's rewrite terminates its workers, shader_split.py flushes its scan cache, vk_android_build.py keeps every finished result),
#     then SIGTERM after STOP_WAIT seconds, then SIGKILL
#   Windows: the child gets a process group of its own (CREATE_NEW_PROCESS_GROUP); stop() sends it CTRL_BREAK_EVENT, then ends the tree with taskkill /T /F
# The child runs unbuffered (PYTHONUNBUFFERED, inherited by the tools it starts) and in UTF-8 (PYTHONUTF8, PYTHONIOENCODING), so its lines come as they are printed.
import os, queue, signal, subprocess, sys, threading, time

STOP_WAIT = 20.0


class Run:
    def __init__(self, argv, log_path, env=None, cwd=None):
        self.argv, self.log_path = list(map(str, argv)), log_path
        e = dict(os.environ if env is None else env, PYTHONUNBUFFERED='1', PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
        kw: dict = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}   # pyright: ignore[reportAttributeAccessIssue]
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self.log = open(log_path, 'w', encoding='utf-8', newline='\n')
        self.t0 = time.monotonic()
        self.log.write(f'# {time.strftime("%Y-%m-%d %H:%M:%S")} {" ".join(self.argv)}\n'); self.log.flush()
        self.p = subprocess.Popen(self.argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=e, cwd=cwd, **kw)
        self.q = queue.Queue()
        self.stopping = False
        self.t = threading.Thread(target=self.read, daemon=True); self.t.start()

    def read(self):
        """The reader thread: each line (CR or LF ends one) to the queue as (seconds since the start, text) and to the log; None at the end of the output"""
        buf = b''
        out = self.p.stdout
        assert out is not None
        while True:
            b = out.read1(65536) if hasattr(out, 'read1') else out.read(4096)   # pyright: ignore[reportAttributeAccessIssue]
            if not b: break
            buf += b
            parts = buf.replace(b'\r\n', b'\n').replace(b'\r', b'\n').split(b'\n')
            buf = parts.pop()
            for p in parts: self.line(p.decode('utf-8', 'replace'))
        if buf: self.line(buf.decode('utf-8', 'replace'))
        self.q.put(None)

    def line(self, s):
        t = time.monotonic() - self.t0
        self.log.write(f'[{t:9.1f}] {s}\n'); self.log.flush()
        self.q.put((t, s))

    def lines(self):
        """The lines read since the last call, [(seconds, text)], and whether the output has ended"""
        out, end = [], False
        while True:
            try: x = self.q.get_nowait()
            except queue.Empty: break
            if x is None: end = True; break
            out.append(x)
        return out, end

    def poll(self): return self.p.poll()

    def signal(self):
        """The gentle stop: SIGINT to the group, CTRL_BREAK_EVENT on Windows"""
        if os.name == 'nt': os.kill(self.p.pid, signal.CTRL_BREAK_EVENT)   # pyright: ignore[reportAttributeAccessIssue]
        else: os.killpg(self.p.pid, signal.SIGINT)

    def stop(self, wait=STOP_WAIT, on_wait=None):
        """Stops the child and every process it started: gently, then (after wait seconds) for sure. on_wait(seconds left) is called while it waits. → its exit code"""
        self.stopping = True
        if self.p.poll() is None:
            try: self.signal()
            except (ProcessLookupError, PermissionError, OSError): pass   # it ended meanwhile: nothing to stop
            end = time.monotonic() + wait
            while self.p.poll() is None and time.monotonic() < end:
                if on_wait: on_wait(end - time.monotonic())
                time.sleep(0.1)
        if self.p.poll() is None: self.kill()
        rc = self.p.wait()
        if os.name != 'nt': self.reap_group()
        return rc

    def kill(self):
        if os.name == 'nt':
            subprocess.run(['taskkill', '/T', '/F', '/PID', str(self.p.pid)], capture_output=True)
            return
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try: os.killpg(self.p.pid, sig)
            except ProcessLookupError: return   # the whole group is gone
            end = time.monotonic() + 5
            while self.p.poll() is None and time.monotonic() < end: time.sleep(0.1)
            if self.p.poll() is not None: return

    def reap_group(self):
        """Workers the child left behind in its group (a tool killed before it could end its own) get SIGKILL"""
        try: os.killpg(self.p.pid, 0)
        except (ProcessLookupError, PermissionError): return   # none left
        try: os.killpg(self.p.pid, signal.SIGKILL)
        except ProcessLookupError: return

    def close(self):
        self.t.join(timeout=5)
        try: self.log.write(f'# exit code {self.p.returncode} after {time.monotonic() - self.t0:.1f} s\n')
        finally: self.log.close()


def packer_argv(pack_android, game, version=None, out=None, J=None, j=None, shader_cache=None, shader_pkg=None, python=None):
    """The command line of the run (python -u pack_android.py <game> [options])"""
    a = [python or sys.executable, '-u', str(pack_android), str(game)]
    if version is not None: a += ['--version', str(version)]
    if out is not None: a += ['--out', str(out)]
    if J is not None: a += ['-J', str(J)]
    if j is not None: a += ['-j', str(j)]
    if shader_cache is not None: a += ['--shader-cache', str(shader_cache)]
    if shader_pkg is not None: a += ['--shader-pkg', str(shader_pkg)]
    return a
