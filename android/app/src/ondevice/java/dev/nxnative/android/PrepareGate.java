package dev.nxnative.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * In the game's process (:game), before the host starts (GameActivity.start): when this app's Prepare job may be working (GameGate.pauseFirst), the job is
 * paused first and the game starts once it really waits. The game asks PackService with ACTION_GAME (its activity is in front, so Android lets it start the
 * service) and listens on a binding for the service's "game_ready". A service whose process has run a tool stops it and ends that process (GameGate
 * STOP_AND_END): the binding then drops, and the game asks again, which starts the service in a new process that waits for the game with little memory. The
 * game waits at most WAIT_MS, with "pausing Prepare first" on its screen; then it starts anyway. The service polls for the game's process (gameRunning) and
 * goes on by itself once it is gone, however the game ended.
 */
final class PrepareGate implements ServiceConnection {
    /**
     * How long the game waits for the job at most: the service ends its process when its tool has not stopped within 20 s (PackService.gamePause), and a new
     * process answers within about a second; the tools stop within seconds (android/SHADERS.md), so this is only reached when something is wrong
     */
    static final long WAIT_MS = 25_000;
    /** Starts of the service the game asks for: the first, and one after each process that ended to make room (a second is only a race in the service) */
    private static final int MAX_ASKS = 3;
    private static final String TAG = "nxpack";
    /** What the gate did, for the game's log (GameActivity notes it once the host runs); null when there was nothing to pause */
    static volatile String outcome;

    private final Activity a;
    private final Runnable go;
    private final String was;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
    private final long t0 = SystemClock.elapsedRealtime();
    private Messenger service;
    private boolean bound, done;
    private int asks;

    private PrepareGate(Activity a, Runnable go, String was) { this.a = a; this.go = go; this.was = was; }

    /** true: the game waits, and go runs on the main thread once the job waits or no longer works (or after WAIT_MS); false: nothing to pause, go on at once.
     *  Also a job paused by the user (or the daily limit) whose process still runs: its tool may still be stopping (GameGate.holds) */
    static boolean hold(Activity a, Runnable go) {
        outcome = null;
        Job j = job(a);
        if (j == null || !GameGate.holds(j.state(), j.reason(), packRunning(a, j.pid()))) return false;
        new PrepareGate(a, go, j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason())).begin();
        return true;
    }

    /** The job Home shows (the last started; the service runs one at a time), from its job.json; null when there is none or it does not read */
    private static Job job(Activity a) {
        File f = Job.remembered(a);
        if (f == null || !f.isFile()) return null;
        try {
            return Job.read(f);
        } catch (IOException e) {
            outcome = "the job's " + f + " did not read (" + e.getMessage() + "): nothing paused";   // i18n-keep: the game's log
            Log.w(TAG, "a game starts: " + outcome);
            return null;
        }
    }

    private void begin() {
        Log.i(TAG, "a game starts while the job is " + was + ": pausing it first");
        bound = a.bindService(new Intent(a, PackService.class), this, 0);   // 0: only while the service runs; the start below runs it
        ask();
        h.postDelayed(() -> finish("the job did not say it waits within " + WAIT_MS / 1000 + " s: the game starts anyway"), WAIT_MS);   // i18n-keep: the game's log
    }

    private void ask() {
        asks++;
        try {
            a.startForegroundService(new Intent(a, PackService.class).setAction(PackService.ACTION_GAME));
        } catch (RuntimeException e) {   // Android refused the start: the job goes on, but this game is in front and has its memory first
            finish("the job's service did not start (" + e + "): the game starts anyway");   // i18n-keep: the game's log
        }
    }

    @Override public void onServiceConnected(ComponentName n, IBinder b) {
        service = new Messenger(b);
        Message m = Message.obtain(null, PackService.MSG_REGISTER);
        m.replyTo = client;
        try { service.send(m); } catch (RemoteException e) { service = null; }
    }

    /** The service's process ended: after a tool ran in it, to give the game its memory (GameGate STOP_AND_END). Its new process waits for the game */
    @Override public void onServiceDisconnected(ComponentName n) {
        service = null;
        if (done) return;
        if (asks >= MAX_ASKS) { finish("the job's process ended " + asks + " times: the game starts"); return; }   // i18n-keep: the game's log
        Job j = job(a);
        if (j == null || !GameGate.pauseFirst(j.state(), j.reason())) {   // it stopped for good meanwhile (a Pause, done); a wait for the game still counts
            finish("the job's process ended and it no longer works (" + (j == null ? "no job" : j.state() + "/" + j.reason()) + ")");   // i18n-keep: the game's log
            return;
        }
        Log.i(TAG, "the job's process ended to make room for the game: starting its service again, to wait for the game");
        ask();
    }

    /**
     * Android took the job's service down: its process ended (to make room for this game, GameGate STOP_AND_END, where HyperOS refuses the sticky restart and
     * the service's record goes at once) or the service stopped itself. Android first told this binding onServiceDisconnected, which asked for a new process
     * when the job still waits by itself (ask), or ended the gate when it no longer works; the binding never connects again. Which of the two took the service
     * down, this binding cannot tell (the asks count onServiceDisconnected's too), so the log says what is known: the service went, the job's state, the
     * asks and the time. The rc1 phone check: every one of the four starts that stopped a tool ended here, 0.1 s before the new :pack said WAIT; the old log
     * line ("the binding to the job's service died") said none of that
     */
    @Override public void onBindingDied(ComponentName n) {
        Job j = job(a);
        String now = j == null ? "no job" : j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason());   // i18n-keep: the game's log
        double s = (SystemClock.elapsedRealtime() - t0) / 1000.0;
        finish(String.format(Locale.ROOT, "Android took the job's service down (its process ended or the service stopped): the game starts (the job %s; asked %d time(s), %.1f s)",
                now, asks, s));   // i18n-keep: the game's log
    }

    private boolean onMessage(Message m) {
        if (m.what != PackService.MSG_STATE) return false;
        android.os.Bundle d = m.getData();
        if (d.getBoolean("game_ready", false)) {
            String how = d.getBoolean("alive") ? "waits for the game" : "no longer works";   // i18n-keep: the game's log
            finish(String.format(Locale.ROOT, "the job (%s) %s; asked %d time(s), %.1f s", was, how, asks, (SystemClock.elapsedRealtime() - t0) / 1000.0));   // i18n-keep: the game's log
        }
        return true;
    }

    private void finish(String why) {
        if (done) return;
        done = true;
        h.removeCallbacksAndMessages(null);
        if (service != null) {
            Message m = Message.obtain(null, PackService.MSG_UNREGISTER);
            m.replyTo = client;
            try { service.send(m); } catch (RemoteException e) { /* gone */ }
        }
        if (bound) { a.unbindService(this); bound = false; }
        outcome = why;
        Log.i(TAG, "a game starts: " + why);
        go.run();
    }

    /**
     * The game ends (GameActivity.end: Stop, Back, a start that failed), its activity still in front: a job waiting for the game is started again
     * (EXTRA_AUTO). Its service's process polls for the game's end and goes on by itself, but that process may be gone (a low-memory kill while the game ran),
     * and on HyperOS nothing restarts it (JobDeath); a start while it runs changes nothing. Android lets this start: the activity is in front
     */
    static void afterGame(Activity a) {
        File f = Job.remembered(a);
        if (f == null || !f.isFile()) return;
        Job j;
        try {
            j = Job.read(f);
        } catch (IOException e) {
            Log.w(TAG, "the game ends: the job's " + f + " did not read (" + e.getMessage() + ")");
            return;
        }
        if (!"paused".equals(j.state()) || !GameGate.REASON.equals(j.reason())) return;
        try {
            a.startForegroundService(new Intent(a, PackService.class).setAction(PackService.ACTION_START).putExtra(PackService.EXTRA_JOB, f.getPath())
                    .putExtra(PackService.EXTRA_AUTO, true));
            Log.i(TAG, "the game ends: the job waited for it; its service is asked to go on (it waits until this process is gone)");
        } catch (RuntimeException e) {   // the job stays paused for the game: Home resumes it when it opens (Ondevice.Panel.afterDeath)
            Log.w(TAG, "the game ends: Android did not let it start the job's service (" + e + "); Home resumes it when it opens");
        }
    }

    /** Whether the job's process (":pack" with that pid; any ":pack" for pid 0 or less: a job.json of an older build) runs */
    static boolean packRunning(Context c, int pid) {
        List<ActivityManager.RunningAppProcessInfo> ps = c.getSystemService(ActivityManager.class).getRunningAppProcesses();
        String name = c.getPackageName() + ":pack";
        if (ps != null) for (ActivityManager.RunningAppProcessInfo p : ps) if (name.equals(p.processName) && (pid <= 0 || p.pid == pid)) return true;
        return false;
    }

    /** Whether a game's process (GameActivity's ":game", AndroidManifest.xml) runs: in the foreground, in the background or still starting */
    static boolean gameRunning(Context c) {
        List<ActivityManager.RunningAppProcessInfo> ps = c.getSystemService(ActivityManager.class).getRunningAppProcesses();
        String name = c.getPackageName() + ":game";
        if (ps != null) for (ActivityManager.RunningAppProcessInfo p : ps) if (name.equals(p.processName)) return true;
        return false;
    }
}
