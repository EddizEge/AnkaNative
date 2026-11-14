#!/usr/bin/env python3
# A thread pool for the Prepare tools (shader_split.py, split_delta.py, vk_android_build.py, vk_android_delta.py) that a stop does not hold up. On the phone those
# tools run in the app's process and its Pause is a KeyboardInterrupt raised in the tool's thread (android/ondevice/dm_pack.c); PackService ends the process when the
# tool has not stopped within 20 s. The plain concurrent.futures executor, left by an exception, first runs every job already submitted: an interrupt that came
# while ex.map was still submitting (its result iterator, which cancels the jobs not started, did not exist yet) waited for the whole map. On a phone that is the
# split's look at the first bytes of every RomFS file (about 30 s of its 39-s start) or the hashing of a library's 42,854 entries.
import concurrent.futures as cf


class Pool(cf.ThreadPoolExecutor):
    """cf.ThreadPoolExecutor whose `with` block, left by an exception (an interrupt, a job that failed), cancels the jobs not started and waits only for those in
    hand; left normally it waits for every job, as the plain executor does. What the jobs compute is unchanged"""
    def __exit__(self, exc_type, exc, tb):
        self.shutdown(wait=True, cancel_futures=exc_type is not None)
        return False
