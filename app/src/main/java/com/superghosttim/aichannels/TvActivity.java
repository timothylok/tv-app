package com.superghosttim.aichannels;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
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
 * Three modes:
 *  HOME  - a short menu: Play latest, Continue watching, All episodes, Watch live (when something is on air).
 *  VOD   - one episode on demand, with resume positions, a pause menu, "Leave episode?" on Back and an end screen.
 *  LIVE  - the original "live TV": plays what is on air by server time (CH+/CH- lead here).
 * All network work runs on a background thread; the player and views are only touched on the main thread.
 */
public class TvActivity extends Activity {
    private static final String TAG = "AITV";
    private static final long TICK_MS = 2000;
    private static final long SCHEDULE_REFRESH_MS = 60_000;
    private static final long DRIFT_LIMIT_MS = 5000;
    private static final long SAVE_EVERY_MS = 10_000;
    private static final long SEEK_MS = 10_000;
    private static final long CONTROLS_HIDE_MS = 6000;

    // Navy background colours (alpha first): captions stay readable over any video frame.
    private static final int BG_CAPTION = 0xCC0B1220;   // 20% transparent: brief captions and the info banner
    private static final int BG_TOPLINE = 0xA60B1220;   // 35% transparent: the always-on top line
    private static final int BG_MENU = 0xF20B1220;      // 5% transparent: menus and the episode list

    private enum Mode { HOME, VOD, LIVE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ServerClock clock = new ServerClock();
    private final Map<String, ScheduleModel> schedules = new HashMap<>();

    private SharedPreferences prefs;
    private ExoPlayer player;
    private WatchState watch;
    private ChannelList channels = ChannelList.parse("{}");
    private int current = -1;
    private int generation = 0;          // bumped on every channel switch so stale fetches are ignored
    private long lastRefresh = 0, lastSave = 0;
    private long retryAt = 0;            // live: after a player error, wait before reloading
    private boolean started = false;
    private Mode mode = Mode.HOME;
    private ScheduleModel.Item vodItem;  // the episode playing on demand
    private long homeShownFor = -2;      // schedule `updated` the home screen was built from
    private String homeNotice = "";
    private boolean keepOn = false;

    private TextView osdNumber, status, bannerChannel, bannerNow, bannerNext, topBar;
    private View banner;
    private ListView list;
    private List<ScheduleModel.Item> choices = new ArrayList<>();
    private LinearLayout panel, panelButtons;
    private TextView panelTitle, panelInfo;
    private ProgressBar panelProgress;
    private String panelKind = "";       // home, controls, paused, leave, end, error ("" = hidden)
    private final StringBuilder digits = new StringBuilder();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { sync(); } catch (RuntimeException e) { Log.w(TAG, "sync failed: " + e); }
            main.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable hideBanner = () -> { banner.setVisibility(View.GONE); osdNumber.setVisibility(View.GONE); };
    private final Runnable hideControls = () -> { if ("controls".equals(panelKind)) hidePanel(); };
    private final Runnable clearStatus = () -> setStatus("");
    private final Runnable commitDigits = this::commitNumber;

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("aitv", MODE_PRIVATE);
        watch = WatchState.parse(prefs.getString("watch", ""));
        buildUi();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "player error: " + e.getErrorCodeName());
                if (mode == Mode.VOD) { showError(); return; }
                retryAt = System.currentTimeMillis() + 5000;   // live: try again quietly
                player.stop();
                player.clearMediaItems();
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
        if (mode == Mode.VOD && vodItem != null && panelKind.isEmpty()) showPaused();   // back from the Home key
    }

    @Override protected void onStop() {
        started = false;
        main.removeCallbacks(tick);
        saveProgress();
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

    private int width() { return getResources().getDisplayMetrics().widthPixels; }

    private TextView text(float sizeFrac, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizeFrac));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(4, 0, 2, Color.BLACK);
        return t;
    }

    /** Background and padding behind a caption so it never sits on bare video. */
    private void solid(TextView t, int color) {
        t.setBackgroundColor(color);
        t.setPadding(px(0.015f), px(0.008f), px(0.015f), px(0.008f));
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(0.012f));
        return g;
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
        solid(status, BG_CAPTION);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        topBar = text(0.028f, false);   // always on: episode, last update (NZ time), free AI left
        topBar.setTextColor(0xCCFFFFFF);
        topBar.setSingleLine(true);
        topBar.setEllipsize(TextUtils.TruncateAt.END);
        topBar.setMaxWidth(width() * 8 / 10);
        solid(topBar, BG_TOPLINE);
        topBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.setMargins(margin / 2, margin / 2, margin, margin);
        root.addView(topBar, tp);

        osdNumber = text(0.09f, true);
        solid(osdNumber, BG_CAPTION);
        osdNumber.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        np.setMargins(margin, margin, margin, margin);
        root.addView(osdNumber, np);

        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.VERTICAL);
        bl.setBackgroundColor(BG_CAPTION);
        bl.setPadding(margin, px(0.025f), margin, px(0.025f));
        bannerChannel = text(0.05f, true);
        bannerNow = text(0.04f, false);
        bannerNext = text(0.035f, false);
        bannerNext.setTextColor(0xFF9DB7E8);
        bl.addView(bannerChannel);
        bl.addView(bannerNow);
        bl.addView(bannerNext);
        bl.setVisibility(View.GONE);
        banner = bl;
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(0, 0, 0, margin / 2);
        root.addView(bl, bp);

        panel = new LinearLayout(this);   // one reusable menu: home, controls, paused, leave, end, error
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BG_MENU);
        panel.setPadding(px(0.06f), px(0.04f), px(0.06f), px(0.04f));
        panelTitle = text(0.05f, true);
        panelInfo = text(0.034f, false);
        panelInfo.setTextColor(0xFFC9D6EE);
        panelInfo.setLineSpacing(px(0.008f), 1f);
        panelProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        panelProgress.setMax(1000);
        panelButtons = new LinearLayout(this);
        panel.addView(panelTitle);
        panel.addView(panelInfo);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, px(0.02f));
        pp.topMargin = px(0.02f);
        panel.addView(panelProgress, pp);
        panel.addView(panelButtons, new LinearLayout.LayoutParams(-1, -2));
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(width() * 3 / 5, -2, Gravity.CENTER));

        list = new ListView(this);
        list.setBackgroundColor(BG_MENU);
        list.setVisibility(View.GONE);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= choices.size()) return;
            ScheduleModel.Item c = choices.get(pos);
            hideList();
            playEpisode(c, watch.resumeAt(c.id));
        });
        root.addView(list, new FrameLayout.LayoutParams(width() / 2, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        setContentView(root);
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.038f));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(px(0.03f), px(0.012f), px(0.03f), px(0.012f));
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_focused}, rounded(0xFF3B82F6));
        sd.addState(new int[]{}, rounded(0xFF1F2A44));
        b.setBackground(sd);
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Show the menu panel. progress < 0 hides the bar; bottom + horizontal = the playback controls row. */
    private void showPanel(String kind, String title, String info, int progress, boolean bottom, boolean horizontal,
                           String[] labels, Runnable[] actions, int focus) {
        panelKind = kind;
        main.removeCallbacks(hideControls);
        panelTitle.setText(title);
        panelTitle.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        panelInfo.setText(info);
        panelInfo.setVisibility(TextUtils.isEmpty(info) ? View.GONE : View.VISIBLE);
        panelProgress.setVisibility(progress >= 0 ? View.VISIBLE : View.GONE);
        if (progress >= 0) panelProgress.setProgress(progress);
        panelButtons.removeAllViews();
        panelButtons.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        Button first = null, focused = null;
        for (int i = 0; i < labels.length; i++) {
            Button b = button(labels[i], actions[i]);
            LinearLayout.LayoutParams lp = horizontal ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(-1, -2);
            if (horizontal) lp.rightMargin = px(0.02f);
            lp.topMargin = px(0.02f);
            panelButtons.addView(b, lp);
            if (first == null) first = b;
            if (i == focus) focused = b;
        }
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) panel.getLayoutParams();
        fl.width = bottom ? -1 : width() * 3 / 5;
        fl.gravity = bottom ? Gravity.BOTTOM : Gravity.CENTER;
        panel.setLayoutParams(fl);
        panel.setTranslationX(0);
        panel.setTranslationY(0);
        panel.setVisibility(View.VISIBLE);
        banner.setVisibility(View.GONE);
        Button f = focused != null ? focused : first;
        if (f != null && list.getVisibility() != View.VISIBLE) f.requestFocus();
        if ("controls".equals(kind)) main.postDelayed(hideControls, CONTROLS_HIDE_MS);
    }

    private void hidePanel() {
        panelKind = "";
        main.removeCallbacks(hideControls);
        panel.setVisibility(View.GONE);
    }

    private void setStatus(String s) {
        status.setText(s == null ? "" : s);
        status.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ the schedule of the current channel

    private ScheduleModel sched() {
        if (current < 0 || current >= channels.size()) return null;
        return schedules.get(channels.get(current).id);
    }

    private long now() { return clock.now(System.currentTimeMillis()); }

    private List<ScheduleModel.Item> available() {
        ScheduleModel s = sched();
        return s == null ? new ArrayList<ScheduleModel.Item>() : s.available(now());
    }

    private String describe(ScheduleModel.Item it) {
        return StatusFormat.day(it.start, now()) + "  ·  " + StatusFormat.minutes(it.end - it.start);
    }

    private String withLabel(String text, String id) {
        String l = watch.label(id);
        return l.isEmpty() ? text : text + "  ·  " + l;
    }

    private String channelName() { return current >= 0 && current < channels.size() ? channels.get(current).name : "AI Channels"; }

    // ------------------------------------------------------------------ HOME

    private void showHome(String notice) {
        homeNotice = notice == null ? "" : notice;
        saveProgress();
        mode = Mode.HOME;
        vodItem = null;
        player.stop();
        player.clearMediaItems();
        hideList();
        setStatus("");
        buildHome();
    }

    private void buildHome() {
        ScheduleModel s = sched();
        homeShownFor = s == null ? -2 : s.updated();
        if (s == null) {
            showPanel("home", channelName(), "Loading episodes...", -1, false, false, new String[0], new Runnable[0], 0);
            return;
        }
        List<ScheduleModel.Item> av = s.available(now());
        watch.prune(av);
        final ScheduleModel.Item latest = av.isEmpty() ? null : av.get(0);
        String resumeId = watch.resumeCandidate(av);
        final ScheduleModel.Item resume = resumeId == null ? null : findIn(av, resumeId);
        final ScheduleModel.Item live = s.itemAt(now());

        StringBuilder info = new StringBuilder();
        if (!homeNotice.isEmpty()) info.append(homeNotice).append("\n");
        info.append(latest == null ? "No episodes yet. New ones are made at 8 am and 2 pm."
                : withLabel("Latest:  Episode " + latest.id + "  ·  " + describe(latest), latest.id));
        if (resume != null) info.append("\nContinue:  Episode ").append(resume.id).append("  ·  ")
                .append(WatchState.clock(watch.remaining(resume.id))).append(" remaining");

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int latestAt = -1, resumeAt = -1;
        if (latest != null) { latestAt = labels.size(); labels.add("▶  Play latest episode"); actions.add(() -> playEpisode(latest, 0)); }
        if (resume != null) {
            resumeAt = labels.size();
            labels.add("↻  Continue watching");
            actions.add(() -> playEpisode(resume, watch.resumeAt(resume.id)));
        }
        int episodesAt = labels.size();
        labels.add("▣  All episodes");
        actions.add(this::showEpisodes);
        if (live != null) { labels.add("●  Watch live now"); actions.add(() -> goLive(current)); }

        WatchState.Focus f = watch.homeFocus(latest == null ? null : latest.id, resume == null ? null : resume.id);
        int focus = f == WatchState.Focus.LATEST && latestAt >= 0 ? latestAt
                : f == WatchState.Focus.RESUME && resumeAt >= 0 ? resumeAt : episodesAt;
        showPanel("home", channelName(), info.toString(), -1, false, false,
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]), focus);
    }

    // ------------------------------------------------------------------ episode list

    private void showEpisodes() {
        choices = available();
        String[] names = new String[Math.max(1, choices.size())];
        if (choices.isEmpty()) names[0] = "No episodes yet";
        int sel = 0;
        for (int i = 0; i < choices.size(); i++) {
            ScheduleModel.Item c = choices.get(i);
            names[i] = withLabel("Episode " + c.id + "\n" + describe(c), c.id);
            if (vodItem != null && vodItem.id.equals(c.id)) sel = i;
        }
        final int size = px(0.036f);
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                t.setTextColor(Color.WHITE);
                t.setPadding(px(0.06f), px(0.018f), px(0.02f), px(0.018f));
                return t;
            }
        });
        list.setVisibility(View.VISIBLE);
        list.setSelection(sel);
        list.requestFocus();
    }

    private void hideList() {
        if (list.getVisibility() != View.VISIBLE) return;
        list.setVisibility(View.GONE);
        if (!panelKind.isEmpty() && panelButtons.getChildCount() > 0) panelButtons.getChildAt(0).requestFocus();
    }

    // ------------------------------------------------------------------ VOD: one episode on demand

    private void playEpisode(ScheduleModel.Item it, long fromMs) {
        if (it == null) return;
        saveProgress();
        hidePanel();
        hideList();
        mode = Mode.VOD;
        vodItem = it;
        setStatus("");
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setMediaItem(new MediaItem.Builder().setMediaId(it.id).setUri(it.url).build());
        player.prepare();
        player.seekTo(0, Math.max(0, fromMs));
        player.play();
        watch.record(it.id, fromMs, it.end - it.start, System.currentTimeMillis());   // "started": no longer New
        persistWatch();
        Log.i(TAG, "playing " + it.id + " from " + fromMs / 1000 + "s");
        showInfoBanner();
    }

    private long duration() {
        long d = player.getDuration();
        return d > 0 ? d : vodItem == null ? 0 : vodItem.end - vodItem.start;
    }

    private void saveProgress() {
        if (mode != Mode.VOD || vodItem == null || player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) return;   // the end screen marks it watched
        if (player.getPlaybackState() == Player.STATE_IDLE) return;    // error / stopped: keep the last good position
        watch.record(vodItem.id, player.getCurrentPosition(), duration(), System.currentTimeMillis());
        persistWatch();
        lastSave = System.currentTimeMillis();
    }

    private void persistWatch() { prefs.edit().putString("watch", watch.toJson()).apply(); }

    private int progress() {
        long d = duration();
        return d <= 0 ? 0 : (int) Math.min(1000, player.getCurrentPosition() * 1000 / d);
    }

    private String timeLine() {
        return WatchState.clock(player.getCurrentPosition()) + " / " + WatchState.clock(duration());
    }

    private void showControls() {
        if (vodItem == null) return;
        final List<ScheduleModel.Item> av = available();
        final ScheduleModel.Item older = ScheduleModel.older(av, vodItem.id), newer = ScheduleModel.newer(av, vodItem.id);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(player.isPlaying() ? "❚❚  Pause" : "▶  Play");
        actions.add(this::togglePlay);
        if (older != null) { labels.add("◀  Previous"); actions.add(() -> playEpisode(older, watch.resumeAt(older.id))); }
        if (newer != null) { labels.add("Next  ▶"); actions.add(() -> playEpisode(newer, watch.resumeAt(newer.id))); }
        labels.add("More episodes"); actions.add(this::showEpisodes);
        labels.add("Exit"); actions.add(this::showLeave);
        showPanel("controls", "Episode " + vodItem.id + "  ·  " + channelName(), timeLine(), progress(), true, true,
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]), 0);
    }

    private void togglePlay() {
        if (player.isPlaying()) pause();
        else { hidePanel(); player.play(); }
    }

    private void pause() {
        player.pause();
        saveProgress();
        showPaused();
    }

    private void showPaused() {
        if (vodItem == null) return;
        final List<ScheduleModel.Item> av = available();
        showPanel("paused", "Paused", "Episode " + vodItem.id + "\n" + WatchState.clock(player.getCurrentPosition())
                        + " of " + WatchState.clock(duration()), progress(), false, false,
                new String[]{"Resume", "Choose episode", "Play latest", "Exit playback"},
                new Runnable[]{
                        () -> { hidePanel(); player.play(); },
                        this::showEpisodes,
                        () -> { if (!av.isEmpty()) playEpisode(av.get(0), 0); },
                        () -> showHome("Your position was saved.")},
                0);
    }

    private void showLeave() {
        if (vodItem == null) return;
        player.pause();
        saveProgress();
        showPanel("leave", "Leave episode?", "Your position will be saved at " + WatchState.clock(player.getCurrentPosition()) + ".",
                -1, false, false,
                new String[]{"Save and exit", "Keep watching", "Choose another episode"},
                new Runnable[]{
                        () -> showHome("Your position was saved."),
                        () -> { hidePanel(); player.play(); },
                        this::showEpisodes},
                0);
    }

    private void showEnd(final ScheduleModel.Item done) {
        mode = Mode.VOD;
        vodItem = done;
        watch.markWatched(done.id, System.currentTimeMillis());
        persistWatch();
        final List<ScheduleModel.Item> av = available();
        final ScheduleModel.Item latest = av.isEmpty() ? null : av.get(0);
        ScheduleModel.Item nx = ScheduleModel.newer(av, done.id);
        if (nx == null) nx = ScheduleModel.older(av, done.id);
        final ScheduleModel.Item next = nx;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String title, info;
        if (latest != null && !latest.id.equals(done.id) && !watch.started(latest.id)) {
            title = "New episode available";
            info = "Episode " + latest.id + " is now available.";
            labels.add("Play episode " + latest.id); actions.add(() -> playEpisode(latest, 0));
            labels.add("Replay episode " + done.id); actions.add(() -> playEpisode(done, 0));
        } else {
            title = "Episode complete";
            info = "Episode " + done.id + " finished.";
            if (next != null) { labels.add("Play next episode (" + next.id + ")"); actions.add(() -> playEpisode(next, watch.resumeAt(next.id))); }
            if (latest != null && !latest.id.equals(done.id) && (next == null || !latest.id.equals(next.id))) {
                labels.add("Play latest"); actions.add(() -> playEpisode(latest, 0));
            }
            labels.add("Replay"); actions.add(() -> playEpisode(done, 0));
        }
        labels.add("Choose episode"); actions.add(this::showEpisodes);
        labels.add("Return to menu"); actions.add(() -> showHome(""));
        showPanel("end", title, info, -1, false, false, labels.toArray(new String[0]), actions.toArray(new Runnable[0]), 0);
    }

    private void showError() {
        final ScheduleModel.Item it = vodItem;
        player.stop();
        showPanel("error", "Playback could not start", "Check your internet connection.", -1, false, false,
                new String[]{"Try again", "Choose another episode", "Exit"},
                new Runnable[]{
                        () -> playEpisode(it, it == null ? 0 : watch.resumeAt(it.id)),
                        this::showEpisodes,
                        () -> showHome("")},
                0);
    }

    private void seekBy(long delta) {
        long d = duration();
        long to = Math.max(0, player.getCurrentPosition() + delta);
        if (d > 0) to = Math.min(to, Math.max(0, d - 1000));
        player.seekTo(to);
        setStatus(WatchState.clock(to) + " / " + WatchState.clock(d));
        main.removeCallbacks(clearStatus);
        main.postDelayed(clearStatus, 1500);
    }

    /** Down (or after picking): what is playing and what comes next (hides after 5 s). */
    private void showInfoBanner() {
        if (current < 0 || current >= channels.size()) return;
        ChannelList.Channel ch = channels.get(current);
        bannerChannel.setText("CH " + ch.number + "   " + ch.name);
        ScheduleModel s = sched();
        long now = now();
        if (mode == Mode.VOD && vodItem != null) {
            bannerNow.setText("Episode " + vodItem.id + "  ·  " + describe(vodItem));
            ScheduleModel.Item nx = ScheduleModel.newer(available(), vodItem.id);
            bannerNext.setText(nx != null ? "Newer: Episode " + nx.id : "You are watching the latest episode.");
        } else {
            DateFormat tf = android.text.format.DateFormat.getTimeFormat(this);
            ScheduleModel.Item it = s == null ? null : s.itemAt(now);
            ScheduleModel.Item next = s == null ? null : s.nextAfter(now);
            bannerNow.setText(it != null ? "Live: " + it.title + "  (until " + tf.format(new Date(it.end)) + ")" : "Nothing is live right now.");
            bannerNext.setText(next != null ? "Next: " + next.title + "  at " + tf.format(new Date(next.start)) : "");
        }
        bannerNext.setVisibility(bannerNext.getText().length() > 0 ? View.VISIBLE : View.GONE);
        banner.setVisibility(View.VISIBLE);
        osdNumber.setText(String.valueOf(ch.number));
        osdNumber.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBanner);
        main.postDelayed(hideBanner, 5000);
    }

    // ------------------------------------------------------------------ keys

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (list.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) { hideList(); return true; }   // back to where you were, nothing changed
            return super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (channels.size() > 0) goLive(code == KeyEvent.KEYCODE_CHANNEL_DOWN ? channels.down(current) : channels.up(current));
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_GUIDE) { showEpisodes(); return true; }
        boolean playPause = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE;
        if (!panelKind.isEmpty()) {
            if (code == KeyEvent.KEYCODE_BACK) { panelBack(); return true; }
            if (playPause && mode == Mode.VOD && !"end".equals(panelKind) && !"error".equals(panelKind)) { togglePlay(); return true; }
            if ("controls".equals(panelKind)) {   // any key keeps the controls up a little longer
                main.removeCallbacks(hideControls);
                main.postDelayed(hideControls, CONTROLS_HIDE_MS);
            }
            return super.onKeyDown(code, e);   // the buttons handle their own focus / OK
        }
        if (mode == Mode.VOD) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_DPAD_UP:
                    showControls(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: showInfoBanner(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-SEEK_MS); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(SEEK_MS); return true;
                case KeyEvent.KEYCODE_BACK: showLeave(); return true;
                default:
                    if (playPause) { togglePlay(); return true; }
            }
        }
        if (mode == Mode.LIVE) {
            switch (code) {
                case KeyEvent.KEYCODE_BACK: showHome(""); return true;
                case KeyEvent.KEYCODE_DPAD_UP: if (channels.size() > 0) goLive(channels.up(current)); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (channels.size() > 0) goLive(channels.down(current)); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: showEpisodes(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (digits.length() > 0) commitNumber(); else showInfoBanner();
                    return true;
                default: break;
            }
        }
        if (code == KeyEvent.KEYCODE_BACK && mode == Mode.HOME) { finish(); return true; }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { typeDigit(code - KeyEvent.KEYCODE_0); return true; }
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) { typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); return true; }
        return super.onKeyDown(code, e);
    }

    /** Back on a menu never loses your place. */
    private void panelBack() {
        switch (panelKind) {
            case "home": finish(); break;                                    // top level: leave the app
            case "controls": hidePanel(); break;
            case "paused": case "leave": hidePanel(); player.play(); break;  // = keep watching
            default: showHome(""); break;                                     // end / error screens
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
        if (idx >= 0) goLive(idx);
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
        io.execute(() -> {
            String body;
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
            main.post(() -> onChannels(parsed));
        });
    }

    private void onChannels(ChannelList list) {
        if (isFinishing() || player == null) return;
        if (list.size() == 0) {
            setStatus("No internet connection. Trying again...");
            main.postDelayed(() -> { if (player != null) loadChannels(); }, 10_000);
            return;
        }
        setStatus("");
        channels = list;
        int idx = channels.indexOfId(prefs.getString("last", ""));
        current = idx >= 0 ? idx : 0;
        showHome("");
        refreshSchedule(true);
    }

    /** Live TV on channel idx: plays what is on air now, by server time. */
    private void goLive(int idx) {
        if (idx < 0 || idx >= channels.size()) return;
        saveProgress();
        hidePanel();
        hideList();
        if (idx != current) generation++;
        current = idx;
        mode = Mode.LIVE;
        vodItem = null;
        prefs.edit().putString("last", channels.get(idx).id).apply();
        digits.setLength(0);
        retryAt = 0;
        player.stop();
        player.clearMediaItems();
        setStatus("");
        showInfoBanner();
        refreshSchedule(true);
        sync();
    }

    /** Fetch the current channel's schedule in the background; the result is applied on the main thread. */
    private void refreshSchedule(boolean force) {
        if (current < 0) return;
        long t = System.currentTimeMillis();
        if (!force && t - lastRefresh < SCHEDULE_REFRESH_MS) return;
        lastRefresh = t;
        final int gen = generation;
        final ChannelList.Channel ch = channels.get(current);
        io.execute(() -> {
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
            main.post(() -> {
                if (player == null) return;
                schedules.put(ch.id, m);
                if (gen == generation) sync();
            });
        });
    }

    // ------------------------------------------------------------------ the 2-second tick

    private static ScheduleModel.Item findIn(List<ScheduleModel.Item> l, String id) {
        for (ScheduleModel.Item i : l) if (i.id.equals(id)) return i;
        return null;
    }

    private String playingId() {
        MediaItem mi = player.getCurrentMediaItem();
        return mi == null ? null : mi.mediaId;
    }

    private void setKeepScreenOn(boolean on) {   // idle menus let the TV's screensaver take over (no burn-in)
        if (on == keepOn) return;
        keepOn = on;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateTopBar(ScheduleModel s, long now, long wall) {
        String have = playingId();
        ScheduleModel.Item last = s.lastStartedBy(now);
        String ep = have != null ? have : (last == null ? null : last.id);
        topBar.setText(StatusFormat.topLine(s.updated(), ep, s.aiLeftPct()));
        topBar.setVisibility(View.VISIBLE);
        int d = px(0.006f);   // drift a few pixels every minute so static text cannot burn in
        long m = wall / 60000;
        topBar.setTranslationX((m % 3) * d);
        topBar.setTranslationY(((m / 3) % 3) * d);
        if (!panelKind.isEmpty() && !"controls".equals(panelKind)) {
            panel.setTranslationX((m % 3 - 1) * d);
            panel.setTranslationY(((m / 3) % 3 - 1) * d);
        }
    }

    private void sync() {
        if (player == null || current < 0 || !started) return;
        long wall = System.currentTimeMillis();
        setKeepScreenOn(player.isPlaying());
        refreshSchedule(false);
        ScheduleModel s = sched();
        if (s == null) { if (mode == Mode.LIVE) setStatus("Loading..."); return; }
        long now = clock.now(wall);
        updateTopBar(s, now, wall);
        boolean ended = player.getPlaybackState() == Player.STATE_ENDED;

        if (mode == Mode.HOME) {   // keep the home screen current when a new episode arrives (never touches the player)
            if ("home".equals(panelKind) && s.updated() != homeShownFor && list.getVisibility() != View.VISIBLE) buildHome();
            return;
        }
        if (mode == Mode.VOD) {    // on demand: the schedule never interrupts; only the end of the episode does
            if (vodItem == null) return;
            if (ended && !"end".equals(panelKind)) { showEnd(vodItem); return; }
            if (player.isPlaying() && wall - lastSave > SAVE_EVERY_MS) saveProgress();
            if ("controls".equals(panelKind)) { panelInfo.setText(timeLine()); panelProgress.setProgress(progress()); }
            return;
        }

        // LIVE
        if (wall < retryAt) return;
        ScheduleModel.Item it = s.itemAt(now);
        if (it == null) {
            String have = playingId();
            ScheduleModel.Item done = have == null ? null : findIn(s.available(now), have);
            if (done != null && ended) showEnd(done);                       // the live episode finished
            else if (done == null) showHome("Nothing is live right now.");
            return;
        }
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
            ScheduleModel.Item next = s.nextAfter(now);
            if (ScheduleModel.contiguous(it, next) && player.getMediaItemCount() == 1) {
                player.addMediaItem(new MediaItem.Builder().setMediaId(next.id).setUri(next.url).build());
            }
        }
    }
}
