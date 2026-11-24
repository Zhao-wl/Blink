package com.zhaowl.blink;

final class BlinkWorkScheduling {
    private BlinkWorkScheduling() {}

    static boolean shouldTrack(String caller, String device, String requested, boolean legacy) {
        return legacy || !caller.isEmpty() && (caller.equals(device) || caller.equals(requested));
    }

    static boolean keepAlive(boolean upcoming, boolean monitoring) {
        return upcoming || monitoring;
    }

    static long nextCheck(long elapsedNow) {
        return elapsedNow + 15 * 60_000L;
    }

    static boolean acceptsObservation(long savedCheck, int savedBoot, long observedCheck, int observedBoot, int currentBoot) {
        return observedCheck >= 0 && observedBoot == currentBoot
            && (savedBoot != currentBoot || observedCheck >= savedCheck);
    }

    static boolean canDeliver(boolean legacy, long revision, long blocked, int leaseBoot, int boot,
                              long elapsedExpires, long elapsedNow, long expires, long now, long due) {
        return legacy || revision > blocked && leaseBoot == boot && elapsedExpires > elapsedNow
            && expires > Math.max(now, due);
    }
}
