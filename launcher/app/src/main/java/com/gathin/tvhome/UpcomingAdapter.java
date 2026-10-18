package com.gathin.tvhome;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Coming next" under the app grid: a heading and one row per scheduled episode of a followed show. Lives in
 * the same RecyclerView as the tiles (after them, full width), so the D-pad and scrolling just carry on.
 */
public class UpcomingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onUpcomingClick(StremioApi.Upcoming item);

        void onUpcomingFocused();
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;

    private final Listener listener;
    private final List<StremioApi.Upcoming> items = new ArrayList<>();

    UpcomingAdapter(Listener listener) {
        this.listener = listener;
    }

    /** Replaces the rows; does nothing when they're the same (a refresh would otherwise drop focus). */
    void setItems(List<StremioApi.Upcoming> fresh) {
        if (fresh.size() == items.size()) {
            boolean same = true;
            for (int i = 0; i < fresh.size() && same; i++) {
                StremioApi.Upcoming a = fresh.get(i), b = items.get(i);
                same = a.showId.equals(b.showId) && a.season == b.season && a.episode == b.episode
                        && a.released == b.released;
            }
            if (same) return;
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** Adapter position of the first row (after the heading). */
    static final int FIRST_ROW = 1;

    @Override
    public int getItemCount() {
        return items.isEmpty() ? 0 : items.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_ROW;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new RecyclerView.ViewHolder(inf.inflate(R.layout.item_upcoming_header, parent, false)) {
            };
        }
        return new RowHolder(inf.inflate(R.layout.item_upcoming, parent, false), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (!(holder instanceof RowHolder)) return;
        RowHolder h = (RowHolder) holder;
        StremioApi.Upcoming u = items.get(position - 1);
        Context ctx = h.itemView.getContext();
        h.date.setText(dateLabel(ctx, u.released));
        h.show.setText(u.showName);
        String ep = ctx.getString(R.string.season_episode, u.season, u.episode);
        h.episode.setText(hasRealName(u.episodeName) ? ep + " · " + u.episodeName : ep);
        h.itemView.setOnClickListener(v -> listener.onUpcomingClick(u));
    }

    /** Cinemeta fills unnamed future episodes with placeholders like "TBA". */
    private static boolean hasRealName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return !n.isEmpty() && !n.equalsIgnoreCase("TBA") && !n.equalsIgnoreCase("TBD");
    }

    /**
     * "Today", "Tomorrow", else a short weekday + date in the app's language (e.g. "dv., 23 d’oct."), with
     * the year when it isn't this one.
     */
    static String dateLabel(Context ctx, long when) {
        LocalDate day = Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return ctx.getString(R.string.today);
        if (day.equals(today.plusDays(1))) return ctx.getString(R.string.tomorrow);
        Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
        String skeleton = day.getYear() == today.getYear() ? "EEEdMMM" : "EEEdMMMy";
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton);
        return new SimpleDateFormat(pattern, loc).format(new Date(when));
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView show;
        final TextView episode;

        RowHolder(View v, Listener listener) {
            super(v);
            date = v.findViewById(R.id.date);
            show = v.findViewById(R.id.show);
            episode = v.findViewById(R.id.episode);
            v.setOnFocusChangeListener((view, focused) -> {
                if (focused) listener.onUpcomingFocused();
            });
        }
    }
}
