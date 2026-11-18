package com.superghosttim.aichannels;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** "Now" according to the server (HTTP Date header), not the box clock, so every box shows the same moment. */
public final class ServerClock {
    private volatile long offsetMs = 0;
    private volatile boolean synced = false;

    /** Parse an RFC 1123 Date header (e.g. "Wed, 18 Nov 2026 07:49:54 GMT") to epoch ms, or -1 if unusable. */
    public static long parseDate(String header) {
        if (header == null) return -1;
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            return f.parse(header.trim()).getTime();
        } catch (ParseException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * @param dateHeader the Date header
     * @param ageSeconds the Age header (seconds the response sat in a cache) or 0
     * @param sentLocal  box clock when the request started
     * @param recvLocal  box clock when the headers arrived
     * @return true if the clock was updated
     */
    public boolean update(String dateHeader, long ageSeconds, long sentLocal, long recvLocal) {
        long d = parseDate(dateHeader);
        if (d < 0 || recvLocal < sentLocal) return false;
        // Date has 1 s resolution (true time is within the second after it); the response took about half the round trip.
        long serverNow = d + Math.max(0, ageSeconds) * 1000 + 500 + (recvLocal - sentLocal) / 2;
        offsetMs = serverNow - recvLocal;
        synced = true;
        return true;
    }

    public boolean isSynced() { return synced; }

    public long now(long localNowMs) { return localNowMs + offsetMs; }

    public long offsetMs() { return offsetMs; }
}
