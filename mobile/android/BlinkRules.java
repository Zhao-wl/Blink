package com.zhaowl.blink;

/** Pure reminder rules shared by the alarm, service and notification code (unit-tested without Android). */
final class BlinkRules {
    private BlinkRules() {}
    static final long MINUTE = 60_000L;

    /** Light the screen only for the first delivery of a round, never during calls or DND, and stop after two silent rounds. */
    static boolean shouldWake(boolean enabled, boolean retry, int unanswered, boolean interactive,
                              boolean interruptionsAllowed, boolean inCall) {
        return enabled && !retry && unanswered < 2 && !interactive && interruptionsAllowed && !inCall;
    }

    /**
     * A retry that expired unanswered means nobody is there. Returns the time the person is
     * considered away from (the first delivery of the round), or 0 if they are present.
     */
    static long awayFrom(long retryAt, long retryExpires, long now, boolean confirmed, long lastActive) {
        if (confirmed || now < retryExpires) return 0;
        long from = retryAt - 5 * MINUTE;
        return lastActive >= from ? 0 : from;
    }

    /** Keep the reminder session after the task is swiped away only when the person opted in. */
    static boolean keepSession(boolean persistent, boolean hasTask) {
        return persistent || hasTask;
    }

    /** During a phone call a due reminder waits; it is retried a minute later while still valid. */
    static long deferForCall(boolean inCall, long now, long expires) {
        return inCall && now + MINUTE < expires ? now + MINUTE : 0;
    }

    /** Short status-bar chip text for Live Updates (about seven characters). */
    static String chip(long remainingMs) {
        long minutes = Math.max(0, (remainingMs + MINUTE - 1) / MINUTE);
        if (minutes < 60) return minutes + "分";
        return (minutes / 60) + "时" + (minutes % 60 == 0 ? "" : (minutes % 60) + "分");
    }

    /** Progress (0..1000) of the current interval for the Live Update bar. */
    static int progress(long start, long end, long now) {
        if (end <= start) return 1000;
        return (int) Math.max(0, Math.min(1000, (now - start) * 1000 / (end - start)));
    }
}
