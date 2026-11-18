package com.superghosttim.aichannels;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The channel line-up (channels.json) with wrap-around up/down, like a TV. */
public final class ChannelList {
    public static final class Channel {
        public final String id, name, scheduleUrl;
        public final int number;
        Channel(String id, int number, String name, String scheduleUrl) {
            this.id = id; this.number = number; this.name = name; this.scheduleUrl = scheduleUrl;
        }
    }

    private final List<Channel> channels;

    private ChannelList(List<Channel> c) { channels = c; }

    public static ChannelList parse(String json) {
        List<Channel> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("channels");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", ""), url = o.optString("schedule", "");
                int number = o.optInt("number", -1);
                if (id.isEmpty() || number < 0 || !ScheduleModel.isHttps(url)) continue;
                out.add(new Channel(id, number, o.optString("name", id), url));
            }
        } catch (Exception e) {
            out.clear();
        }
        Collections.sort(out, new Comparator<Channel>() {
            @Override public int compare(Channel a, Channel b) { return Integer.compare(a.number, b.number); }
        });
        return new ChannelList(out);
    }

    public int size() { return channels.size(); }
    public Channel get(int i) { return channels.get(i); }

    public int indexOfId(String id) {
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(id)) return i;
        return -1;
    }

    public int indexOfNumber(int number) {
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).number == number) return i;
        return -1;
    }

    /** Index after `i`, wrapping to the first. */
    public int up(int i) { return channels.isEmpty() ? -1 : (i + 1) % channels.size(); }

    /** Index before `i`, wrapping to the last. */
    public int down(int i) { return channels.isEmpty() ? -1 : (i - 1 + channels.size()) % channels.size(); }
}
