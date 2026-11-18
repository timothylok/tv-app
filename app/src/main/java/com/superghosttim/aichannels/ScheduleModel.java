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

    private ScheduleModel(List<Item> items, String filler) { this.items = items; this.filler = filler; }

    public static ScheduleModel empty() { return new ScheduleModel(new ArrayList<Item>(), null); }

    public static boolean isHttps(String u) { return u != null && u.startsWith("https://") && u.length() > 10; }

    public static ScheduleModel parse(String json) {
        List<Item> out = new ArrayList<>();
        String filler = null;
        try {
            JSONObject root = new JSONObject(json);
            String f = root.optString("filler", "");
            if (isHttps(f)) filler = f;
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
        return new ScheduleModel(out, filler);
    }

    public String filler() { return filler; }

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

    /** True when b starts within 1.5 s of a's end, so the two can be queued back to back. */
    public static boolean contiguous(Item a, Item b) {
        return a != null && b != null && Math.abs(b.start - a.end) <= 1500;
    }
}
