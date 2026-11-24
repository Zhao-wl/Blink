package com.zhaowl.blink;

public final class BlinkRulesTest {
    private static final long M = 60_000L;

    public static void main(String[] args) {
        // Wake only on the first delivery, outside calls/DND, until two rounds go unanswered.
        check(BlinkRules.shouldWake(true, false, 0, false, true, false), "first delivery wakes");
        check(!BlinkRules.shouldWake(true, true, 0, false, true, false), "retry never wakes");
        check(!BlinkRules.shouldWake(true, false, 2, false, true, false), "two silent rounds stop waking");
        check(!BlinkRules.shouldWake(true, false, 0, false, true, true), "calls never wake");
        check(!BlinkRules.shouldWake(true, false, 0, false, false, false), "DND never wakes");
        check(!BlinkRules.shouldWake(false, false, 0, false, true, false), "opt-in only");

        // An expired, unconfirmed retry means away from the first delivery.
        long retryAt = 100 * M, expires = 105 * M;
        check(BlinkRules.awayFrom(retryAt, expires, 104 * M, false, 0) == 0, "retry still valid");
        check(BlinkRules.awayFrom(retryAt, expires, 106 * M, false, 0) == 95 * M, "away from first delivery");
        check(BlinkRules.awayFrom(retryAt, expires, 106 * M, true, 0) == 0, "confirmed is present");
        check(BlinkRules.awayFrom(retryAt, expires, 106 * M, false, 97 * M) == 0, "unlocking after delivery is presence");

        check(!BlinkRules.keepSession(false, false), "swiping away ends the default session");
        check(BlinkRules.keepSession(true, false), "opt-in keeps reminders after swipe");

        check(BlinkRules.deferForCall(true, 0, 5 * M) == M, "call defers a minute");
        check(BlinkRules.deferForCall(true, 0, M / 2) == 0, "expired during call is dropped");
        check(BlinkRules.deferForCall(false, 0, 5 * M) == 0, "no call no defer");

        check(BlinkRules.chip(8 * M - 1000).equals("8分"), "chip minutes");
        check(BlinkRules.chip(90 * M).equals("1时30分"), "chip hours");
        check(BlinkRules.progress(0, 100, 50) == 500, "progress half");
        System.out.println("Android reminder rules: 17 cases passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
