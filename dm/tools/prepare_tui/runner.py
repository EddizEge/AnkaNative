# pack_android.py as a child process of the interface: its output (stdout and stderr as one stream, in order) read line by line on a thread, each line into the
# log file with the time since the start, and stopped on request with what lets it end cleanly and resume later:
#   POSIX: the child leads a process group of its own (start_new_session: the terminal's signals never reach it); stop() sends SIGINT to the group (the tools
#     stop as on Ctrl+C: pack_android.py's rewrite terminates its workers, shader_split.py flushes its scan cache, vk_android_build.py keeps every finished result),
#     then SIGTERM after STOP_WAIT seconds, then SIGKILL
#   Windows: the child gets a process group of its own (CREATE_NEW_PROCESS_GROUP) and a job object (win_job) that holds it and every process it starts; stop()
#     sends the group CTRL_BREAK_EVENT, which pack_android.py and its tools take as Ctrl+C (lf_newlines.break_as_interrupt), then ends the job
# The run is over when the packer has exited and its output has ended. Workers that outlive it (the packer killed, by the out-of-memory killer or by hand, while
# its tools' pools run) hold the output open: GRACE seconds after the packer exited they are ended (end_group) and the run is over all the same, failed or done by
# the packer's exit code, never by the output.
# A log that cannot be written (the drive is full) does not stop the run: the lines still come, and log_error says why the log ends early.
# The child runs unbuffered (PYTHONUNBUFFERED, inherited by the tools it starts) and in UTF-8 (PYTHONUTF8, PYTHONIOENCODING), so its lines come as they are printed.
import os, queue, signal, subprocess, sys, threading, time

STOP_WAIT = 20.0
GRACE = 2.0   # seconds the output may stay open after the packer exited (its last lines on their way), then what is left of its processes is ended


class Run:
    def __init__(self, argv, log_path, env=None, cwd=None):
        """Starts argv; OSError when its log cannot be made (the caller says so: nothing was started)"""
        self.argv, self.log_path = list(map(str, argv)), log_path
        e = dict(os.environ if env is None else env, PYTHONUNBUFFERED='1', PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
        kw: dict = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}   # pyright: ignore[reportAttributeAccessIssue]
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self.log = open(log_path, 'w', encoding='utf-8', newline='\n')
        try: self.log.write(f'# {time.strftime("%Y-%m-%d %H:%M:%S")} {" ".join(self.argv)}\n'); self.log.flush()
        except OSError: self.log.close(); raise
        self.log_error = None   # the OSError that ended the log early
        self.t0 = time.monotonic()
        try: self.p = subprocess.Popen(self.argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=e, cwd=cwd, **kw)
        except OSError: self.log.close(); raise
        self.job = win_job(self.p) if os.name == 'nt' else None
        self.q = queue.Queue()
        self.stopping, self.eof, self.exited_at = False, False, None
        self.t = threading.Thread(target=self.read, daemon=True); self.t.start()

    def read(self):
        """The reader thread: each line (CR or LF ends one) to the queue as (seconds since the start, text) and to the log; None at the end of the output, however
        the reading ends"""
        try:
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
        finally: self.q.put(None)

    def line(self, s):
        t = time.monotonic() - self.t0
        if self.log_error is None:
            try: self.log.write(f'[{t:9.1f}] {s}\n'); self.log.flush()
            except OSError as e: self.log_error = e   # (no space left, most likely): the run goes on, the screens say the log ends here
        self.q.put((t, s))

    def lines(self):
        """The lines read since the last call, [(seconds, text)], and whether the output has ended"""
        out = []
        while True:
            try: x = self.q.get_nowait()
            except queue.Empty: break
            if x is None: self.eof = True; break
            out.append(x)
        return out, self.eof

    def poll(self): return self.p.poll()

    def finished(self):
        """Whether the run is over: the packer has exited and its output ended, or it exited GRACE seconds ago (its leftover processes are then ended: their output
        is no longer the run's)"""
        if self.p.poll() is None: return False
        if self.eof: return True
        now = time.monotonic()
        if self.exited_at is None: self.exited_at = now
        if now - self.exited_at < GRACE: return False
        self.end_group()
        self.t.join(timeout=5)   # (the pipe's last holders are gone: the reader reaches the end)
        return True

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
        self.end_group()
        return rc

    def kill(self):
        if os.name == 'nt':
            if self.job is not None: self.job.terminate()
            else: subprocess.run(['taskkill', '/T', '/F', '/PID', str(self.p.pid)], capture_output=True)   # (no job object: the tree as it is now)
            return
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try: os.killpg(self.p.pid, sig)
            except ProcessLookupError: return   # the whole group is gone
            end = time.monotonic() + 5
            while self.p.poll() is None and time.monotonic() < end: time.sleep(0.1)
            if self.p.poll() is not None: return

    def end_group(self):
        """The processes the child left behind (a tool killed before it could end its own workers) are ended: SIGKILL to its group, the job ended on Windows"""
        if os.name == 'nt':
            if self.job is not None: self.job.terminate()
            return
        try: os.killpg(self.p.pid, 0)
        except (ProcessLookupError, PermissionError): return   # none left
        try: os.killpg(self.p.pid, signal.SIGKILL)
        except ProcessLookupError: return

    def close(self):
        self.t.join(timeout=5)
        if not self.t.is_alive() and self.p.stdout is not None: self.p.stdout.close()
        try:
            if self.log_error is None: self.log.write(f'# exit code {self.p.returncode} after {time.monotonic() - self.t0:.1f} s\n')
        except OSError as e: self.log_error = e
        finally:
            try: self.log.close()
            except OSError as e: self.log_error = self.log_error or e   # (its last buffered bytes: the same full drive)
            if self.job is not None: self.job.close()


class WinJob:
    """A Windows job object that ends every process in it when it is terminated or its last handle closes (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE): the packer and all
    it starts, as a process group is on POSIX. The packer is put in it right after it starts, before it has started anything (its imports and the identify step
    come first)"""
    def __init__(self, k32, handle): self.k32, self.h = k32, handle

    def terminate(self):
        if self.h: self.k32.TerminateJobObject(self.h, 1)

    def close(self):
        if self.h: self.k32.CloseHandle(self.h); self.h = None


def win_job(p):
    """p in a new job object (WinJob), or None when the console's own job does not allow one (kill() then ends the tree with taskkill)"""
    import ctypes
    from ctypes import wintypes
    k32 = ctypes.WinDLL('kernel32', use_last_error=True)   # pyright: ignore[reportAttributeAccessIssue]

    class Basic(ctypes.Structure):
        _fields_ = [('per_process_time', ctypes.c_int64), ('per_job_time', ctypes.c_int64), ('flags', wintypes.DWORD), ('min_ws', ctypes.c_size_t),
                    ('max_ws', ctypes.c_size_t), ('processes', wintypes.DWORD), ('affinity', ctypes.c_size_t), ('priority', wintypes.DWORD), ('scheduling', wintypes.DWORD)]

    class Extended(ctypes.Structure):   # JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        _fields_ = [('basic', Basic), ('io', ctypes.c_uint64 * 6), ('process_memory', ctypes.c_size_t), ('job_memory', ctypes.c_size_t),
                    ('peak_process_memory', ctypes.c_size_t), ('peak_job_memory', ctypes.c_size_t)]
    k32.CreateJobObjectW.restype = wintypes.HANDLE
    k32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    k32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    k32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    k32.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
    k32.CloseHandle.argtypes = [wintypes.HANDLE]
    h = k32.CreateJobObjectW(None, None)
    if not h: return None
    info = Extended(); info.basic.flags = 0x2000   # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    if not (k32.SetInformationJobObject(h, 9, ctypes.byref(info), ctypes.sizeof(info))   # JobObjectExtendedLimitInformation
            and k32.AssignProcessToJobObject(h, int(p._handle))):   # pyright: ignore[reportAttributeAccessIssue]
        k32.CloseHandle(h); return None
    return WinJob(k32, h)


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
