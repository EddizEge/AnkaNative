package dev.nxnative.android;

import java.io.IOException;

/**
 * An IOException whose message is a {@link Msg} (docs/i18n.md): {@link #getMessage} is English (logs, the job's error in job.json, reports, and every test that
 * reads it), {@link #getLocalizedMessage} is the user's language, for the screen. A screen shows e.getLocalizedMessage() of any exception (for other exceptions it
 * is their message). toString stays English, so a stack trace in a log is English too.
 */
final class LocalizedIOException extends IOException {
    private static final long serialVersionUID = 1L;
    /** The message; transient: the launcher never serializes exceptions, and a Msg's arguments need not be serializable */
    final transient Msg msg;

    LocalizedIOException(String key, Object... args) { this(new Msg(key, args), null); }

    LocalizedIOException(Msg msg, Throwable cause) {
        super(cause);
        this.msg = msg;
    }

    @Override public String getMessage() { return msg == null ? super.getMessage() : msg.english(); }

    @Override public String getLocalizedMessage() { return msg == null ? super.getLocalizedMessage() : msg.text(); }

    @Override public String toString() { return getClass().getName() + ": " + getMessage(); }
}
