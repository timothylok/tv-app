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
        TimeZone nz = TimeZone.getTimeZone("Pacific/Auckland");
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH);
        f.setTimeZone(nz);
        // Android's ICU prints "GMT+13:00" and the JVM "AM": build the zone name and am/pm case ourselves
        String s = f.format(new Date(epochMs)).replace(" AM", " am").replace(" PM", " pm");
        return s + (nz.inDaylightTime(new Date(epochMs)) ? " NZDT" : " NZST");
    }

    /** "19 Nov, 2:21 pm" in New Zealand time, for the episode list. */
    public static String nzShort(long epochMs) {
        SimpleDateFormat f = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("Pacific/Auckland"));
        return f.format(new Date(epochMs)).replace(" AM", " am").replace(" PM", " pm");
    }

    /** "Today 8:10 am", "Yesterday 2:21 pm" or "18 Nov 10:40 pm", in New Zealand time. */
    public static String day(long epochMs, long nowMs) {
        TimeZone nz = TimeZone.getTimeZone("Pacific/Auckland");
        SimpleDateFormat d = new SimpleDateFormat("yyyyMMdd", Locale.ENGLISH);
        d.setTimeZone(nz);
        SimpleDateFormat t = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        t.setTimeZone(nz);
        String time = t.format(new Date(epochMs)).replace("AM", "am").replace("PM", "pm");
        String day = d.format(new Date(epochMs));
        if (day.equals(d.format(new Date(nowMs)))) return "Today " + time;
        if (day.equals(d.format(new Date(nowMs - 24L * 3600 * 1000)))) return "Yesterday " + time;
        SimpleDateFormat dm = new SimpleDateFormat("d MMM", Locale.ENGLISH);
        dm.setTimeZone(nz);
        return dm.format(new Date(epochMs)) + " " + time;
    }

    /** "2 min" (at least 1). */
    public static String minutes(long durMs) { return Math.max(1, Math.round(durMs / 60000.0)) + " min"; }

    /** The always-on top line: "Episode 20261118-1902  |  Updated 19 Nov 2026, 8:04 am NZDT  |  AI free left 94%". */
    public static String topLine(long updated, String episodeId, int aiLeftPct) {
        StringBuilder sb = new StringBuilder();
        if (episodeId != null && !episodeId.isEmpty()) sb.append("Episode ").append(episodeId).append("  |  ");
        sb.append(updated > 0 ? "Updated " + nz(updated) : "Updated: unknown");
        sb.append("  |  AI free left ").append(aiLeftPct >= 0 ? aiLeftPct + "%" : "unknown");
        return sb.toString();
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
