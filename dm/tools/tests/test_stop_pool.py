"""stop_pool.Pool (dm/gfx/tools/stop_pool.py): the Prepare tools' thread pools, which an interrupt (the phone's Pause, raised in the tool's thread) does not wait out.
The plain executor's `with` exit ran every job already submitted: an interrupt that came while ex.map was still submitting waited for the whole map (the split's
first look at every RomFS file took 2 to 7 s to stop on a computer, about 30 s on a phone, where PackService ends the process after 20 s)."""
import concurrent.futures as cf
import re
import sys
import threading
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS))
from stop_pool import Pool, Ends   # noqa: E402
import ctypes   # noqa: E402
import random   # noqa: E402


def run(pool_class, n=200):
    """ex.map over n jobs of 20 ms in 2 threads, interrupted (KeyboardInterrupt) while it submits them: (jobs run, seconds the `with` block took to end)"""
    ran = []
    lock = threading.Lock()

    def job(i):
        time.sleep(0.02)
        with lock: ran.append(i)
        return i

    def items():
        for i in range(n):
            if i == n // 2: raise KeyboardInterrupt   # as the interrupt arrives in the submitting thread
            yield i

    t0 = time.monotonic()
    try:
        with pool_class(2) as ex: list(ex.map(job, items()))
    except KeyboardInterrupt: pass
    return len(ran), time.monotonic() - t0


class Stop(unittest.TestCase):
    def test_interrupt_while_submitting(self):
        plain, _ = run(cf.ThreadPoolExecutor)
        self.assertEqual(plain, 100)   # what the plain executor does: every job submitted runs first (1 s here)
        ran, secs = run(Pool)
        self.assertLessEqual(ran, 4)   # only the jobs in hand (2 threads)
        self.assertLess(secs, 0.5)

    def test_same_results(self):
        with Pool(4) as ex: got = list(ex.map(lambda i: i * i, range(1000)))
        self.assertEqual(got, [i * i for i in range(1000)])

    def test_failure_cancels_the_rest(self):
        ran = []

        def job(i):
            if i == 3: raise ValueError('bad')
            time.sleep(0.01); ran.append(i)
            return i

        with self.assertRaises(ValueError):
            with Pool(2) as ex: list(ex.map(job, range(500)))
        self.assertLess(len(ran), 50)

    def test_ends_order_and_stop(self):
        ends = Ends()
        with Pool(3) as ex:
            for i in range(50): ends.add(ex.submit(lambda i=i: time.sleep(0.002) or i))
            got = []
            while ends.left(): got += [f.result() for f in ends.wait(0.5)]
        self.assertEqual(sorted(got), list(range(50)))
        self.assertEqual(ends.wait(0.01), [])
        gate = threading.Event()
        ends = Ends()
        with Pool(2) as ex:
            fs = [ends.add(ex.submit(gate.wait, 5)) for _ in range(20)]
            time.sleep(0.05)
            threading.Timer(0.2, gate.set).start()
            ends.stop()   # the 2 in hand end, the 18 not started are cancelled
            self.assertTrue(all(f.done() for f in fs))
        self.assertEqual(sum(f.cancelled() for f in fs), 18)
        self.assertEqual(ends.left(), 0)

    def test_interrupt_in_the_wait(self):
        """The phone's Pause raises KeyboardInterrupt in the waiting thread (PyThreadState_SetAsyncExc, android/ondevice/dm_pack.c) at any bytecode. Over many
        futures cf.wait spent most of its time in the Python loops that take and give back every future's lock, and an interrupt landing there left locks taken: a
        worker finishing a future waited for its lock for good (the split hung in 2 of 15 early stops on a computer). With Ends, every trial ends, and no future's
        lock is left taken"""
        rnd = random.Random(7)
        for trial in range(12):
            ends, out = Ends(), []
            ex = cf.ThreadPoolExecutor(6)

            def run():
                try:
                    for _ in range(20000): ends.add(ex.submit(time.sleep, rnd.uniform(0.0, 0.0006)))
                    while ends.left(): [f.result() for f in ends.wait(0.5)]
                    out.append('done')
                except KeyboardInterrupt:
                    ends.stop(); out.append('interrupted')

            th = threading.Thread(target=run, daemon=True)
            th.start()
            time.sleep(rnd.uniform(0.05, 1.5))
            ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(th.ident), ctypes.py_object(KeyboardInterrupt))
            th.join(10)
            self.assertFalse(th.is_alive(), f'trial {trial}: the wait did not end within 10 s of the interrupt')
            ex.shutdown(wait=True)
            self.assertTrue(out, f'trial {trial}')
            for f in ends.fs:
                self.assertTrue(f._condition.acquire(blocking=False), f'trial {trial}: a future\'s lock left taken')
                f._condition.release()

    def test_tools_use_it(self):
        """Every thread pool of the tools Prepare runs on the phone (shader_split.py, split_delta.py, vk_android_build.py, vk_android_delta.py) is a Pool, or
        stops its workers itself (vk_android_build.py collect, shader_split.py InterpPool)"""
        for name in ('shader_split.py', 'split_delta.py', 'vk_android_build.py', 'vk_android_delta.py'):
            s = (TOOLS / name).read_text(encoding='utf-8')
            plain = [l for l in s.splitlines() if re.search(r'with cf\.ThreadPoolExecutor\(', l) and 'collect(' not in l]
            self.assertEqual(plain, [], name)
            self.assertIn('from stop_pool import Pool', s, name)
            self.assertNotRegex(s, r'cf\.wait\(|as_completed\(', f'{name}: a wait an interrupt can hang (stop_pool.Ends)')


if __name__ == '__main__':
    unittest.main()
