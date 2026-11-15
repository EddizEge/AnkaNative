package dev.nxnative.android;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
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

/**
 * In the app's main process (the launcher's): watches the Prepare job's process (:pack) through a binding of PackService (flag 0: it never starts or holds the
 * service) for as long as this process lives, and when that process ends while the job should be working (JobDeath), starts the job again, which Android allows
 * while the app has a visible activity (or one of its exemptions holds). When Android refuses (the app in the background: ForegroundServiceStartNotAllowedException
 * on Android 12+), or the service is not back with its worker within VERIFY_MS, it posts a notification in place of the job's: "Interrupted: …", which opens the
 * app and resumes the job (Ondevice "ondevice_resume"), with a Resume action of its own. So the job never just vanishes with its notification, as it did on
 * HyperOS, which refuses the service's sticky restart (JobDeath). Nothing here can help when no process of the app lives: the service's alarm (JobAlarm) then
 * starts this process for a check some minutes later, and Home resumes it when it opens (Ondevice.Panel.afterDeath).
 */
final class JobWatch implements ServiceConnection {
    private static final String TAG = "nxpack";
    /**
     * How long a start the app made by itself has to come back with the job's worker before the user is told: a new :pack process answered in 0.7-1 s on the
     * POCO F8 Pro (the phone test's shader update started in 0.7-1 s), and its worker exists from the start (PackService.begin), before anything loads the packer
     */
    static final long VERIFY_MS = 10_000;
    /** When this process last started the job by itself (the watch or Home's open; elapsedRealtime): one automatic start at a time (JobDeath.mayStart) */
    static long lastAuto = -JobDeath.MIN_GAP_MS;
    private static JobWatch watch;

    private final Context c;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
    private Messenger service;
    /** The service's process id and whether its worker runs the job, as it last said (PackService MSG_STATE "pid", "alive") */
    private int pid = -1;
    private boolean alive;
    /**
     * The death link on the service's binder, which lives in the job's process: binderDied is the process's end. onServiceDisconnected is not: Android calls
     * it also when the service stops cleanly while its process lives (ActiveServices.bringDownServiceLocked tells every binding connected(null, dead), which
     * LoadedApk turns into onServiceDisconnected and then onBindingDied), and in the phone check of rc1 the watch took each such stop for a death: "the job's
     * process N ended (no exit record)" after all 23 Pauses and 3 finished jobs, 0.5 s after each stop, with the process still working afterwards
     */
    private Death link;
    /** The process whose binder died and whose end died() decides in a moment (-1: none) */
    private int deciding = -1;
    private final Runnable verify = this::verify;

    private JobWatch(Context c) { this.c = c; }

    /** Starts watching, once a process (Home's Panel calls it when it is made) */
    static void start(Context c) {
        if (watch != null) return;
        watch = new JobWatch(c.getApplicationContext());
        watch.bind();
    }

    private void bind() {
        if (!c.bindService(new Intent(c, PackService.class), this, 0)) Log.w(TAG, "job watch: Android did not let the launcher bind the Prepare service");
    }

    /** One link to the binder of one service instance: the process it lives in, as the service last said it, and whether its worker ran then */
    private final class Death implements IBinder.DeathRecipient {
        final IBinder b;
        int pid = -1;
        boolean worker;
        /** When the link was made (wall clock, ms): its process lived then, so its exit record is not older (a pid used again later is another process) */
        final long since = System.currentTimeMillis();

        Death(IBinder b) { this.b = b; }

        @Override public void binderDied() { h.post(() -> ended(this)); }   // a binder thread: decided on the main thread
    }

    @Override public void onServiceConnected(ComponentName n, IBinder b) {
        // a new service instance (in the same process after a clean stop, or in a new one): its binder is the one to watch; the old one's process, if it is
        // another, was already reported (binderDied), and if it is this one, its death is this binder's too
        if (link != null && link.b != b) link.b.unlinkToDeath(link, 0);
        if (link == null || link.b != b) {
            Death d = new Death(b);
            try {
                b.linkToDeath(d, 0);
                link = d;
            } catch (RemoteException e) {   // the process died between the connection and here: the same as its binderDied
                link = null;
                h.post(() -> ended(d));
            }
        }
        service = new Messenger(b);
        Message m = Message.obtain(null, PackService.MSG_REGISTER);
        m.replyTo = client;
        try { service.send(m); } catch (RemoteException e) { service = null; }
    }

    private boolean onMessage(Message m) {
        if (m.what != PackService.MSG_STATE) return false;
        Bundle d = m.getData();
        pid = d.getInt("pid", pid);
        alive = d.getBoolean("alive");
        if (link != null) {
            link.pid = pid;
            link.worker = alive;
        }
        return true;
    }

    /**
     * The service went: a clean stop (its process lives on, cached) or its process's end. Only the binder's death (ended) says which, so nothing is decided
     * here; the link stays on the old binder, whose death is still the process's
     */
    @Override public void onServiceDisconnected(ComponentName n) {
        service = null;
        pid = -1;
        alive = false;
    }

    /** The binding is dead (the service was taken down, cleanly or with its process, or the app's package changed): bind again for its next start */
    @Override public void onBindingDied(ComponentName n) {
        c.unbindService(this);
        service = null;
        bind();
    }

    /** The job's process really ended (binderDied of the service's binder): what job.json says decides, a moment later (the exit's record) */
    private void ended(Death d) {
        if (link == d) link = null;
        // the process the decision is about: as the service said, else (it died before its first MSG_STATE) the one job.json names, so Home leaves it to
        // this watch (decides) instead of deciding about the same death too
        Job j = d.pid > 0 ? null : remembered(c);
        int p = d.pid > 0 ? d.pid : j != null ? j.pid() : -1;
        deciding = p;
        h.postDelayed(() -> died(p, d.pid > 0, d.since, d.worker), 500);
    }

    /** p: the process that ended (-1: unknown); said: the service said it (else job.json's); since: when the link to it was made */
    private void died(int p, boolean said, long since, boolean worker) {
        if (deciding == p) deciding = -1;
        Job j = remembered(c);
        if (j == null) return;
        // the service's own pid: its record not older than the link; else the job's last process (JobDeath.fits with job.json's pid and start)
        ApplicationExitInfo exit = said ? exitOf(c, p, since) : exitOf(c, j);
        JobDeath.Act act = JobDeath.after(j.state(), j.reason(), false, exit == null ? JobDeath.EXIT_UNKNOWN : exit.getReason(), PrepareGate.gameRunning(c));
        Log.i(TAG, "the job's process " + (p > 0 ? p + " " : "") + "ended" + (worker ? " while its worker ran" : "") + " (" + describe(exit) + "), the job "
                + j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason()) + ": " + act);
        if (act == JobDeath.Act.RESUME) restart(c, j, "its process ended");   // i18n-keep: the log's reason
        else if (JobDeath.checkAgain(j.state(), j.reason(), false, PrepareGate.gameRunning(c))) JobAlarm.arm(c, true);   // a game that ends without its own end
    }

    /**
     * Starts the job again by itself (the job should be working and nothing runs it): Android may refuse from the background, and then, or when the service is
     * not back with its worker within VERIFY_MS, or when it ended again within JobDeath.MIN_GAP_MS of the last automatic start, the stopped notification goes up
     */
    static void restart(Context c, Job j, String why) {
        if (Job.onlyShown(c, j)) { Log.i(TAG, "the job: " + why + ", but the user asked only to see it (Show the job): not started"); return; }
        long now = SystemClock.elapsedRealtime();
        if (!JobDeath.mayStart(now, lastAuto)) {   // it ended again right after an automatic start: no loop, the user is told
            Log.w(TAG, "the job: " + why + ", " + (now - lastAuto) + " ms after an automatic start: the stopped notification instead");
            stopped(c, j);
            return;
        }
        lastAuto = now;
        try {
            c.startForegroundService(new Intent(c, PackService.class).setAction(PackService.ACTION_START).putExtra(PackService.EXTRA_JOB, j.file.getPath())
                    .putExtra(PackService.EXTRA_AUTO, true));
            Log.i(TAG, "the job: " + why + ": starting it again");
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException (Android 12+, the app in the background), or another refusal
            Log.w(TAG, "the job: " + why + ": Android did not let the app start it again (" + e + "): the stopped notification instead");
            stopped(c, j);
            return;
        }
        if (watch != null) {
            watch.h.removeCallbacks(watch.verify);
            watch.h.postDelayed(watch.verify, VERIFY_MS);
        }
    }

    /** VERIFY_MS after an automatic start: the job's worker runs again, else the user is told */
    private void verify() {
        if (alive) return;
        Job j = remembered(c);
        if (j == null || !JobDeath.notBack(j.state(), j.reason(), alive)) return;
        Log.w(TAG, "the job did not come back within " + VERIFY_MS / 1000 + " s of its automatic start (" + j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason())
                + "): the stopped notification");
        stopped(c, j);
    }

    /** The notification in place of the job's: Prepare stopped, a tap opens the app and resumes it; its Resume action resumes it without opening the app */
    static void stopped(Context c, Job j) {
        Context t = Lang.wrap(c);   // Android 12: the language picked in the app's settings (13+ applies it itself)
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        PackService.channel(t, nm);
        String token = java.util.UUID.randomUUID().toString();
        Prefs.open(c, "ondevice").edit().putString("resume_token", token).apply();
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Ondevice.EXTRA_RESUME, j.file.getPath()).putExtra(Ondevice.EXTRA_TOKEN, token);
        Intent resume = new Intent(c, PackService.class).setAction(PackService.ACTION_START).putExtra(PackService.EXTRA_JOB, j.file.getPath());
        Notification n = new Notification.Builder(c, PackService.CHANNEL).setSmallIcon(R.drawable.anka_notify).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true)
                .setContentTitle(t.getString(R.string.prepare_note_stopped, j.name()))
                .setContentText(t.getString(R.string.prepare_note_stopped_text, j.stepMsg().text()))
                .setContentIntent(PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, t.getString(R.string.prepare_button_resume),
                        PendingIntent.getForegroundService(c, 2, resume, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .build();
        nm.notify(PackService.NOTE, n);
    }

    /** The job Home shows, from its job.json; null when there is none or it does not read */
    static Job remembered(Context c) {
        File f = Job.remembered(c);
        if (f == null || !f.isFile()) return null;
        try {
            return Job.read(f);
        } catch (IOException e) {
            Log.w(TAG, "job watch: " + e.getMessage());
            return null;
        }
    }

    /**
     * The exit record of the process pid (one this watch saw end: its binder died), not older than since (when the watch linked to it: a pid used again later
     * is another process, whose own record may not be written yet), null when Android has none (yet)
     */
    static ApplicationExitInfo exitOf(Context c, int pid, long since) {
        for (ApplicationExitInfo i : c.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(c.getPackageName(), pid, 1))
            if (JobDeath.fits(i.getPid(), i.getTimestamp(), pid, since, 0)) return i;
        return null;
    }

    /**
     * The exit record of job j's last process (JobDeath.fits: its pid and not older than its start, or for an older job.json not older than its last write),
     * null when Android has none: never an older process's (Home, the job alarm and a game's start, which did not see the process end)
     */
    static ApplicationExitInfo exitOf(Context c, Job j) {
        int pid = j.pid();
        String pack = c.getPackageName() + ":pack";
        for (ApplicationExitInfo i : c.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(c.getPackageName(), Math.max(0, pid), pid > 0 ? 1 : 16))
            if (pack.equals(i.getProcessName()) && JobDeath.fits(i.getPid(), i.getTimestamp(), pid, j.pidStart(), j.updated())) return i;
        return null;
    }

    /**
     * Whether this watch decides about the end of process pid (one that ran the job): it is bound to its service (the link's pid), or saw its binder die and
     * decides in a moment (deciding). Then Home leaves that end to it (Ondevice.Panel.afterDeath): one decision for one death. Without that Home, 0.5 s after
     * a kill -9, had no record of it yet and the watch would then have taken Home's start for a loop
     */
    static boolean decides(int pid) {
        JobWatch w = watch;
        return w != null && pid > 0 && (w.link != null && w.link.pid == pid || w.deciding == pid);
    }

    /** The exit for the log: its reason code, the system's description and the status (exit code or signal) */
    static String describe(ApplicationExitInfo i) {
        return i == null ? "no exit record" : "exit reason " + i.getReason() + (i.getDescription() == null ? "" : " \"" + i.getDescription() + "\"") + ", status " + i.getStatus();   // i18n-keep: a log line
    }
}
