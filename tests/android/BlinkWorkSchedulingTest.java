package com.zhaowl.blink;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

public final class BlinkWorkSchedulingTest {
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final int BOOT = 7;

    public static void main(String[] args) {
        testOvernightRenewal();
        testLockedHandoff();
        testStaleObservation();
        testLegacyFallback();
        System.out.println("Android work scheduling: 4 regression cases passed");
    }

    private static void testOvernightRenewal() {
        long closeOfWork = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli();
        long nextReminder = Instant.parse("2026-11-24T01:20:00Z").toEpochMilli();
        long elapsed = 4 * HOUR;
        long expires = closeOfWork + 12 * HOUR;
        long expiresElapsed = elapsed + 12 * HOUR;
        boolean hasScheduledReminder = BlinkWorkScheduling.canDeliver(false, 4, -1, BOOT, BOOT,
            expiresElapsed, elapsed, expires, closeOfWork, nextReminder);
        check(!hasScheduledReminder, "18:00 lease must not authorize tomorrow's 09:20 reminder yet");
        boolean monitoring = BlinkWorkScheduling.shouldTrack("current", "current", "", false);
        check(BlinkWorkScheduling.keepAlive(hasScheduledReminder, monitoring),
            "locking after work must retain native maintenance even when there is no authorized reminder");

        long checkAt = elapsed;
        while (checkAt < expiresElapsed - 6 * HOUR) {
            long nextCheck = BlinkWorkScheduling.nextCheck(checkAt);
            check(nextCheck > checkAt && nextCheck <= checkAt + 15 * MINUTE,
                "the next native check must be due within 15 minutes of the previous check");
            check(nextCheck < expiresElapsed, "native checks must be scheduled before the current lease expires");
            checkAt = nextCheck;
        }
        long checkedAt = closeOfWork + (checkAt - elapsed);
        check(BlinkWorkScheduling.canDeliver(false, 4, -1, BOOT, BOOT, checkAt + 12 * HOUR, checkAt,
            checkedAt + 12 * HOUR, checkedAt, nextReminder),
            "the renewed lease must authorize the saved 09:20 reminder without opening the page");
    }

    private static void testLockedHandoff() {
        long now = Instant.parse("2026-11-23T02:00:00Z").toEpochMilli();
        long elapsed = 4 * HOUR;
        long nextReminder = now + 20 * MINUTE;
        boolean monitoring = BlinkWorkScheduling.shouldTrack("new", "old", "new", false);
        check(BlinkWorkScheduling.keepAlive(false, monitoring),
            "the incoming device must monitor while locked, before it can schedule any shared alarms");
        check(!BlinkWorkScheduling.canDeliver(false, -1, -1, BOOT, BOOT, 0, elapsed, 0, now, nextReminder),
            "an incoming device must wait for authorization before delivering");
        check(!BlinkWorkScheduling.canDeliver(false, 4, 4, BOOT, BOOT, elapsed + 12 * HOUR, elapsed,
            now + 12 * HOUR, now, nextReminder),
            "the old device must not use the blocked revision once it has released");
        check(BlinkWorkScheduling.shouldTrack("new", "new", "", false),
            "the incoming device must continue monitoring after it is confirmed as primary");
        check(BlinkWorkScheduling.canDeliver(false, 5, -1, BOOT, BOOT, elapsed + 12 * HOUR, elapsed,
            now + 12 * HOUR, now, nextReminder),
            "the incoming device must be able to schedule the saved reminder after receiving the new lease");
        check(!BlinkWorkScheduling.keepAlive(false, BlinkWorkScheduling.shouldTrack("other", "new", "", false)),
            "a device that is neither primary nor waiting must not retain a native service with no reminders");
    }

    private static void testStaleObservation() {
        check(BlinkWorkScheduling.acceptsObservation(100, BOOT, 200, BOOT, BOOT),
            "same-boot fresh observations must be accepted");
        check(!BlinkWorkScheduling.acceptsObservation(200, BOOT, 100, BOOT, BOOT),
            "a stale WebView observation must not undo a newer native renewal or handoff");
        check(!BlinkWorkScheduling.acceptsObservation(0, BOOT, 200, BOOT, BOOT + 1),
            "a response observed before reboot must not authorize the current boot");
    }

    private static void testLegacyFallback() {
        long now = Instant.parse("2026-11-23T02:00:00Z").toEpochMilli();
        long elapsed = 4 * HOUR;
        long nextReminder = now + 20 * MINUTE;
        check(BlinkWorkScheduling.canDeliver(true, -1, -1, BOOT, BOOT, 0, elapsed, 0, now, nextReminder),
            "a confirmed legacy server must permit local reminders without a shared lease");
        check(BlinkWorkScheduling.shouldTrack("local", "", "", true),
            "legacy mode must retain native checks to detect return of the lease API");
        check(!BlinkWorkScheduling.canDeliver(false, -1, -1, BOOT, BOOT, 0, elapsed, 0, now, nextReminder),
            "when the server returns to lease mode, a device with no authorization must stop sharing reminders");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
