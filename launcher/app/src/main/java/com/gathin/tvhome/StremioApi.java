package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Stremio account client: QR / link-code login and the library's Continue Watching items.
 * Endpoints and rules follow stremio-core (api.strem.io, link.stremio.com). Call the network methods off
 * the main thread.
 */
final class StremioApi {

    private static final String API = "https://api.strem.io/api/";
    private static final String LINK = "https://link.stremio.com/api/v2/";
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/meta/series/";
    private static final String PREFS = "stremio";
    private static final String KEY_AUTH = "authKey";
    private static final String KEY_CACHE = "continue";
    private static final String KEY_UPCOMING = "upcoming";
    /** Error code api.strem.io returns for a signed-out / expired session. */
    static final int ERR_SESSION = 1;
    private static final int MAX_ITEMS = 12;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** Past this share of an episode you're into the credits: offer the next one instead. */
    private static final double CREDITS = 0.9;
    /** Shows you finished an episode of within this window are checked for new episodes. */
    private static final long FOLLOW_WINDOW_MS = 60 * DAY_MS;
    /** At most this many shows are looked up per refresh (one Cinemeta request each, cached). */
    private static final int MAX_LOOKUPS = 15;
    /** Shows watched within this window (or kept in the library) are followed for Coming next. */
    private static final long UPCOMING_WINDOW_MS = 120 * DAY_MS;
    private static final int MAX_UPCOMING_LOOKUPS = 20;
    private static final int MAX_UPCOMING = 10;
    /** Episode lists change rarely; keep them this long. */
    private static final long EPISODES_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final Map<String, CachedEpisodes> EPISODES = new HashMap<>();

    private StremioApi() {
    }

    static final class ApiException extends IOException {
        final int code;

        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class LinkCode {
        final String code;
        final String link;
        final String qrUrl;

        LinkCode(String code, String link, String qrUrl) {
            this.code = code;
            this.link = link;
            this.qrUrl = qrUrl;
        }
    }

    /** Partly watched: resume where it stopped. */
    static final int KIND_PROGRESS = 0;
    /** A show's next episode, already released and not started yet. */
    static final int KIND_NEXT = 1;
    /** An episode released after you'd caught up with the show. */
    static final int KIND_NEW = 2;

    /**
     * A row entry. Times are in milliseconds, as Stremio stores them. For next / new episodes videoId is that
     * episode, and lastWatched is the sort time (when you last watched the show, or the release for new ones).
     */
    static final class Item {
        final String id;
        final String type;
        final String name;
        final String poster;
        final String videoId;
        final long timeOffset;
        final long duration;
        final long lastWatched;
        final int kind;
        final String episodeName;
        final String thumbnail;

        Item(String id, String type, String name, String poster, String videoId,
             long timeOffset, long duration, long lastWatched) {
            this(id, type, name, poster, videoId, timeOffset, duration, lastWatched, KIND_PROGRESS, null, null);
        }

        Item(String id, String type, String name, String poster, String videoId, long timeOffset, long duration,
             long lastWatched, int kind, String episodeName, String thumbnail) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.poster = poster;
            this.videoId = videoId;
            this.timeOffset = timeOffset;
            this.duration = duration;
            this.lastWatched = lastWatched;
            this.kind = kind;
            this.episodeName = episodeName;
            this.thumbnail = thumbnail;
        }

        /** {season, episode} from a series video id like "tt0944947:1:5", or null. */
        int[] seasonEpisode() {
            if (videoId == null) return null;
            String[] p = videoId.split(":");
            if (p.length < 3) return null;
            try {
                return new int[]{Integer.parseInt(p[p.length - 2]), Integer.parseInt(p[p.length - 1])};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** The episode still when known, else a landscape backdrop for IMDb ids (Stremio's image host), else the poster. */
        String imageUrl() {
            if (thumbnail != null) return thumbnail;
            if (id.startsWith("tt")) return "https://images.metahub.space/background/medium/" + id + "/img";
            return poster;
        }

        /** Opens the title in the Stremio app, on the episode / video that was playing. */
        Uri deepLink() {
            String path = "stremio:///detail/" + Uri.encode(type) + "/" + Uri.encode(id);
            if (videoId != null && !videoId.isEmpty()) path += "/" + Uri.encode(videoId);
            return Uri.parse(path);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("type", type).put("name", name).put("poster", poster)
                    .put("videoId", videoId).put("timeOffset", timeOffset).put("duration", duration)
                    .put("lastWatched", lastWatched).put("kind", kind).put("episodeName", episodeName)
                    .put("thumbnail", thumbnail);
        }

        static Item fromJson(JSONObject o) {
            return new Item(o.optString("id"), o.optString("type"), o.optString("name"),
                    o.isNull("poster") ? null : o.optString("poster"),
                    o.isNull("videoId") ? null : o.optString("videoId"),
                    o.optLong("timeOffset"), o.optLong("duration"), o.optLong("lastWatched"),
                    o.optInt("kind"), o.isNull("episodeName") ? null : o.optString("episodeName"),
                    o.isNull("thumbnail") ? null : o.optString("thumbnail"));
        }
    }

    /** One episode from Cinemeta, Stremio's metadata service. */
    static final class Episode {
        final String id;
        final int season;
        final int episode;
        final String name;
        final long released;
        final String thumbnail;

        Episode(String id, int season, int episode, String name, long released, String thumbnail) {
            this.id = id;
            this.season = season;
            this.episode = episode;
            this.name = name;
            this.released = released;
            this.thumbnail = thumbnail;
        }
    }

    /** A scheduled (not yet released) episode of a show you follow. */
    static final class Upcoming {
        final String showId;
        final String showName;
        final int season;
        final int episode;
        final String episodeName;
        final long released;

        Upcoming(String showId, String showName, int season, int episode, String episodeName, long released) {
            this.showId = showId;
            this.showName = showName;
            this.season = season;
            this.episode = episode;
            this.episodeName = episodeName;
            this.released = released;
        }

        /** Opens the show in the Stremio app. */
        Uri deepLink() {
            return Uri.parse("stremio:///detail/series/" + Uri.encode(showId));
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("showId", showId).put("showName", showName).put("season", season)
                    .put("episode", episode).put("episodeName", episodeName).put("released", released);
        }

        static Upcoming fromJson(JSONObject o) {
            return new Upcoming(o.optString("showId"), o.optString("showName"), o.optInt("season"),
                    o.optInt("episode"), o.isNull("episodeName") ? null : o.optString("episodeName"),
                    o.optLong("released"));
        }
    }

    /** One library fetch: what to continue, and what's coming next. */
    static final class Library {
        final List<Item> watching;
        final List<Upcoming> upcoming;

        Library(List<Item> watching, List<Upcoming> upcoming) {
            this.watching = watching;
            this.upcoming = upcoming;
        }
    }

    private static final class CachedEpisodes {
        final long fetched;
        final List<Episode> episodes;

        CachedEpisodes(long fetched, List<Episode> episodes) {
            this.fetched = fetched;
            this.episodes = episodes;
        }
    }

    // ---- stored state ----------------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String authKey(Context ctx) {
        return prefs(ctx).getString(KEY_AUTH, null);
    }

    static void setAuthKey(Context ctx, String key) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (key == null) e.remove(KEY_AUTH).remove(KEY_CACHE).remove(KEY_UPCOMING);
        else e.putString(KEY_AUTH, key);
        e.apply();
    }

    static List<Upcoming> cachedUpcoming(Context ctx) {
        List<Upcoming> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY_UPCOMING, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Upcoming.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void cacheUpcoming(Context ctx, List<Upcoming> items) {
        JSONArray a = new JSONArray();
        try {
            for (Upcoming u : items) a.put(u.toJson());
        } catch (JSONException ignored) {
        }
        prefs(ctx).edit().putString(KEY_UPCOMING, a.toString()).apply();
    }

    /** The last fetched items, so the row shows instantly (and offline). */
    static List<Item> cached(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY_CACHE, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void cache(Context ctx, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) a.put(it.toJson());
        } catch (JSONException ignored) {
        }
        prefs(ctx).edit().putString(KEY_CACHE, a.toString()).apply();
    }

    // ---- network ---------------------------------------------------------------------------------

    static LinkCode createLink() throws IOException {
        JSONObject r = result(request(LINK + "create?type=Create", null));
        return new LinkCode(r.optString("code"), r.optString("link"), r.optString("qrcode"));
    }

    /** The auth key once the user has logged in on their phone, or null while the code is still pending. */
    static String readLink(String code) throws IOException {
        try {
            JSONObject r = result(request(LINK + "read?type=Read&code=" + Uri.encode(code), null));
            String key = r.optString("authKey", "");
            return key.isEmpty() ? null : key;
        } catch (ApiException pending) {
            return null;
        }
    }

    /**
     * Fetches the library once and builds what's asked for: {@link #watching} and / or {@link #upcoming}.
     * The other list is left empty.
     */
    static Library fetch(String authKey, boolean wantWatching, boolean wantUpcoming) throws IOException {
        JSONArray all = library(authKey);
        long now = System.currentTimeMillis();
        return new Library(wantWatching ? watching(all, now) : new ArrayList<>(),
                wantUpcoming ? upcoming(all, now) : new ArrayList<>());
    }

    private static JSONArray library(String authKey) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("authKey", authKey).put("collection", "libraryItem").put("ids", new JSONArray()).put("all", true);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return resultArray(request(API + "datastoreGet", body.toString()));
    }

    /**
     * The next scheduled episode of each show you follow (watched in the last four months, or kept in the
     * library), soonest first. Only IMDb ("tt") shows, via Cinemeta.
     */
    private static List<Upcoming> upcoming(JSONArray all, long now) {
        List<Item> followed = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || !"series".equals(o.optString("type")) || !o.optString("_id").startsWith("tt")) continue;
            boolean removed = o.optBoolean("removed"), temp = o.optBoolean("temp");
            if (removed && !temp) continue;
            JSONObject state = o.optJSONObject("state");
            long lastWatched = state != null ? parseTime(state.optString("lastWatched")) : 0L;
            boolean inLibrary = !removed && !temp;
            if (!inLibrary && now - lastWatched > UPCOMING_WINDOW_MS) continue;
            followed.add(new Item(o.optString("_id"), "series", o.optString("name"), null, null, 0, 0, lastWatched));
        }
        Collections.sort(followed, (a, b) -> Long.compare(b.lastWatched, a.lastWatched));

        List<Upcoming> out = new ArrayList<>();
        for (int i = 0; i < followed.size() && i < MAX_UPCOMING_LOOKUPS; i++) {
            Item show = followed.get(i);
            List<Episode> eps = episodes(show.id);
            if (eps == null) continue;
            for (Episode e : eps) {
                if (e.season > 0 && e.released > now) {
                    out.add(new Upcoming(show.id, show.name, e.season, e.episode, e.name, e.released));
                    break;
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.released, b.released));
        return out.size() > MAX_UPCOMING ? new ArrayList<>(out.subList(0, MAX_UPCOMING)) : out;
    }

    /**
     * What to watch next, most recent first:
     * <ul>
     * <li>Titles in progress, by stremio-core's is_in_continue_watching rule: type isn't "other", not removed
     * (or temporary), and playback has started.</li>
     * <li>A show's next episode. Stremio itself moves a finished show on to the next episode with a 1 ms
     * position; shows stopped in the credits get the same treatment here.</li>
     * <li>New episodes of shows you'd caught up with (position 0, watched in the last two months), like
     * Stremio's new-episode notifications.</li>
     * </ul>
     * Episode details come from Cinemeta, so only IMDb ("tt") shows get next / new episodes.
     */
    private static List<Item> watching(JSONArray all, long now) {
        List<Item> items = new ArrayList<>();
        List<Item> shows = new ArrayList<>();   // need an episode lookup
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("type");
            JSONObject state = o.optJSONObject("state");
            if (state == null || "other".equals(type)) continue;
            if (o.optBoolean("removed") && !o.optBoolean("temp")) continue;
            Item it = new Item(o.optString("_id"), type, o.optString("name"),
                    nonEmpty(o.optString("poster")), nonEmpty(state.optString("video_id")),
                    state.optLong("timeOffset"), state.optLong("duration"), parseTime(state.optString("lastWatched")));
            boolean episodic = "series".equals(type) && it.id.startsWith("tt") && it.videoId != null;
            boolean advanced = it.timeOffset == 1;
            boolean inCredits = it.timeOffset > 1 && it.duration > 0 && it.timeOffset >= it.duration * CREDITS;
            boolean caughtUp = it.timeOffset == 0 && now - it.lastWatched < FOLLOW_WINDOW_MS;
            if (episodic && (advanced || inCredits || caughtUp)) shows.add(it);
            else if (it.timeOffset > 0) items.add(it);
        }

        Collections.sort(shows, (a, b) -> Long.compare(b.lastWatched, a.lastWatched));
        for (int i = 0; i < shows.size(); i++) {
            Item show = shows.get(i);
            Item next = i < MAX_LOOKUPS ? nextEpisode(show, now) : null;
            if (next != null) {
                items.add(next);
            } else if (show.timeOffset == 1) {
                // Stremio moved it on but Cinemeta couldn't say more: still the next episode, without details.
                items.add(new Item(show.id, show.type, show.name, show.poster, show.videoId, 0, 0,
                        show.lastWatched, KIND_NEXT, null, null));
            } else if (show.timeOffset > 1) {
                items.add(show);   // in the credits of the show's latest episode: keep it as in progress
            }
        }

        Collections.sort(items, (a, b) -> Long.compare(b.lastWatched, a.lastWatched));
        return items.size() > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;
    }

    /** The episode to offer for a show (see {@link #watching}), or null when none is out yet. */
    private static Item nextEpisode(Item show, long now) {
        List<Episode> eps = episodes(show.id);
        if (eps == null) return null;
        Episode target = null;
        for (int i = 0; i < eps.size(); i++) {
            if (!eps.get(i).id.equals(show.videoId)) continue;
            if (show.timeOffset == 1) {
                target = eps.get(i);   // Stremio already moved the show on to this episode
            } else {
                for (int j = i + 1; j < eps.size() && target == null; j++) {
                    if (eps.get(j).season > 0) target = eps.get(j);   // skip specials
                }
            }
            break;
        }
        if (target == null || target.released <= 0 || target.released > now) return null;
        boolean isNew = show.timeOffset == 0 && target.released > show.lastWatched;
        return new Item(show.id, show.type, show.name, show.poster, target.id, 0, 0,
                isNew ? target.released : show.lastWatched, isNew ? KIND_NEW : KIND_NEXT, target.name, target.thumbnail);
    }

    /** A show's episodes in order (season, episode), cached for a few hours; null if Cinemeta can't say. */
    private static List<Episode> episodes(String showId) {
        synchronized (EPISODES) {
            CachedEpisodes c = EPISODES.get(showId);
            if (c != null && System.currentTimeMillis() - c.fetched < EPISODES_TTL_MS) return c.episodes;
        }
        List<Episode> eps = new ArrayList<>();
        try {
            JSONObject meta = new JSONObject(request(CINEMETA + Uri.encode(showId) + ".json", null)).optJSONObject("meta");
            JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
            if (videos == null) return null;
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.optJSONObject(i);
                if (v == null || v.optString("id").isEmpty()) continue;
                String name = nonEmpty(v.optString("name"));
                if (name == null) name = nonEmpty(v.optString("title"));
                eps.add(new Episode(v.optString("id"), v.optInt("season"), v.optInt("episode", v.optInt("number")),
                        name, parseTime(v.optString("released")), nonEmpty(v.optString("thumbnail"))));
            }
        } catch (IOException | JSONException e) {
            return null;
        }
        Collections.sort(eps, (a, b) -> a.season != b.season
                ? Integer.compare(a.season, b.season) : Integer.compare(a.episode, b.episode));
        synchronized (EPISODES) {
            EPISODES.put(showId, new CachedEpisodes(System.currentTimeMillis(), eps));
        }
        return eps;
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static long parseTime(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static JSONObject result(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            throwIfError(o);
            return o.getJSONObject("result");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static JSONArray resultArray(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            throwIfError(o);
            return o.getJSONArray("result");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static void throwIfError(JSONObject o) throws ApiException {
        JSONObject err = o.optJSONObject("error");
        if (err != null) throw new ApiException(err.optInt("code"), err.optString("message"));
    }

    /** GET when body is null, otherwise POST it as JSON. Returns the response body (also for HTTP errors). */
    private static String request(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream is = in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
