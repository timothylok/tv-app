package com.superghosttim.aichannels;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Groups episodes for browsing: week, then day, then morning/afternoon (AM/PM), then the episodes themselves.
 * All in New Zealand time. Plain Java so it can be unit-tested.
 */
public final class EpisodeBrowser {
    private EpisodeBrowser() { }

    public static final int WEEK = 0, DAY = 1, HALF = 2, LEAF = 3;

    public static final class Group {
        public final String label;
        public final List<ScheduleModel.Item> items = new ArrayList<>();   // newest first
        Group(String label) { this.label = label; }
    }

    private static final String[] DAYS = {"", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private static Calendar cal(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Pacific/Auckland"), Locale.ENGLISH);
        c.setTimeInMillis(ms);
        return c;
    }

    private static String dayKey(Calendar c) {
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    /** Monday 00:00 (New Zealand) of the week containing ms. */
    private static Calendar weekStart(long ms) {
        Calendar c = cal(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday = 0 ... Sunday = 6
        c.add(Calendar.DAY_OF_MONTH, -back);
        return c;
    }

    private static String dm(Calendar c) { return c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)]; }

    /** Groups newest-first items at one level (WEEK, DAY or HALF). Groups stay newest first. */
    public static List<Group> group(List<ScheduleModel.Item> newestFirst, int level, long nowMs) {
        Map<String, Group> out = new LinkedHashMap<>();
        Calendar today = cal(nowMs);
        long thisWeek = weekStart(nowMs).getTimeInMillis();
        for (ScheduleModel.Item it : newestFirst) {
            Calendar c = cal(it.start);
            String key, label;
            if (level == WEEK) {
                Calendar ws = weekStart(it.start);
                long diffWeeks = Math.round((thisWeek - ws.getTimeInMillis()) / (7.0 * 24 * 3600 * 1000));
                Calendar we = (Calendar) ws.clone();
                we.add(Calendar.DAY_OF_MONTH, 6);
                key = String.valueOf(ws.getTimeInMillis());
                label = diffWeeks == 0 ? "This week" : diffWeeks == 1 ? "Last week" : dm(ws) + " - " + dm(we);
            } else if (level == DAY) {
                key = dayKey(c);
                Calendar yest = cal(nowMs);
                yest.add(Calendar.DAY_OF_MONTH, -1);
                label = key.equals(dayKey(today)) ? "Today" : key.equals(dayKey(yest)) ? "Yesterday"
                        : DAYS[c.get(Calendar.DAY_OF_WEEK)] + " " + dm(c);
            } else {
                boolean am = c.get(Calendar.HOUR_OF_DAY) < 12;
                key = (am ? "am" : "pm");
                label = am ? "Morning (AM)" : "Afternoon / evening (PM)";
            }
            Group g = out.get(key);
            if (g == null) { g = new Group(label); out.put(key, g); }
            g.items.add(it);
        }
        return new ArrayList<>(out.values());
    }

    /**
     * The first level at or below `level` that actually offers a choice (more than one group), or LEAF when
     * every level from here on has a single group, so the viewer never taps through one-item menus.
     */
    public static int firstUsefulLevel(List<ScheduleModel.Item> items, int level, long nowMs) {
        for (int lv = level; lv <= HALF; lv++) {
            if (group(items, lv, nowMs).size() > 1) return lv;
        }
        return LEAF;
    }
}
