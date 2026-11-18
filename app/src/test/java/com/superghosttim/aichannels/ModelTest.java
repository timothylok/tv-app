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

    @Test public void statusLineUsesNewZealandTime() {
        // 2026-11-18 19:04 UTC = 19 Nov 08:04 NZDT (UTC+13); 2026-07-01 00:00 UTC = 12:00 pm NZST (UTC+12)
        assertEquals("19 Nov 2026, 8:04 am NZDT", StatusFormat.nz(1795028655550L));
        assertEquals("1 Jul 2026, 12:00 pm NZST", StatusFormat.nz(1782864000000L));
        assertEquals("Updated 19 Nov 2026, 8:04 am NZDT  |  Episode 20261118-1902  |  AI free left 94%",
                StatusFormat.line(1795028655550L, "20261118-1902", 94));
        assertEquals("Updated: unknown  |  AI free left unknown", StatusFormat.line(0, null, -1));
    }
}
