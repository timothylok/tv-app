package com.superghosttim.aichannels;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Text for the "last updated" line. Plain Java so it can be unit-tested. */
public final class StatusFormat {
    private StatusFormat() { }

    /** "19 Nov 2026, 8:04 am NZDT": New Zealand time (daylight saving included) whatever the box's zone is. */
    public static String nz(long epochMs) {
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy, h:mm a z", Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("Pacific/Auckland"));
        // am/pm case differs between JVM and Android's ICU: force lower case, keep the zone name (NZDT/NZST) as is
        String s = f.format(new Date(epochMs));
        return s.replace(" AM ", " am ").replace(" PM ", " pm ");
    }

    /** One line: "Updated 19 Nov 2026, 8:04 am NZDT | Episode 20261118-1902 | AI free left 94%". */
    public static String line(long updated, String episodeId, int aiLeftPct) {
        StringBuilder sb = new StringBuilder();
        sb.append(updated > 0 ? "Updated " + nz(updated) : "Updated: unknown");
        if (episodeId != null && !episodeId.isEmpty()) sb.append("  |  Episode ").append(episodeId);
        sb.append("  |  AI free left ").append(aiLeftPct >= 0 ? aiLeftPct + "%" : "unknown");
        return sb.toString();
    }
}
