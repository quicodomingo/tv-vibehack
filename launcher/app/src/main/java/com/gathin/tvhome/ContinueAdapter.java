package com.gathin.tvhome;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The "Continue watching" row: Stremio titles in progress and recently used streaming apps, newest first.
 */
public class ContinueAdapter extends RecyclerView.Adapter<ContinueAdapter.Holder> {

    interface CardListener {
        void onCardClick(Card card);

        /** D-pad Down on a card: move focus to the dock. */
        void onCardDown();

        void onCardFocused();
    }

    /**
     * One card: a remote image (Stremio) or an app banner, with an optional progress bar (0-1, or -1) and an
     * optional badge (e.g. "Next"); a strong badge (new episode) is white, the others dark.
     */
    static final class Card {
        final String title;
        final String subtitle;
        final String imageUrl;
        final Drawable banner;
        final float progress;
        final long time;
        final Intent intent;
        String badge;
        boolean badgeStrong;

        Card(String title, String subtitle, String imageUrl, Drawable banner, float progress, long time, Intent intent) {
            this.title = title;
            this.subtitle = subtitle;
            this.imageUrl = imageUrl;
            this.banner = banner;
            this.progress = progress;
            this.time = time;
            this.intent = intent;
        }

        Card withBadge(String text, boolean strong) {
            badge = text;
            badgeStrong = strong;
            return this;
        }

        /** What the card shows; used to skip redundant refreshes (which would also drop focus). */
        String signature() {
            return title + '|' + subtitle + '|' + imageUrl + '|' + Math.round(progress * 100) + '|' + badge;
        }
    }

    static final float FOCUS_SCALE = 1.08f;

    private final CardListener listener;
    private final List<Card> cards = new ArrayList<>();

    ContinueAdapter(CardListener listener) {
        this.listener = listener;
    }

    /** Replaces the cards; returns false (and does nothing) when nothing visible changed. */
    boolean setCards(List<Card> fresh) {
        if (fresh.size() == cards.size()) {
            boolean same = true;
            for (int i = 0; i < fresh.size() && same; i++) {
                same = Objects.equals(fresh.get(i).signature(), cards.get(i).signature());
            }
            if (same) return false;
        }
        cards.clear();
        cards.addAll(fresh);
        notifyDataSetChanged();
        return true;
    }

    /** Rebinds the cards in place, so images that failed to load (e.g. offline at boot) are fetched again. */
    void retryImages() {
        notifyItemRangeChanged(0, cards.size());
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_continue, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Card c = cards.get(position);
        h.title.setText(c.title);
        h.subtitle.setText(c.subtitle);
        h.subtitle.setVisibility(c.subtitle == null || c.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        if (c.imageUrl != null) {
            ImageLoader.load(h.image, c.imageUrl, h.image.getLayoutParams().width);
        } else {
            h.image.setTag(R.id.image_url, null);
            h.image.setImageDrawable(c.banner);
        }
        if (c.progress >= 0f) {
            h.track.setVisibility(View.VISIBLE);
            h.shade.setVisibility(View.VISIBLE);
            h.bar.setPivotX(0f);
            h.bar.setScaleX(Math.max(0.03f, Math.min(1f, c.progress)));
        } else {
            h.track.setVisibility(View.GONE);
            h.shade.setVisibility(View.GONE);
        }
        if (c.badge != null) {
            h.badge.setText(c.badge);
            h.badge.setBackgroundTintList(ColorStateList.valueOf(c.badgeStrong ? 0xFFFFFFFF : 0xB3000000));
            h.badge.setTextColor(c.badgeStrong ? 0xFF111111 : 0xFFFFFFFF);
            h.badge.setVisibility(View.VISIBLE);
        } else {
            h.badge.setVisibility(View.GONE);
        }
        h.card.setOnClickListener(v -> listener.onCardClick(c));
    }

    @Override
    public int getItemCount() {
        return cards.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView image;
        final TextView badge;
        final View shade;
        final View track;
        final View bar;
        final TextView title;
        final TextView subtitle;

        Holder(View v, CardListener listener) {
            super(v);
            card = v.findViewById(R.id.continueCard);
            image = v.findViewById(R.id.image);
            badge = v.findViewById(R.id.badge);
            shade = v.findViewById(R.id.shade);
            track = v.findViewById(R.id.progressTrack);
            bar = v.findViewById(R.id.progressBar);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);

            final float radius = v.getResources().getDisplayMetrics().density * 12f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            // Stay in the row at its ends (instead of jumping to the top bar), and go to the dock on Down.
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                RecyclerView.Adapter<?> a = getBindingAdapter();
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return pos == 0;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return a != null && pos == a.getItemCount() - 1;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    listener.onCardDown();
                    return true;
                }
                return false;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                view.animate().scaleX(s).scaleY(s).setDuration(focused ? 200 : 160)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
                title.animate().alpha(focused ? 1f : 0.7f).setDuration(160).start();
                if (focused) listener.onCardFocused();
            });
        }
    }
}
