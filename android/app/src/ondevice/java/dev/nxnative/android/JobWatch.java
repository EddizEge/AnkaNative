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

    @Override public void onServiceConnected(ComponentName n, IBinder b) {
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
        return true;
    }

    /** The service's process ended (the service had been running in it): what job.json says decides, a moment later (the exit's record) */
    @Override public void onServiceDisconnected(ComponentName n) {
        service = null;
        int p = pid;
        boolean was = alive;
        pid = -1;
        alive = false;
        h.postDelayed(() -> died(p, was), 500);
    }

    @Override public void onBindingDied(ComponentName n) {   // the app's package changed under it: bind again
        c.unbindService(this);
        service = null;
        bind();
    }

    private void died(int p, boolean worker) {
        Job j = remembered(c);
        if (j == null) return;
        ApplicationExitInfo exit = exitOf(c, p);
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

    /** The exit record of the job's process pid (pid ≤ 0: the latest of a :pack process), null when Android has none */
    static ApplicationExitInfo exitOf(Context c, int pid) {
        List<ApplicationExitInfo> l = c.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(c.getPackageName(), Math.max(0, pid), pid > 0 ? 1 : 16);
        String pack = c.getPackageName() + ":pack";
        for (ApplicationExitInfo i : l) if (pid > 0 ? i.getPid() == pid : pack.equals(i.getProcessName())) return i;
        return null;
    }

    /** The exit for the log: its reason code, the system's description and the status (exit code or signal) */
    static String describe(ApplicationExitInfo i) {
        return i == null ? "no exit record" : "exit reason " + i.getReason() + (i.getDescription() == null ? "" : " \"" + i.getDescription() + "\"") + ", status " + i.getStatus();   // i18n-keep: a log line
    }
}
