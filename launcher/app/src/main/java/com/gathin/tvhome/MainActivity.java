package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.app.LocaleManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that glides between tiles.
 */
public class MainActivity extends Activity
        implements AppAdapter.TileListener, ContinueAdapter.CardListener, UpcomingAdapter.Listener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /** Re-fetch Stremio on return to Home at most this often. */
    private static final long STREMIO_REFRESH_MS = 30_000L;
    /** How long the QR login waits for the phone. */
    private static final long LOGIN_TIMEOUT_MS = 10 * MINUTE_MS;
    /** Apps used within this window can appear in Continue watching. */
    private static final long RECENT_WINDOW_MS = 14 * 24 * 60 * MINUTE_MS;
    private static final int MAX_RECENT_APPS = 6;
    private static final int MAX_CONTINUE = 14;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    /** Position of the tile being moved with the D-pad, or -1 when not moving. */
    private int movingPos = -1;
    private float density;

    private ContinueAdapter continueAdapter;
    private View continueBlock;
    private RecyclerView continueRow;
    private boolean continueShown = true;
    private List<StremioApi.Item> stremioItems = new ArrayList<>();
    private UpcomingAdapter upcomingAdapter;
    private List<StremioApi.Upcoming> upcomingItems = new ArrayList<>();
    private long lastStremioFetch;
    /** Network work (Stremio): one thread, so requests never overlap. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        GridLayoutManager layout = new GridLayoutManager(this, COLUMNS);
        // Tiles take one cell; the Coming next heading and rows after them span the full width.
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < adapter.getItemCount() ? 1 : COLUMNS;
            }
        });
        grid.setLayoutManager(layout);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        upcomingAdapter = new UpcomingAdapter(this);
        // App positions come first, so tile positions are the same in the grid and in AppAdapter.
        // Stable ids (each adapter its own), so a focused tile keeps its app across reloads.
        ConcatAdapter.Config config = new ConcatAdapter.Config.Builder()
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build();
        grid.setAdapter(new ConcatAdapter(config, adapter, upcomingAdapter));

        continueBlock = findViewById(R.id.continueBlock);
        continueRow = findViewById(R.id.continueRow);
        continueRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        continueRow.setItemAnimator(null);
        continueAdapter = new ContinueAdapter(this);
        continueRow.setAdapter(continueAdapter);
        stremioItems = StremioApi.cached(this);
        upcomingItems = StremioApi.cachedUpcoming(this);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Smooth glide between tiles: a critically damped spring settles without overshooting (no bounce).
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(700f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> showSettingsMenu());

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        continueShown = true;
        continueBlock.setAlpha(1f);
        continueRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        refreshContinue(false);
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        if (movingPos >= 0) finishMove();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        setContinueShown(position < COLUMNS);   // the grid scrolls up into the row's space
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                // Last row of apps: carry on into Coming next if it's there, otherwise stay put.
                if (upcomingAdapter.getItemCount() > 0) focusTile(count + UpcomingAdapter.FIRST_ROW);
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    /** Focuses the grid item at position: an app tile, or a Coming next row (which follow the tiles). */
    private void focusTile(int position) {
        int total = grid.getAdapter() != null ? grid.getAdapter().getItemCount() : 0;
        if (total == 0) return;
        final int pos = Math.max(0, Math.min(position, total - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View target = focusTarget(vh);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (target.isFocused() && target.getId() == R.id.card) onTileFocused(pos, target);
            else target.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? focusTarget(h) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    /** The focusable view of a grid item: a tile's card, or the whole Coming next row. */
    private static View focusTarget(RecyclerView.ViewHolder vh) {
        View card = vh.itemView.findViewById(R.id.card);
        return card != null ? card : vh.itemView;
    }

    /** Runs once after the grid's next layout pass, when its view holders match the adapter again. */
    private void afterGridLayout(Runnable r) {
        afterLayout(grid, r);
    }

    private static void afterLayout(View view, Runnable r) {
        view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
    }

    // ---- continue watching -----------------------------------------------------------------------

    @Override
    public void onCardClick(ContinueAdapter.Card card) {
        open(new Intent(card.intent));
    }

    @Override
    public void onCardDown() {
        focusTile(lastFocused < COLUMNS ? lastFocused : 0);
    }

    @Override
    public void onCardFocused() {
        setContinueShown(true);
    }

    private void setContinueShown(boolean show) {
        if (show == continueShown) return;
        continueShown = show;
        continueBlock.animate().alpha(show ? 1f : 0f).setDuration(show ? 260 : 160).start();
        // Faded out it must not take focus either, or Up from Coming next would jump into it.
        continueRow.setDescendantFocusability(show
                ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /** Rebuilds the row from cached Stremio items and app usage, then re-fetches Stremio if it's due. */
    private void refreshContinue(boolean force) {
        rebuildContinueRow();
        rebuildUpcoming();
        boolean wantWatching = isFeatureOn(FEATURE_CONTINUE), wantUpcoming = isFeatureOn(FEATURE_UPCOMING);
        String key = StremioApi.authKey(this);
        long now = SystemClock.elapsedRealtime();
        if (key == null || !(wantWatching || wantUpcoming)) return;
        if (!force && lastStremioFetch != 0 && now - lastStremioFetch < STREMIO_REFRESH_MS) return;
        lastStremioFetch = now;
        io.execute(() -> {
            try {
                StremioApi.Library lib = StremioApi.fetch(key, wantWatching, wantUpcoming);
                if (wantWatching) StremioApi.cache(this, lib.watching);
                if (wantUpcoming) StremioApi.cacheUpcoming(this, lib.upcoming);
                handler.post(() -> {
                    if (wantWatching) stremioItems = lib.watching;
                    if (wantUpcoming) upcomingItems = lib.upcoming;
                    rebuildContinueRow();
                    rebuildUpcoming();
                });
            } catch (StremioApi.ApiException e) {
                if (e.code == StremioApi.ERR_SESSION) handler.post(this::disconnectStremio);
            } catch (Exception ignored) {
                // Offline or Stremio down: keep showing the cached lists.
            }
        });
    }

    private void rebuildUpcoming() {
        upcomingAdapter.setItems(isFeatureOn(FEATURE_UPCOMING) ? upcomingItems : new ArrayList<>());
    }

    @Override
    public void onUpcomingClick(StremioApi.Upcoming item) {
        open(new Intent(Intent.ACTION_VIEW, item.deepLink()));
    }

    @Override
    public void onUpcomingFocused() {
        // Below the grid: same as browsing the tiles, the top area stays clear.
        setContinueShown(false);
        hideHero();
    }

    // ---- feature switches (Settings menu) ----------------------------------------------------------

    private static final String FEATURE_CONTINUE = "continue";
    private static final String FEATURE_UPCOMING = "upcoming";

    private boolean isFeatureOn(String feature) {
        return getSharedPreferences("features", MODE_PRIVATE).getBoolean(feature, true);
    }

    /** Turns a section on or off right away; turning one on fetches it if Stremio is connected. */
    private void setFeatureOn(String feature, boolean on) {
        getSharedPreferences("features", MODE_PRIVATE).edit().putBoolean(feature, on).apply();
        refreshContinue(on);
    }

    private void rebuildContinueRow() {
        List<ContinueAdapter.Card> cards = new ArrayList<>();
        if (isFeatureOn(FEATURE_CONTINUE)) {
            for (StremioApi.Item it : stremioItems) cards.add(stremioCard(it));
            cards.addAll(recentAppCards());
        }
        Collections.sort(cards, (a, b) -> Long.compare(b.time, a.time));
        if (cards.size() > MAX_CONTINUE) cards = new ArrayList<>(cards.subList(0, MAX_CONTINUE));

        View focused = getCurrentFocus();
        RecyclerView.ViewHolder focusedVh = focused != null ? continueRow.findContainingViewHolder(focused) : null;
        if (!continueAdapter.setCards(cards)) return;

        boolean any = !cards.isEmpty();
        continueBlock.setVisibility(any ? View.VISIBLE : View.GONE);
        // The row takes the top shelf's place above the dock.
        heroName.setVisibility(any ? View.INVISIBLE : View.VISIBLE);
        int down = any ? R.id.continueRow : R.id.grid;
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
            findViewById(id).setNextFocusDownId(down);
        }
        if (focusedVh != null) {
            // The refresh replaced the focused card: put focus back on the same slot.
            int pos = Math.min(focusedVh.getBindingAdapterPosition(), cards.size() - 1);
            if (pos < 0) {
                focusTile(0);
            } else {
                afterLayout(continueRow, () -> {
                    RecyclerView.ViewHolder vh = continueRow.findViewHolderForAdapterPosition(pos);
                    if (vh != null) vh.itemView.findViewById(R.id.continueCard).requestFocus();
                });
            }
        }
    }

    private ContinueAdapter.Card stremioCard(StremioApi.Item it) {
        List<String> parts = new ArrayList<>();
        int[] se = it.seasonEpisode();
        if (se != null) parts.add(getString(R.string.season_episode, se[0], se[1]));
        float progress = -1f;
        if (it.kind != StremioApi.KIND_PROGRESS) {
            // Next / new episode: no progress yet, so its title instead of the time left.
            if (it.episodeName != null) parts.add(it.episodeName);
        } else if (it.duration > 0) {
            progress = (float) it.timeOffset / it.duration;
            long left = Math.max(1, (it.duration - it.timeOffset) / MINUTE_MS);
            parts.add(getString(R.string.time_left, (int) left));
        }
        ContinueAdapter.Card card = new ContinueAdapter.Card(it.name, String.join(" · ", parts), it.imageUrl(), null,
                progress, it.lastWatched, new Intent(Intent.ACTION_VIEW, it.deepLink()));
        if (it.kind == StremioApi.KIND_NEXT) card.withBadge(getString(R.string.badge_next), false);
        if (it.kind == StremioApi.KIND_NEW) card.withBadge(getString(R.string.badge_new), true);
        return card;
    }

    /**
     * Recently used apps, which reopen where the app left off. Needs usage access (granted over adb); without
     * it the list is empty. With Stremio connected its app is left out, since its titles are listed instead.
     */
    private List<ContinueAdapter.Card> recentAppCards() {
        List<ContinueAdapter.Card> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        Map<String, UsageStats> usage;
        try {
            usage = usm.queryAndAggregateUsageStats(now - RECENT_WINDOW_MS, now);
        } catch (Exception e) {
            return out;
        }
        boolean stremio = StremioApi.authKey(this) != null;
        List<String> excluded = Arrays.asList(getResources().getStringArray(R.array.continue_exclude));
        for (AppAdapter.Entry e : adapter.allApps()) {
            if ((stremio && e.pkg.startsWith("com.stremio")) || adapter.isHidden(e.pkg) || excluded.contains(e.pkg)) {
                continue;
            }
            UsageStats u = usage.get(e.pkg);
            // Skip apps that were only opened by accident.
            if (u == null || u.getLastTimeUsed() <= 0 || u.getTotalTimeInForeground() < MINUTE_MS) continue;
            Drawable art = e.banner != null ? e.banner : e.icon;
            // A private copy: the grid tile shows the same drawable, and sharing it would fight over bounds.
            if (art != null && art.getConstantState() != null) art = art.getConstantState().newDrawable(getResources());
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(e.component);
            out.add(new ContinueAdapter.Card(e.label, ago(u.getLastTimeUsed(), now), null, art, -1f,
                    u.getLastTimeUsed(), launch));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out.size() > MAX_RECENT_APPS ? new ArrayList<>(out.subList(0, MAX_RECENT_APPS)) : out;
    }

    private String ago(long then, long now) {
        long min = Math.max(0, now - then) / MINUTE_MS;
        if (min < 1) return getString(R.string.just_now);
        if (min < 60) return getString(R.string.minutes_ago, (int) min);
        if (min < 24 * 60) return getString(R.string.hours_ago, (int) (min / 60));
        return getString(R.string.days_ago, (int) (min / (24 * 60)));
    }

    private void disconnectStremio() {
        StremioApi.setAuthKey(this, null);
        stremioItems = new ArrayList<>();
        upcomingItems = new ArrayList<>();
        rebuildContinueRow();
        rebuildUpcoming();
    }

    /** QR login: the phone confirms the code at link.stremio.com and we poll for the resulting auth key. */
    private void showStremioLogin() {
        View panel = getLayoutInflater().inflate(R.layout.popup_stremio, root, false);
        ImageView qr = panel.findViewById(R.id.qr);
        TextView link = panel.findViewById(R.id.link);
        TextView status = panel.findViewById(R.id.status);
        PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setAnimationStyle(0);
        AtomicBoolean open = new AtomicBoolean(true);
        popup.setOnDismissListener(() -> open.set(false));
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);

        io.execute(() -> {
            StremioApi.LinkCode code;
            try {
                code = StremioApi.createLink();
            } catch (Exception e) {
                handler.post(() -> status.setText(R.string.stremio_unreachable));
                return;
            }
            handler.post(() -> {
                link.setText(code.link.replaceFirst("^https?://", ""));
                ImageLoader.load(qr, code.qrUrl, (int) (180 * density));
            });
            long deadline = SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS;
            while (open.get() && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
                String key;
                try {
                    key = StremioApi.readLink(code.code);
                } catch (Exception e) {
                    continue;   // network blip: keep waiting
                }
                if (key != null) {
                    handler.post(() -> {
                        StremioApi.setAuthKey(this, key);
                        if (popup.isShowing()) popup.dismiss();
                        Toast.makeText(this, R.string.stremio_connected, Toast.LENGTH_SHORT).show();
                        refreshContinue(true);
                    });
                    return;
                }
            }
            if (open.get()) handler.post(() -> status.setText(R.string.stremio_expired));
        });
    }

    // ---- arranging tiles -------------------------------------------------------------------------

    @Override
    public void onTileMenu(int position, View card) {
        AppAdapter.Entry e = adapter.entryAt(position);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newPanel(panel, (int) (260 * density));
        addMenuRow(rows, null, getString(R.string.move), null, false, v -> {
            popup.dismiss();
            startMove(position);
        });
        addMenuRow(rows, null, getString(R.string.hide), null, false, v -> {
            popup.dismiss();
            adapter.setHidden(e.pkg, true);
            adapter.reload();
            afterGridLayout(() -> focusTile(position));
        });
        addMenuRow(rows, null, getString(R.string.app_info), null, false, v -> {
            popup.dismiss();
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)));
        });
        addMenuRow(rows, null, getString(R.string.uninstall), null, false, v -> {
            popup.dismiss();
            open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)));
        });
        showPanel(popup, panel, card, Gravity.START);
    }

    /** Move mode: the D-pad pushes the focused tile around; OK or Back ends it. The order saves on every step. */
    private void startMove(int position) {
        movingPos = position;
        greeting.setText(getString(R.string.moving_hint, adapter.labelAt(position)));
    }

    private void finishMove() {
        movingPos = -1;
        updateGreeting();
    }

    private void moveBy(int delta) {
        int raw = movingPos + delta;
        if (raw < 0) return;
        final int to = Math.min(raw, adapter.getItemCount() - 1);
        if (to == movingPos) return;
        adapter.move(movingPos, to);
        movingPos = to;
        grid.invalidateItemDecorations();   // dock spacing depends on position
        afterGridLayout(() -> {
            for (int i = 0; i < grid.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = grid.getChildViewHolder(grid.getChildAt(i));
                if (!(vh instanceof AppAdapter.Holder)) continue;   // Coming next rows
                int p = vh.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) adapter.applyDockLabel((AppAdapter.Holder) vh, p);
            }
            // The moved tile keeps focus, so no focus event fires: move the highlight by hand.
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(to);
            View card = vh != null ? vh.itemView.findViewById(R.id.card) : null;
            if (card != null && card.isFocused()) onTileFocused(to, card);
            else focusTile(to);
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (movingPos < 0) return super.dispatchKeyEvent(event);
        int delta;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: delta = -COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: delta = COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                // Finish on release, so the key-up doesn't reach the tile and launch the app.
                if (event.getAction() == KeyEvent.ACTION_UP) finishMove();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) moveBy(delta);
        return true;
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = newPanel(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvIntent(tim);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        showPanel(popup, panel, anchor, Gravity.START);
    }

    // ---- settings menu ---------------------------------------------------------------------------

    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newPanel(panel, (int) (280 * density));
        addMenuRow(rows, null, getString(R.string.apps), null, false, v -> {
            popup.dismiss();
            showAppsMenu();
        });
        // Per-app language needs Android 13; TVs rarely offer Catalan as a system language.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addMenuRow(rows, null, getString(R.string.language), languageName(currentLanguage()), false, v -> {
                popup.dismiss();
                showLanguageMenu();
            });
        }
        // Section switches: toggle in place, the menu stays open.
        for (String[] f : new String[][]{{FEATURE_CONTINUE, getString(R.string.continue_watching)},
                {FEATURE_UPCOMING, getString(R.string.coming_next)}}) {
            boolean on = isFeatureOn(f[0]);
            View row = addMenuRow(rows, null, f[1], getString(on ? R.string.on : R.string.off), on, null);
            row.setOnClickListener(v -> {
                boolean now = !isFeatureOn(f[0]);
                setFeatureOn(f[0], now);
                setRowStatus(row, getString(now ? R.string.on : R.string.off), now);
            });
        }
        boolean stremio = StremioApi.authKey(this) != null;
        addMenuRow(rows, null, getString(stremio ? R.string.stremio_disconnect : R.string.stremio_connect), null,
                stremio, v -> {
                    popup.dismiss();
                    if (StremioApi.authKey(this) != null) disconnectStremio();
                    else showStremioLogin();
                });
        // The system's app list: force stop, clear data, permissions (a launcher can't force stop apps itself).
        addMenuRow(rows, null, getString(R.string.manage_apps), null, false, v -> {
            popup.dismiss();
            open(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        });
        addMenuRow(rows, null, getString(R.string.android_settings), null, false, v -> {
            popup.dismiss();
            open(new Intent(Settings.ACTION_SETTINGS));
        });
        showPanel(popup, panel, anchor, Gravity.END);
    }

    /** Every app with a Shown/Hidden toggle; the tiles update when the menu closes. */
    private void showAppsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newPanel(panel, (int) (360 * density));
        for (AppAdapter.Entry e : adapter.allApps()) {
            boolean hidden = adapter.isHidden(e.pkg);
            View row = addMenuRow(rows, e.icon, e.label, getString(hidden ? R.string.hidden : R.string.shown), !hidden, null);
            row.setOnClickListener(v -> {
                boolean hide = !adapter.isHidden(e.pkg);
                adapter.setHidden(e.pkg, hide);
                setRowStatus(row, getString(hide ? R.string.hidden : R.string.shown), !hide);
            });
        }
        popup.setOnDismissListener(() -> adapter.reload());
        showPanel(popup, panel, anchor, Gravity.END);
    }

    private void showLanguageMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newPanel(panel, (int) (280 * density));
        String current = currentLanguage();
        for (String tag : new String[]{"", "ca", "en"}) {
            addMenuRow(rows, null, languageName(tag), null, tag.equals(current), v -> {
                popup.dismiss();
                setLanguage(tag);   // the system recreates the activity in the new language
            });
        }
        showPanel(popup, panel, anchor, Gravity.END);
    }

    /** The app's own language ("" = follow the system). */
    private String currentLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "";
        LocaleList l = getSystemService(LocaleManager.class).getApplicationLocales();
        return l.isEmpty() ? "" : l.get(0).getLanguage();
    }

    private void setLanguage(String tag) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        getSystemService(LocaleManager.class).setApplicationLocales(
                tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
    }

    private String languageName(String tag) {
        switch (tag) {
            case "ca": return getString(R.string.lang_ca);
            case "en": return getString(R.string.lang_en);
            default: return getString(R.string.lang_system);
        }
    }

    // ---- dropdown panels -------------------------------------------------------------------------

    /** A dropdown panel built from popup_inputs; fill its rows, then call {@link #showPanel}. */
    private PopupWindow newPanel(View panel, int width) {
        PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    /**
     * Drops the panel in below the anchor (or above it when there's no room, e.g. for the dock), capped in
     * height (the rows scroll), and focuses the first row.
     */
    private void showPanel(PopupWindow popup, View panel, View anchor, int gravity) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        panel.measure(View.MeasureSpec.makeMeasureSpec(popup.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(panel.getMeasuredHeight(), (int) (screenHeight * 0.6f));
        popup.setHeight(height);

        // Leave room for a focused tile's zoom, which overhangs its layout bounds.
        float overhang = anchor.getHeight() * (anchor.getScaleY() - 1f) / 2f;
        int gap = (int) (10 * density + overhang);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        boolean below = loc[1] + anchor.getHeight() + gap + height <= screenHeight;
        int yOff = below ? gap : -(anchor.getHeight() + gap + height);

        // Drop in: fade and unfold from the edge nearest the anchor.
        panel.setAlpha(0f);
        panel.setPivotY(below ? 0f : height);
        panel.setScaleY(0.92f);
        panel.setTranslationY((below ? -8 : 8) * density);
        popup.showAsDropDown(anchor, 0, yOff, gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        LinearLayout rows = panel.findViewById(R.id.rows);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    /** A menu row (item_input): optional app icon, title, optional status text and dot. */
    private View addMenuRow(LinearLayout rows, android.graphics.drawable.Drawable icon, String title,
                            String status, boolean dot, View.OnClickListener click) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ImageView iv = row.findViewById(R.id.icon);
        if (icon != null) {
            iv.setImageDrawable(icon);
            iv.setImageTintList(null);   // app icons keep their colours
        } else {
            iv.setVisibility(View.GONE);
        }
        TextView t = row.findViewById(R.id.title);
        t.setText(title);
        if (icon == null) ((LinearLayout.LayoutParams) t.getLayoutParams()).setMarginStart(0);
        setRowStatus(row, status, dot);
        if (click != null) row.setOnClickListener(click);
        rows.addView(row);
        return row;
    }

    private void setRowStatus(View row, String status, boolean dot) {
        TextView s = row.findViewById(R.id.status);
        s.setText(status);
        s.setVisibility(status == null ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.dot).setVisibility(dot ? View.VISIBLE : View.GONE);
    }

    /**
     * How to open Live TV, or null when there's none. The live_tv_package setting wins; otherwise only on a
     * TV with a built-in (hardware) tuner, not on a dongle: the app that views the channel list, else a
     * known Live TV app, else the tuner's own app.
     */
    private Intent liveTvIntent(TvInputManager tim) {
        PackageManager pm = getPackageManager();
        if (!liveTvPkg.isEmpty()) return pm.getLeanbackLaunchIntentForPackage(liveTvPkg);
        String tunerPkg = hardwareTunerPackage(tim);
        if (tunerPkg == null) return null;
        Intent channels = new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.Channels.CONTENT_URI, TvContract.Channels.CONTENT_TYPE);
        if (channels.resolveActivity(pm) != null) return channels;
        for (String pkg : getResources().getStringArray(R.array.live_tv_candidates)) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return pm.getLeanbackLaunchIntentForPackage(tunerPkg);
    }

    /**
     * The package of a built-in tuner input, or null. Streaming apps (3Cat, Play Movies…) register tuner-type
     * inputs too, so only inputs provided by the system count as a real tuner.
     */
    private static String hardwareTunerPackage(TvInputManager tim) {
        if (tim == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_TUNER) continue;
            ServiceInfo si = info.getServiceInfo();
            if (si != null && (si.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return si.packageName;
        }
        return null;
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
