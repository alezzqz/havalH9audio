/*
 * HavalH9Music - offline audio player for the Haval car head unit
 * Copyright (C) 2026 TermoGRAF
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tgraf.havalh9music.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tgraf.havalh9music.R;
import com.tgraf.havalh9music.databinding.ItemTrackBinding;
import com.tgraf.havalh9music.model.LibraryRow;
import com.tgraf.havalh9music.model.TrackEntity;
import com.tgraf.havalh9music.util.AppearanceSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Адаптер списка текущего каталога: папки и треки (в т.ч. CUE).
 */
public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.VH> {

    public interface Listener {
        void onRowClick(LibraryRow row, int position);

        void onMoreClick(LibraryRow row);

    }

    private final List<LibraryRow> items = new ArrayList<>();
    private final Listener listener;
    private String activeTrackPath; // подсветка играющего трека

    public TrackAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<LibraryRow> rows) {
        int oldSize = items.size();
        items.clear();
        items.addAll(rows);
        int newSize = items.size();
        // Вместо notifyDataSetChanged — минимальные события по размеру:
        // RecyclerView не пересоздаёт строки без нужды и не теряет позицию.
        if (oldSize == 0) {
            if (newSize > 0) {
                notifyItemRangeInserted(0, newSize);
            }
            return;
        }
        if (newSize == 0) {
            notifyItemRangeRemoved(0, oldSize);
            return;
        }
        int common = Math.min(oldSize, newSize);
        notifyItemRangeChanged(0, common);
        if (newSize > oldSize) {
            notifyItemRangeInserted(common, newSize - common);
        } else if (newSize < oldSize) {
            notifyItemRangeRemoved(common, oldSize - common);
        }
    }

    /**
     * Подсветка играющего трека: точечно обновляем только две строки.
     */
    public void setActiveTrack(String path) {
        int oldPos = indexOfTrack(activeTrackPath);
        activeTrackPath = path;
        int newPos = indexOfTrack(path);
        if (oldPos >= 0) {
            notifyItemChanged(oldPos);
        }
        if (newPos >= 0 && newPos != oldPos) {
            notifyItemChanged(newPos);
        }
    }

    private int indexOfTrack(String path) {
        for (int i = 0; i < items.size(); i++) {
            TrackEntity t = items.get(i).track;
            if (t != null && Objects.equals(t.path, path)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTrackBinding b = ItemTrackBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LibraryRow row = items.get(position);

        if (row.kind == LibraryRow.Kind.FOLDER) {
            h.binding.rowIconContainer.setBackgroundResource(R.drawable.bg_icon_box);
            h.binding.rowIcon.setImageResource(R.drawable.ic_folder);
            h.binding.rowIcon.setImageTintList(ColorStateList.valueOf(
                    AppearanceSettings.color(h.itemView.getContext(), R.attr.paletteAccent)));
            h.binding.rowTitle.setText(row.label());
            h.binding.rowTitle.setTextColor(AppearanceSettings.color(
                    h.itemView.getContext(), R.attr.paletteTextPrimary));

            String sub = row.subtitle();
            if (!sub.isEmpty()) {
                h.binding.rowSubtitle.setText(sub);
                h.binding.rowSubtitle.setVisibility(View.VISIBLE);
            } else {
                h.binding.rowSubtitle.setVisibility(View.GONE);
            }

            h.binding.rowDuration.setVisibility(View.GONE);
            h.binding.rowMore.setVisibility(View.GONE);
            h.binding.rowChevron.setVisibility(View.VISIBLE);
            h.itemView.setBackgroundResource(0);
            // У каталогов долгое нажатие не нужно — сбрасываем, чтобы
            // переиспользованная строка не «запомнила» слушатель трека.
            h.itemView.setOnLongClickListener(null);
        } else {
            TrackEntity t = row.track;
            boolean active = t.path != null && t.path.equals(activeTrackPath);

            h.binding.rowIconContainer.setBackgroundResource(
                    active ? R.drawable.bg_icon_box_active : R.drawable.bg_icon_box);
            h.binding.rowIcon.setImageResource(
                    t.isCue ? R.drawable.ic_disc : R.drawable.ic_note);
            h.binding.rowIcon.setImageTintList(ColorStateList.valueOf(
                    AppearanceSettings.color(h.itemView.getContext(),
                            active ? R.attr.paletteAccent : R.attr.paletteIconPrimary)));

            h.binding.rowTitle.setText(row.label());
            h.binding.rowTitle.setTextColor(AppearanceSettings.color(
                    h.itemView.getContext(),
                    active ? R.attr.paletteAccent : R.attr.paletteTextPrimary));

            h.binding.rowSubtitle.setVisibility(View.GONE);

            h.binding.rowDuration.setText(row.subtitle());
            h.binding.rowDuration.setTextColor(AppearanceSettings.color(
                    h.itemView.getContext(),
                    active ? R.attr.paletteAccent : R.attr.paletteTextSecondary));
            h.binding.rowDuration.setVisibility(View.VISIBLE);

            h.binding.rowMore.setVisibility(View.VISIBLE);
            h.binding.rowChevron.setVisibility(View.GONE);

            h.itemView.setBackgroundResource(active ? R.drawable.bg_active_row : 0);
        }

        h.itemView.setOnClickListener(v ->
                listener.onRowClick(row, h.getBindingAdapterPosition()));
        h.binding.rowMore.setOnClickListener(v -> listener.onMoreClick(row));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class VH extends RecyclerView.ViewHolder {
        final ItemTrackBinding binding;

        VH(ItemTrackBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
