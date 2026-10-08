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

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.tgraf.havalh9music.R;
import com.tgraf.havalh9music.databinding.ItemBreadcrumbBinding;
import com.tgraf.havalh9music.model.BreadcrumbLevel;
import com.tgraf.havalh9music.util.AppearanceSettings;

import java.util.List;

/**
 * Адаптер хлебных крошек: перестраивает LinearLayout внутри HorizontalScrollView.
 * Сворачивание длинных путей ("> 6 уровней") делает PlayerViewModel.
 */
public class BreadcrumbAdapter {

    public interface Listener {
        void onLevelClick(BreadcrumbLevel level);
    }

    private final LayoutInflater inflater;
    private final LinearLayout container;
    private final Listener listener;

    public BreadcrumbAdapter(LayoutInflater inflater, LinearLayout container, Listener listener) {
        this.inflater = inflater;
        this.container = container;
        this.listener = listener;
    }

    /**
     * Пересобирает цепочку уровней.
     */
    public void bind(List<BreadcrumbLevel> levels) {
        container.removeAllViews();
        if (levels == null || levels.isEmpty()) {
            return;
        }
        for (BreadcrumbLevel level : levels) {
            ItemBreadcrumbBinding b = ItemBreadcrumbBinding.inflate(inflater, container, false);
            b.crumbText.setText(level.label);
            b.crumbText.setTextColor(AppearanceSettings.color(container.getContext(),
                    level.last ? R.attr.paletteAccent : R.attr.paletteTextSecondary));
            b.chevron.setVisibility(level.last ? View.INVISIBLE : View.VISIBLE);
            // «…» — не кликабельно; текущий уровень тоже не перезапускает
            if (!level.last && !level.ellipsis) {
                b.getRoot().setOnClickListener(v -> listener.onLevelClick(level));
            } else {
                b.getRoot().setOnClickListener(null);
            }
            container.addView(b.getRoot());
        }
    }
}