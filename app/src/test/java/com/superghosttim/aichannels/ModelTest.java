package com.superghosttim.aichannels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModelTest {
    private static final String SCHED = "{\"channel\":\"ai-news\",\"filler\":\"https://x.test/filler.mp4\",\"items\":["
            + "{\"id\":\"b\",\"title\":\"B\",\"url\":\"https://x.test/b.mp4\",\"start\":2000,\"end\":3000},"
            + "{\"id\":\"a\",\"title\":\"A\",\"url\":\"https://x.test/a.mp4\",\"start\":1000,\"end\":2000},"
            + "{\"id\":\"bad1\",\"url\":\"http://x.test/c.mp4\",\"start\":5000,\"end\":6000},"
            + "{\"id\":\"bad2\",\"url\":\"https://x.test/d.mp4\",\"start\":7000,\"end\":7000},"
            + "{\"title\":\"noid\",\"url\":\"https://x.test/e.mp4\",\"start\":8000,\"end\":9000},"
            + "\"junk\"]}";

    @Test public void scheduleSkipsBadItemsAndSorts() {
        ScheduleModel s = ScheduleModel.parse(SCHED);
        assertEquals(2, s.size());
        assertEquals("https://x.test/filler.mp4", s.filler());
        assertEquals("a", s.itemAt(1000).id);
    }

    @Test public void itemAtBoundaries() {
        ScheduleModel s = ScheduleModel.parse(SCHED);
        assertNull(s.itemAt(999));            // before everything
        assertEquals("a", s.itemAt(1999).id);
        assertEquals("b", s.itemAt(2000).id); // end is exclusive, next start inclusive
        assertNull(s.itemAt(3000));           // past the end
        assertEquals("b", s.nextAfter(1500).id);
        assertNull(s.nextAfter(2000));
    }

    @Test public void contiguousWithin1500ms() {
        ScheduleModel s = ScheduleModel.parse(SCHED);
        assertTrue(ScheduleModel.contiguous(s.itemAt(1500), s.itemAt(2500)));
        assertFalse(ScheduleModel.contiguous(null, s.itemAt(2500)));
    }

    @Test public void brokenScheduleIsEmptyNotACrash() {
        assertEquals(0, ScheduleModel.parse("not json").size());
        assertEquals(0, ScheduleModel.parse("{}").size());
        assertNull(ScheduleModel.parse("{\"filler\":\"http://insecure\"}").filler());
    }

    @Test public void channelsWrapAndLookup() {
        ChannelList c = ChannelList.parse("{\"channels\":["
                + "{\"id\":\"two\",\"number\":2,\"name\":\"Two\",\"schedule\":\"https://x.test/2.json\"},"
                + "{\"id\":\"one\",\"number\":1,\"name\":\"One\",\"schedule\":\"https://x.test/1.json\"},"
                + "{\"id\":\"bad\",\"number\":3,\"schedule\":\"http://x.test/3.json\"}]}");
        assertEquals(2, c.size());
        assertEquals("one", c.get(0).id);               // sorted by number
        assertEquals(1, c.up(0));
        assertEquals(0, c.up(1));                       // wraps
        assertEquals(1, c.down(0));                     // wraps
        assertEquals(1, c.indexOfNumber(2));
        assertEquals(-1, c.indexOfNumber(9));
        assertEquals(0, c.indexOfId("one"));
        assertEquals(-1, ChannelList.parse("{}").up(0));
        assertEquals(0, ChannelList.parse("garbage").size());
    }

    @Test public void clockFollowsServerNotBox() {
        ServerClock k = new ServerClock();
        assertFalse(k.isSynced());
        assertEquals(5000, k.now(5000));                // unsynced: box clock
        long server = ServerClock.parseDate("Wed, 18 Nov 2026 07:49:54 GMT");
        assertEquals(1794988194000L, server);
        // box clock is 10 min fast; request took 200 ms
        long box = server + 600_000;
        assertTrue(k.update("Wed, 18 Nov 2026 07:49:54 GMT", 0, box - 200, box));
        long now = k.now(box);
        assertEquals(server + 500 + 100, now);          // +0.5 s Date resolution, +half the round trip
        assertEquals(now + 1000, k.now(box + 1000));
    }

    @Test public void clockRejectsGarbageAndAddsAge() {
        ServerClock k = new ServerClock();
        assertFalse(k.update(null, 0, 0, 10));
        assertFalse(k.update("yesterday", 0, 0, 10));
        assertFalse(k.isSynced());
        assertEquals(-1, ServerClock.parseDate("nope"));
        assertTrue(k.update("Wed, 18 Nov 2026 07:49:54 GMT", 30, 1000, 1000));
        assertEquals(ServerClock.parseDate("Wed, 18 Nov 2026 07:49:54 GMT") + 30_500, k.now(1000));
        assertNotNull(k);
    }

    @Test public void statusFieldsParseAndDefault() {
        ScheduleModel s = ScheduleModel.parse("{\"updated\":1795028655550,\"ai\":{\"left_pct\":94},\"items\":["
                + "{\"id\":\"a\",\"url\":\"https://x.test/a.mp4\",\"start\":1000,\"end\":2000}]}");
        assertEquals(1795028655550L, s.updated());
        assertEquals(94, s.aiLeftPct());
        assertEquals("a", s.lastStartedBy(5000).id);
        assertNull(s.lastStartedBy(999));
        ScheduleModel old = ScheduleModel.parse(SCHED);   // old schedules have neither field
        assertEquals(0, old.updated());
        assertEquals(-1, old.aiLeftPct());
        assertEquals(-1, ScheduleModel.parse("{\"ai\":{\"left_pct\":250},\"items\":[]}").aiLeftPct());
    }

    @Test public void recentEpisodesAreNewestFirstWithin24h() {
        long h = 3600_000L;
        ScheduleModel s = ScheduleModel.parse("{\"items\":["
                + "{\"id\":\"old\",\"url\":\"https://x.test/o.mp4\",\"start\":" + 0 + ",\"end\":" + 1000 + "},"
                + "{\"id\":\"a\",\"url\":\"https://x.test/a.mp4\",\"start\":" + 30 * h + ",\"end\":" + (30 * h + 1000) + "},"
                + "{\"id\":\"b\",\"url\":\"https://x.test/b.mp4\",\"start\":" + 40 * h + ",\"end\":" + (40 * h + 1000) + "},"
                + "{\"id\":\"future\",\"url\":\"https://x.test/f.mp4\",\"start\":" + 60 * h + ",\"end\":" + (60 * h + 1000) + "}]}");
        java.util.List<ScheduleModel.Item> r = s.recent(50 * h, 24 * h);
        assertEquals(2, r.size());                  // 'old' is over 24 h ago, 'future' has not started
        assertEquals("b", r.get(0).id);
        assertEquals("a", r.get(1).id);
        assertEquals("a", ScheduleModel.nextReplay(r, "b").id);   // next older
        assertEquals("b", ScheduleModel.nextReplay(r, "a").id);   // wraps to the newest
        assertEquals("b", ScheduleModel.nextReplay(r, "gone").id);
        assertNull(ScheduleModel.nextReplay(new java.util.ArrayList<ScheduleModel.Item>(), "a"));
        assertEquals("19 Nov, 2:21 pm", StatusFormat.nzShort(1795051289000L));
    }

    @Test public void watchStateTracksProgressAndFocus() {
        long h = 3600_000L;
        ScheduleModel s = ScheduleModel.parse("{\"items\":["
                + "{\"id\":\"a\",\"url\":\"https://x.test/a.mp4\",\"start\":" + h + ",\"end\":" + (h + 200_000) + "},"
                + "{\"id\":\"b\",\"url\":\"https://x.test/b.mp4\",\"start\":" + 7 * h + ",\"end\":" + (7 * h + 200_000) + "}]}");
        java.util.List<ScheduleModel.Item> av = s.available(60 * h);
        assertEquals(2, av.size());
        assertEquals("b", av.get(0).id);
        assertEquals("b", ScheduleModel.newer(av, "a").id);
        assertNull(ScheduleModel.newer(av, "b"));
        assertEquals("a", ScheduleModel.older(av, "b").id);
        assertNull(ScheduleModel.older(av, "a"));

        WatchState w = WatchState.parse(null);
        assertEquals("New", w.label("b"));
        assertEquals(WatchState.Focus.LATEST, w.homeFocus("b", null));          // newest unplayed: Play latest
        w.record("a", 80_000, 200_000, 1000);
        assertEquals("40% watched", w.label("a"));
        assertEquals(80_000, w.resumeAt("a"));
        assertEquals("a", w.resumeCandidate(av));
        assertEquals(120_000, w.remaining("a"));
        w.record("b", 185_000, 200_000, 2000);                                  // last 20 s counts as watched
        assertEquals("Watched", w.label("b"));
        assertEquals(0, w.resumeAt("b"));
        assertEquals(WatchState.Focus.RESUME, w.homeFocus("b", w.resumeCandidate(av)));
        w.record("x", 5_000, 200_000, 3000);                                    // barely started: no resume
        assertEquals(0, w.resumeAt("x"));
        assertEquals("", w.label("x"));

        WatchState back = WatchState.parse(w.toJson());                         // survives a restart
        assertEquals(80_000, back.resumeAt("a"));
        assertTrue(back.watched("b"));
        back.prune(av);
        assertFalse(back.started("x"));
        back.prune(new java.util.ArrayList<ScheduleModel.Item>());              // empty schedule keeps history
        assertTrue(back.started("a"));
        assertEquals(WatchState.Focus.EPISODES, WatchState.parse("garbage").homeFocus(null, null));
        assertEquals("6:42", WatchState.clock(402_000));
        long t = 1795051289000L;   // 19 Nov 2026, 2:21 pm NZDT
        assertEquals("Today 2:21 pm", StatusFormat.day(t, t + 3600_000L));
        assertEquals("Yesterday 2:21 pm", StatusFormat.day(t, t + 24 * 3600_000L));
        assertEquals("19 Nov 2:21 pm", StatusFormat.day(t, t + 48 * 3600_000L));
        assertEquals("2 min", StatusFormat.minutes(146_000));
    }

    @Test public void statusLineUsesNewZealandTime() {
        // 2026-11-18 19:04 UTC = 19 Nov 08:04 NZDT (UTC+13); 2026-07-01 00:00 UTC = 12:00 pm NZST (UTC+12)
        assertEquals("19 Nov 2026, 8:04 am NZDT", StatusFormat.nz(1795028655550L));
        assertEquals("1 Jul 2026, 12:00 pm NZST", StatusFormat.nz(1782864000000L));
        assertEquals("Updated 19 Nov 2026, 8:04 am NZDT  |  Episode 20261118-1902  |  AI free left 94%",
                StatusFormat.line(1795028655550L, "20261118-1902", 94));
        assertEquals("Updated: unknown  |  AI free left unknown", StatusFormat.line(0, null, -1));
        assertEquals("Episode Today 2:21 pm  |  Updated 19 Nov 2026, 8:04 am NZDT  |  AI free left 94%",
                StatusFormat.topLine(1795028655550L, 1795051289000L, 1795051289000L + 60_000, 94));
        assertEquals("Updated: unknown  |  AI free left unknown", StatusFormat.topLine(0, 0, 0, -1));
    }
}
