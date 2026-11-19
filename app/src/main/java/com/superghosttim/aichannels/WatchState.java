package com.superghosttim.aichannels;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What the viewer has watched: resume position, duration and "watched" per episode id.
 * Plain Java (no Android classes) so it can be unit-tested; stored as a JSON string in SharedPreferences.
 */
public final class WatchState {
    public static final long END_MARGIN_MS = 20_000;   // within the last 20 s counts as watched
    public static final long MIN_RESUME_MS = 10_000;   // less than 10 s in: start again rather than "continue"

    public enum Focus { LATEST, RESUME, EPISODES }

    private static final class Entry {
        long pos, dur, touched;
        boolean done;
    }

    private final Map<String, Entry> map = new HashMap<>();

    public static WatchState parse(String json) {
        WatchState w = new WatchState();
        if (json == null || json.isEmpty()) return w;
        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject o = root.optJSONObject(id);
                if (o == null || id.isEmpty()) continue;
                Entry e = new Entry();
                e.pos = Math.max(0, o.optLong("p", 0));
                e.dur = Math.max(0, o.optLong("d", 0));
                e.touched = Math.max(0, o.optLong("t", 0));
                e.done = o.optBoolean("c", false);
                w.map.put(id, e);
            }
        } catch (Exception ignored) {
            return new WatchState();   // damaged: start fresh rather than crash
        }
        return w;
    }

    public String toJson() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Entry> m : map.entrySet()) {
                Entry e = m.getValue();
                root.put(m.getKey(), new JSONObject().put("p", e.pos).put("d", e.dur).put("t", e.touched).put("c", e.done));
            }
        } catch (Exception ignored) { }
        return root.toString();
    }

    /** Record the position reached; within the last 20 s (or at the end) marks the episode watched. */
    public void record(String id, long posMs, long durMs, long wallMs) {
        if (id == null || id.isEmpty()) return;
        Entry e = map.get(id);
        if (e == null) { e = new Entry(); map.put(id, e); }
        e.pos = Math.max(0, posMs);
        if (durMs > 0) e.dur = durMs;
        e.touched = wallMs;
        if (e.dur > 0 && e.pos >= e.dur - END_MARGIN_MS) { e.done = true; e.pos = 0; }
    }

    public void markWatched(String id, long wallMs) {
        if (id == null) return;
        Entry e = map.get(id);
        if (e == null) { e = new Entry(); map.put(id, e); }
        e.done = true;
        e.pos = 0;
        e.touched = wallMs;
    }

    public boolean started(String id) { return map.containsKey(id); }

    public boolean watched(String id) { Entry e = map.get(id); return e != null && e.done; }

    /** Where to start this episode: the saved position if partly watched, otherwise 0. */
    public long resumeAt(String id) {
        Entry e = map.get(id);
        return e == null || e.done || e.pos < MIN_RESUME_MS ? 0 : e.pos;
    }

    /** 0-100 for a partly watched episode, -1 otherwise. */
    public int percent(String id) {
        Entry e = map.get(id);
        if (e == null || e.done || e.dur <= 0 || e.pos < MIN_RESUME_MS) return -1;
        return (int) Math.min(99, e.pos * 100 / e.dur);
    }

    /** "New", "Watched", "40% watched" or "" (opened but barely started). */
    public String label(String id) {
        if (!started(id)) return "New";
        if (watched(id)) return "Watched";
        int p = percent(id);
        return p >= 0 ? p + "% watched" : "";
    }

    /** The most recently watched unfinished episode that is still available, or null. */
    public String resumeCandidate(List<ScheduleModel.Item> available) {
        String best = null;
        long t = -1;
        for (ScheduleModel.Item it : available) {
            Entry e = map.get(it.id);
            if (e != null && !e.done && e.pos >= MIN_RESUME_MS && e.touched > t) { best = it.id; t = e.touched; }
        }
        return best;
    }

    /** Remaining time of a partly watched episode in ms (0 if unknown). */
    public long remaining(String id) {
        Entry e = map.get(id);
        return e == null || e.dur <= 0 ? 0 : Math.max(0, e.dur - e.pos);
    }

    /** Home focus: Play latest if it is unplayed, else Resume if something is unfinished, else Play latest, else Episodes. */
    public Focus homeFocus(String latestId, String resumeId) {
        if (latestId != null && !started(latestId)) return Focus.LATEST;
        if (resumeId != null) return Focus.RESUME;
        if (latestId != null) return Focus.LATEST;
        return Focus.EPISODES;
    }

    /** Forget episodes the schedule no longer has (their videos are deleted after 48 h). */
    public void prune(List<ScheduleModel.Item> available) {
        if (available.isEmpty()) return;   // an empty or broken schedule must not wipe the history
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (ScheduleModel.Item it : available) keep.add(it.id);
        map.keySet().retainAll(keep);
    }

    /** "6:42" style time for the screen. */
    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }
}
