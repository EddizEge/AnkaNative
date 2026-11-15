package dev.nxnative.android;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.ApplicationExitInfo;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * A check of the Prepare job some minutes after its process was last seen working, for when no process of the app is left to notice that it died. JobWatch
 * (the main process) sees :pack end only while the main process lives, and under memory pressure lmkd ends the cached main process (adj 900+) before :pack
 * (a foreground service, adj 200): the phone test's low-memory kills took the background apps first. With both gone the job stood still and silent until the
 * app was opened (F2).
 * <p>
 * While a worker runs, PackService keeps one alarm set about ARM_MS ahead (arm, every TICK; an inexact, non-waking alarm: no permission, and it costs nothing
 * while it is moved on). A clean end of the worker (done, failed, a Pause) cancels it. When it fires, Android starts the app's main process for this receiver if
 * none runs, and JobDeath decides as for JobWatch: a job that should be working and whose :pack process is gone is started again (JobWatch.restart), which
 * Android refuses from the background, and then the "Interrupted" notification goes up; a job waiting for a game that still runs is checked again later, so
 * it is also caught when the game ends without its own end (a crash, a kill). Force stop removes the app's alarms, so it never fires after one.
 * <p>
 * HyperOS may refuse to start a dead app for a broadcast (its Autostart setting, as it refuses the sticky restart): then nothing changes from before.
 */
public final class JobAlarm extends BroadcastReceiver {
    private static final String TAG = "nxpack";
    /** How far ahead the alarm is set: inexact, so it fires up to about 1.75 times this after the process was last seen */
    static final long ARM_MS = 3 * 60_000;
    /** How often a working service moves it on (PackService's tick, every 10 s, calls arm; at most once a minute reaches AlarmManager) */
    static final long REARM_MS = 60_000;
    private static long armedAt = -REARM_MS;

    public JobAlarm() {}

    private static PendingIntent intent(Context c) {
        return PendingIntent.getBroadcast(c, 4, new Intent(c, JobAlarm.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The alarm set (again) ARM_MS from now; force: now, else at most once each REARM_MS in this process */
    static void arm(Context c, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - armedAt < REARM_MS) return;
        armedAt = now;
        c.getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME, now + ARM_MS, intent(c));
    }

    /** No check needed: the job ended, failed or was paused while its process lived */
    static void cancel(Context c) {
        armedAt = -REARM_MS;
        c.getSystemService(AlarmManager.class).cancel(intent(c));
    }

    @Override public void onReceive(Context c, Intent i) {
        Job j = JobWatch.remembered(c);
        if (j == null) return;
        boolean pack = packRuns(c), game = PrepareGate.gameRunning(c);
        ApplicationExitInfo exit = pack ? null : JobWatch.exitOf(c, 0);
        JobDeath.Act act = JobDeath.after(j.state(), j.reason(), pack, exit == null ? JobDeath.EXIT_UNKNOWN : exit.getReason(), game);
        boolean again = JobDeath.checkAgain(j.state(), j.reason(), pack, game);
        Log.i(TAG, "job alarm: the job " + j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason()) + (pack ? ", its process runs" : ", no process of it ("
                + JobWatch.describe(exit) + ")") + (game ? ", a game runs" : "") + ": " + act + (again ? ", checked again later" : ""));
        if (again) arm(c, true);
        if (act == JobDeath.Act.RESUME) JobWatch.restart(c, j, "its process ended (the job alarm found it)");   // i18n-keep: the log's reason
    }

    /** Whether a :pack process of this app runs (a service in it moves this alarm on itself; a cached one keeps a job that does not work) */
    static boolean packRuns(Context c) {
        List<ActivityManager.RunningAppProcessInfo> ps = c.getSystemService(ActivityManager.class).getRunningAppProcesses();
        String name = c.getPackageName() + ":pack";
        if (ps != null) for (ActivityManager.RunningAppProcessInfo p : ps) if (name.equals(p.processName)) return true;
        return false;
    }
}
