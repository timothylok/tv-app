package com.superghosttim.aichannels;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen "live TV": picks the item that is on air by server time, seeks into it, and follows the schedule.
 * CH+/CH-/D-pad up/down switch channel, number keys jump, OK shows Now/Next, Back opens the channel list.
 * All network work runs on a background thread; the player and views are only touched on the main thread.
 */
public class TvActivity extends Activity {
    private static final String TAG = "AITV";
    private static final String FILLER_ID = "filler";
    private static final long TICK_MS = 2000;
    private static final long RECENT_MS = 24L * 3600 * 1000;   // episodes you can watch again
    private static final long SCHEDULE_REFRESH_MS = 60_000;
    private static final long GAP_REFRESH_MS = 10_000;
    private static final long DRIFT_LIMIT_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ServerClock clock = new ServerClock();
    private final Map<String, ScheduleModel> schedules = new HashMap<>();

    private SharedPreferences prefs;
    private ExoPlayer player;
    private ChannelList channels = ChannelList.parse("{}");
    private int current = -1;
    private int generation = 0;          // bumped on every channel switch so stale fetches are ignored
    private long lastRefresh = 0;
    private long retryAt = 0;            // after a player error, wait before reloading
    private boolean started = false;
    private String replayId;                 // episode being watched again (in a gap, or picked from the list)
    private boolean replayChosen;            // picked by the viewer: keep playing it even if it is also on air now
    private List<ScheduleModel.Item> choices = new ArrayList<>();
    private boolean listIsEpisodes;

    private TextView osdNumber, status, bannerChannel, bannerNow, bannerNext, bannerStatus;
    private View banner;
    private ListView list;
    private final StringBuilder digits = new StringBuilder();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { sync(); } catch (RuntimeException e) { Log.w(TAG, "sync failed: " + e); }
            main.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable hideBanner = new Runnable() {
        @Override public void run() { banner.setVisibility(View.GONE); osdNumber.setVisibility(View.GONE); }
    };
    private final Runnable commitDigits = new Runnable() {
        @Override public void run() { commitNumber(); }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("aitv", MODE_PRIVATE);
        buildUi();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "player error: " + e.getErrorCodeName());
                retryAt = System.currentTimeMillis() + 5000;
                player.stop();
                player.clearMediaItems();   // sync() loads the right item again after the wait
            }
        });
        ((PlayerView) findViewById(1)).setPlayer(player);
        KeepAliveService.start(this);
        loadChannels();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override protected void onStop() {
        started = false;
        main.removeCallbacks(tick);
        if (player != null) player.pause();   // going Home must not leave sound playing
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (player != null) { player.release(); player = null; }
        KeepAliveService.stop(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI (built in code: no layout files to keep in sync)

    private int px(float fractionOfHeight) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return Math.round(m.heightPixels * fractionOfHeight);
    }

    private TextView text(float sizeFrac, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizeFrac));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(4, 0, 2, Color.BLACK);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        PlayerView pv = new PlayerView(this);
        pv.setId(1);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        int margin = px(0.06f);   // TV safe area

        status = text(0.04f, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        osdNumber = text(0.09f, true);
        osdNumber.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        np.setMargins(margin, margin, margin, margin);
        root.addView(osdNumber, np);

        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.VERTICAL);
        bl.setBackgroundColor(0xCC0B1220);
        bl.setPadding(margin, px(0.025f), margin, px(0.025f));
        bannerChannel = text(0.05f, true);
        bannerNow = text(0.04f, false);
        bannerNext = text(0.035f, false);
        bannerNext.setTextColor(0xFF9DB7E8);
        bl.addView(bannerChannel);
        bl.addView(bannerNow);
        bl.addView(bannerNext);
        bannerStatus = text(0.03f, false);
        bannerStatus.setTextColor(0xFF8FA3C7);
        bl.addView(bannerStatus);
        bl.setVisibility(View.GONE);
        banner = bl;
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(0, 0, 0, margin / 2);
        root.addView(bl, bp);

        list = new ListView(this);
        list.setBackgroundColor(0xE60B1220);
        list.setVisibility(View.GONE);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                hideList();
                if (listIsEpisodes) { if (pos >= 0 && pos < choices.size()) playReplay(choices.get(pos)); }
                else tuneTo(pos);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels * 2 / 5, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        root.addView(list, lp);

        setContentView(root);
    }

    private void showList() {
        if (channels.size() == 0) return;
        String[] names = new String[channels.size()];
        for (int i = 0; i < names.length; i++) names[i] = channels.get(i).number + "   " + channels.get(i).name;
        listIsEpisodes = false;
        fillList(names, Math.max(0, current));
    }

    /** Episodes of the last 24 hours, newest first; OK plays the chosen one from the start. */
    private void showEpisodes() {
        if (current < 0 || current >= channels.size()) return;
        ScheduleModel s = schedules.get(channels.get(current).id);
        if (s == null) return;
        long now = clock.now(System.currentTimeMillis());
        choices = s.recent(now, RECENT_MS);
        ScheduleModel.Item live = s.itemAt(now);
        String[] names = new String[Math.max(1, choices.size())];
        if (choices.isEmpty()) names[0] = "No episodes in the last 24 hours";
        for (int i = 0; i < choices.size(); i++) {
            ScheduleModel.Item c = choices.get(i);
            names[i] = StatusFormat.nzShort(c.start) + "   " + c.title
                    + (live != null && live.id.equals(c.id) ? "   (on air)" : "");
        }
        listIsEpisodes = true;
        fillList(names, 0);
    }

    private void fillList(String[] names, int selected) {
        final int size = px(0.045f);
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                t.setTextColor(Color.WHITE);
                t.setPadding(px(0.06f), px(0.02f), px(0.02f), px(0.02f));
                return t;
            }
        });
        list.setVisibility(View.VISIBLE);
        list.setSelection(selected);
        list.requestFocus();
    }

    private void hideList() { list.setVisibility(View.GONE); }

    private void setStatus(String s) { status.setText(s == null ? "" : s); }

    private void showBanner() {
        if (current < 0 || current >= channels.size()) return;
        ChannelList.Channel ch = channels.get(current);
        ScheduleModel s = schedules.get(ch.id);
        long now = clock.now(System.currentTimeMillis());
        DateFormat tf = android.text.format.DateFormat.getTimeFormat(this);
        bannerChannel.setText("CH " + ch.number + "   " + ch.name);
        ScheduleModel.Item it = s == null ? null : s.itemAt(now);
        ScheduleModel.Item next = s == null ? null : s.nextAfter(now);
        ScheduleModel.Item rp = replayId == null || s == null ? null : findIn(s.recent(now, RECENT_MS), replayId);
        bannerNow.setText(rp != null && (replayChosen || it == null)
                ? "Watching again: " + rp.title + "  (from " + StatusFormat.nzShort(rp.start) + ")"
                : it != null ? "Now: " + it.title + "  (until " + tf.format(new Date(it.end)) + ")"
                : "Next episode coming up shortly");
        bannerNext.setText(next != null ? "Next: " + next.title + "  at " + tf.format(new Date(next.start)) : "");
        bannerNext.setVisibility(next != null ? View.VISIBLE : View.GONE);
        ScheduleModel.Item last = s == null ? null : s.lastStartedBy(now);
        bannerStatus.setText(s == null ? "" : StatusFormat.line(s.updated(), last == null ? null : last.id, s.aiLeftPct()));
        banner.setVisibility(View.VISIBLE);
        osdNumber.setText(String.valueOf(ch.number));
        osdNumber.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBanner);
        main.postDelayed(hideBanner, 5000);
    }

    // ------------------------------------------------------------------ keys

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        boolean listShown = list.getVisibility() == View.VISIBLE;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (listShown) hideList(); else showList();
            return true;
        }
        if (listShown) return super.onKeyDown(code, e);   // the list handles its own D-pad / OK
        switch (code) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channels.size() > 0) tuneTo(channels.up(current));
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channels.size() > 0) tuneTo(channels.down(current));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_GUIDE:
                showEpisodes();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (digits.length() > 0) commitNumber(); else showBanner();
                return true;
            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { typeDigit(code - KeyEvent.KEYCODE_0); return true; }
                if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) { typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); return true; }
                return super.onKeyDown(code, e);
        }
    }

    private void typeDigit(int d) {
        if (digits.length() >= 3) digits.setLength(0);
        digits.append(d);
        osdNumber.setText(digits.toString());
        osdNumber.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBanner);
        main.removeCallbacks(commitDigits);
        main.postDelayed(commitDigits, 1500);
    }

    private void commitNumber() {
        main.removeCallbacks(commitDigits);
        if (digits.length() == 0) return;
        int n = Integer.parseInt(digits.toString());
        digits.setLength(0);
        int idx = channels.indexOfNumber(n);
        if (idx >= 0) tuneTo(idx);
        else { osdNumber.setText(n + "  -"); main.postDelayed(hideBanner, 1500); }
    }

    // ------------------------------------------------------------------ channels and schedule (network on io thread)

    private File cacheFile(String name) { return new File(getFilesDir(), name); }

    private void writeCache(String name, String body) {
        try {
            File f = cacheFile(name), tmp = new File(f.getPath() + ".part");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(body.getBytes(Charset.forName("UTF-8"))); }
            if (!tmp.renameTo(f)) Log.w(TAG, "cache rename failed for " + name);
        } catch (Exception e) {
            Log.w(TAG, "cache write failed: " + e);
        }
    }

    private String readCache(String name) {
        try {
            File f = cacheFile(name);
            if (!f.isFile() || f.length() > (1 << 20)) return null;
            byte[] buf = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            }
            return new String(buf, Charset.forName("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private void loadChannels() {
        setStatus("Connecting...");
        io.execute(new Runnable() {
            @Override public void run() {
                String body = null;
                try {
                    Net.Result r = Net.get(BuildConfig.CHANNELS_URL);
                    clock.update(r.date, r.ageSeconds, r.sentLocal, r.recvLocal);
                    body = r.body;
                    if (ChannelList.parse(body).size() > 0) writeCache("channels.json", body);
                } catch (Exception e) {
                    Log.w(TAG, "channels fetch failed: " + e);
                    body = readCache("channels.json");
                }
                final ChannelList parsed = body == null ? ChannelList.parse("{}") : ChannelList.parse(body);
                main.post(new Runnable() {
                    @Override public void run() { onChannels(parsed); }
                });
            }
        });
    }

    private void onChannels(ChannelList list) {
        if (isFinishing() || player == null) return;
        if (list.size() == 0) {
            setStatus("Can't reach the channels. Retrying...");
            main.postDelayed(new Runnable() { @Override public void run() { if (player != null) loadChannels(); } }, 10_000);
            return;
        }
        channels = list;
        int idx = channels.indexOfId(prefs.getString("last", ""));
        tuneTo(idx >= 0 ? idx : 0);
    }

    private void tuneTo(int idx) {
        if (idx < 0 || idx >= channels.size()) return;
        current = idx;
        generation++;
        replayId = null;
        replayChosen = false;
        prefs.edit().putString("last", channels.get(idx).id).apply();
        digits.setLength(0);
        retryAt = 0;
        player.stop();
        player.clearMediaItems();
        setStatus("");
        showBanner();
        refreshSchedule(true);
        sync();   // plays at once if a cached schedule exists
    }

    /** Fetch the current channel's schedule in the background; the result is applied on the main thread. */
    private void refreshSchedule(boolean force) {
        if (current < 0) return;
        long t = System.currentTimeMillis();
        if (!force && t - lastRefresh < SCHEDULE_REFRESH_MS) return;
        lastRefresh = t;
        final int gen = generation;
        final ChannelList.Channel ch = channels.get(current);
        io.execute(new Runnable() {
            @Override public void run() {
                String body = null;
                try {
                    Net.Result r = Net.get(ch.scheduleUrl);
                    clock.update(r.date, r.ageSeconds, r.sentLocal, r.recvLocal);
                    body = r.body;
                    writeCache("schedule-" + ch.id + ".json", body);
                } catch (Exception e) {
                    Log.w(TAG, "schedule fetch failed: " + e);
                    if (schedules.get(ch.id) == null) body = readCache("schedule-" + ch.id + ".json");
                }
                if (body == null) return;
                final ScheduleModel m = ScheduleModel.parse(body);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (player == null) return;
                        schedules.put(ch.id, m);
                        if (gen == generation) sync();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ playback: one idempotent "make the player match the schedule"

    private static ScheduleModel.Item findIn(List<ScheduleModel.Item> l, String id) {
        for (ScheduleModel.Item i : l) if (i.id.equals(id)) return i;
        return null;
    }

    private void loadReplay(ScheduleModel.Item r) {
        player.setMediaItem(new MediaItem.Builder().setMediaId(r.id).setUri(r.url).build());
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.prepare();
        player.seekTo(0, 0);
        player.play();
        Log.i(TAG, "watching again: " + r.id);
    }

    /** The viewer picked an episode from the list: play it from the start. */
    private void playReplay(ScheduleModel.Item r) {
        replayId = r.id;
        replayChosen = true;
        retryAt = 0;
        setStatus("");
        loadReplay(r);
        showBanner();
    }

    private String playingId() {
        MediaItem mi = player.getCurrentMediaItem();
        return mi == null ? null : mi.mediaId;
    }

    private void sync() {
        if (player == null || current < 0 || !started) return;
        long wall = System.currentTimeMillis();
        refreshSchedule(false);
        if (wall < retryAt) return;
        ChannelList.Channel ch = channels.get(current);
        ScheduleModel s = schedules.get(ch.id);
        if (s == null) { setStatus("Loading schedule..."); return; }
        long now = clock.now(wall);
        ScheduleModel.Item it = s.itemAt(now);
        boolean ended = player.getPlaybackState() == Player.STATE_ENDED;
        if (replayId != null && replayChosen && replayId.equals(playingId()) && !ended
                && (it == null || it.id.equals(replayId))) {
            return;   // the viewer picked this episode: let it play to the end (even if it is also on air now)
        }
        if (it != null) {
            replayId = null;          // live takes over from any replay
            replayChosen = false;
            setStatus("");
            String have = playingId();
            if (!it.id.equals(have) || ended) {
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
                player.setMediaItem(new MediaItem.Builder().setMediaId(it.id).setUri(it.url).build());
                ScheduleModel.Item next = s.nextAfter(now);
                if (ScheduleModel.contiguous(it, next)) {   // queue the next one so the switch is gapless
                    player.addMediaItem(new MediaItem.Builder().setMediaId(next.id).setUri(next.url).build());
                }
                player.prepare();
                player.seekTo(0, Math.max(0, now - it.start));
                player.play();
                Log.i(TAG, "on air: " + it.id + " at +" + (now - it.start) / 1000 + "s");
            } else {
                if (!player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) player.play();
                // keep following the clock: seek only when clearly out of step (buffering stalls are not drift)
                if (player.getPlaybackState() == Player.STATE_READY && player.isPlaying()) {
                    long expect = now - it.start;
                    if (Math.abs(player.getCurrentPosition() - expect) > DRIFT_LIMIT_MS) {
                        Log.i(TAG, "drift fix to +" + expect / 1000 + "s");
                        player.seekTo(Math.max(0, expect));
                    }
                }
                // queue the next item once it becomes contiguous and is not queued yet
                ScheduleModel.Item next = s.nextAfter(now);
                if (ScheduleModel.contiguous(it, next) && player.getMediaItemCount() == 1) {
                    player.addMediaItem(new MediaItem.Builder().setMediaId(next.id).setUri(next.url).build());
                }
            }
            return;
        }
        // gap: watch the recent episodes again (newest first, then the older ones) until the next one is on air
        List<ScheduleModel.Item> recent = s.recent(now, RECENT_MS);
        if (!recent.isEmpty()) {
            ScheduleModel.Item r = replayId == null ? null : findIn(recent, replayId);
            boolean replaying = r != null && r.id.equals(playingId());
            if (r == null) r = recent.get(0);                                          // nothing chosen yet: the newest
            else if (replaying && ended) r = ScheduleModel.nextReplay(recent, r.id);   // finished: the next one
            if (!r.id.equals(playingId()) || ended) { replayChosen = false; loadReplay(r); }
            else if (!player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) player.play();
            replayId = r.id;
            setStatus("");
            if (wall - lastRefresh > GAP_REFRESH_MS) refreshSchedule(true);
            return;
        }
        // nothing recent at all: loop the filler (or wait)
        if (s.filler() == null) { setStatus("Next episode coming up shortly"); return; }
        setStatus("");
        if (!FILLER_ID.equals(playingId())) {
            player.setMediaItem(new MediaItem.Builder().setMediaId(FILLER_ID).setUri(s.filler()).build());
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.prepare();
            player.play();
            Log.i(TAG, "on air: filler");
        }
        if (wall - lastRefresh > GAP_REFRESH_MS) refreshSchedule(true);
    }
}
