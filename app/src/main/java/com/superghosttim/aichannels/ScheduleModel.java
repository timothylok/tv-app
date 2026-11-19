package com.superghosttim.aichannels;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** One channel's schedule: items with absolute start/end (epoch ms). Parsing never throws; bad entries are skipped. */
public final class ScheduleModel {
    public static final class Item {
        public final String id, title, url;
        public final long start, end;
        Item(String id, String title, String url, long start, long end) {
            this.id = id; this.title = title; this.url = url; this.start = start; this.end = end;
        }
    }

    private final List<Item> items;
    private final String filler;   // https URL of the looping ident, or null
    private final long updated;    // when the pipeline last wrote this schedule (epoch ms), 0 = unknown
    private final int aiLeftPct;   // free Workers AI allowance left (0-100), -1 = unknown

    private ScheduleModel(List<Item> items, String filler, long updated, int aiLeftPct) {
        this.items = items; this.filler = filler; this.updated = updated; this.aiLeftPct = aiLeftPct;
    }

    public static ScheduleModel empty() { return new ScheduleModel(new ArrayList<Item>(), null, 0, -1); }

    public static boolean isHttps(String u) { return u != null && u.startsWith("https://") && u.length() > 10; }

    public static ScheduleModel parse(String json) {
        List<Item> out = new ArrayList<>();
        String filler = null;
        long updated = 0;
        int aiLeft = -1;
        try {
            JSONObject root = new JSONObject(json);
            String f = root.optString("filler", "");
            if (isHttps(f)) filler = f;
            updated = Math.max(0, root.optLong("updated", 0));
            JSONObject ai = root.optJSONObject("ai");
            if (ai != null) {
                int p = ai.optInt("left_pct", -1);
                if (p >= 0 && p <= 100) aiLeft = p;
            }
            JSONArray arr = root.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String url = o.optString("url", "");
                long start = o.optLong("start", -1), end = o.optLong("end", -1);
                if (id.isEmpty() || !isHttps(url) || start < 0 || end <= start) continue;
                out.add(new Item(id, o.optString("title", ""), url, start, end));
            }
        } catch (Exception e) {
            return empty();
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(a.start, b.start); }
        });
        return new ScheduleModel(out, filler, updated, aiLeft);
    }

    public String filler() { return filler; }

    public long updated() { return updated; }

    public int aiLeftPct() { return aiLeftPct; }

    /** The most recent item that has started by `now` (on air or already finished), or null. */
    public Item lastStartedBy(long now) {
        Item hit = null;
        for (Item it : items) if (it.start <= now) hit = it;
        return hit;
    }

    public int size() { return items.size(); }

    /** The item on air at `now`, or null (gap, or nothing scheduled). */
    public Item itemAt(long now) {
        Item hit = null;
        for (Item it : items) if (it.start <= now && now < it.end) hit = it;  // latest start wins on overlap
        return hit;
    }

    /** First item that starts after `now`, or null. */
    public Item nextAfter(long now) {
        for (Item it : items) if (it.start > now) return it;
        return null;
    }

    /** Episodes that started within the last `windowMs` (including the one on air), newest first. */
    public List<Item> recent(long now, long windowMs) {
        List<Item> out = new ArrayList<>();
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.start <= now && it.start >= now - windowMs) out.add(it);
        }
        return out;
    }

    /** Every episode that has started (all the server still keeps, about 48 h), newest first. */
    public List<Item> available(long now) { return recent(now, Long.MAX_VALUE / 2); }

    /** In a newest-first list: the next newer episode (or null). */
    public static Item newer(List<Item> newestFirst, String id) {
        for (int i = 1; i < newestFirst.size(); i++) if (newestFirst.get(i).id.equals(id)) return newestFirst.get(i - 1);
        return null;
    }

    /** In a newest-first list: the next older episode (or null). */
    public static Item older(List<Item> newestFirst, String id) {
        for (int i = 0; i < newestFirst.size() - 1; i++) if (newestFirst.get(i).id.equals(id)) return newestFirst.get(i + 1);
        return null;
    }

    /** The episode to replay after `afterId` (the next older one, wrapping to the newest), or null if the list is empty. */
    public static Item nextReplay(List<Item> newestFirst, String afterId) {
        if (newestFirst.isEmpty()) return null;
        for (int i = 0; i < newestFirst.size(); i++) {
            if (newestFirst.get(i).id.equals(afterId)) return newestFirst.get((i + 1) % newestFirst.size());
        }
        return newestFirst.get(0);
    }

    /** True when b starts within 1.5 s of a's end, so the two can be queued back to back. */
    public static boolean contiguous(Item a, Item b) {
        return a != null && b != null && Math.abs(b.start - a.end) <= 1500;
    }
}
