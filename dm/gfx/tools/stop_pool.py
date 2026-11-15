#!/usr/bin/env python3
# A thread pool for the Prepare tools (shader_split.py, split_delta.py, vk_android_build.py, vk_android_delta.py) that a stop does not hold up. On the phone those
# tools run in the app's process and its Pause is a KeyboardInterrupt raised in the tool's thread (android/ondevice/dm_pack.c); PackService ends the process when the
# tool has not stopped within 20 s. The plain concurrent.futures executor, left by an exception, first runs every job already submitted: an interrupt that came
# while ex.map was still submitting (its result iterator, which cancels the jobs not started, did not exist yet) waited for the whole map. On a phone that is the
# split's look at the first bytes of every RomFS file (about 30 s of its 39-s start) or the hashing of a library's 42,854 entries. Ends: how those tools wait
# for their futures where an interrupt can reach the waiting thread, without cf.wait (whose locks an interrupt could leave taken: a stop that never ended).
import concurrent.futures as cf
import queue


class Pool(cf.ThreadPoolExecutor):
    """cf.ThreadPoolExecutor whose `with` block, left by an exception (an interrupt, a job that failed), cancels the jobs not started and waits only for those in
    hand; left normally it waits for every job, as the plain executor does. What the jobs compute is unchanged"""
    def __exit__(self, exc_type, exc, tb):
        self.shutdown(wait=True, cancel_futures=exc_type is not None)
        return False


class Ends:
    """Futures in the order they end, through a queue their done callbacks feed: the waits of a tool an interrupt can reach (vk_android_build.py collect,
    shader_split.py pooled) use it instead of cf.wait and cf.as_completed. Those take every future's lock in a Python loop (concurrent.futures' _AcquireFutures)
    and give them back in another, and an interrupt raised in the waiting thread (PyThreadState_SetAsyncExc: the phone's Pause) is delivered between two
    bytecodes, so it could land between two acquires or releases and leave locks taken that nothing gives back. The next worker to finish a future then waited for
    its lock for good, and the tool with it: on a computer in the phone's form the split hung in 2 of 15 Pauses early in its scan, and on the phone only
    PackService's 20-s fallback would have ended it. SimpleQueue.get waits in C on the queue's own lock, which no other code holds, so an interrupt reaches this
    thread within the timeout and leaves nothing taken"""
    def __init__(self):
        self.q = queue.SimpleQueue()
        self.fs = []   # every future added
        self.ended = 0   # how many of them wait() and stop() have given back

    def add(self, f):
        """f is followed from here (its done callback puts it in the queue, at once when it has ended already) → f"""
        f.add_done_callback(self.q.put)
        self.fs.append(f)
        return f

    def left(self):
        """The futures added and not given back yet"""
        return len(self.fs) - self.ended

    def wait(self, timeout):
        """The futures that end within timeout seconds, and every other that has ended by then; [] when none did"""
        try: got = [self.q.get(timeout=timeout)]
        except queue.Empty: return []
        while True:
            try: got.append(self.q.get_nowait())
            except queue.Empty: break
        self.ended += len(got)
        return got

    def stop(self):
        """After an exception: the futures not started are cancelled, and this returns once those in hand have ended (the caller has told their workers to stop).
        It looks at the futures themselves, not at what the queue gave back: the interrupt may have come between a get and its count, and that future is gone
        from the queue"""
        for f in self.fs: f.cancel()
        pending = self.fs
        while True:
            pending = [f for f in pending if not f.done()]
            if not pending: break
            try: self.q.get(timeout=0.1)   # (woken as one ends: the count is not kept from here on)
            except queue.Empty: pass
        self.ended = len(self.fs)
