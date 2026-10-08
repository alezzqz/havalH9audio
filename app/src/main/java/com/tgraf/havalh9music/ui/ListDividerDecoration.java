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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tgraf.havalh9music.R;
import com.tgraf.havalh9music.util.AppearanceSettings;

/**
 * Тонкие разделительные линии между строками списка (папки и треки).
 *
 * <p>Линия рисуется по габаритам строки (с отступом от краёв панели) в
 * зарезервированном нижнем отступе, поэтому совпадает по ширине с подсветкой
 * активного трека и не появляется после последней строки (в отличие от
 * стандартной {@code DividerItemDecoration}).
 */
public class ListDividerDecoration extends RecyclerView.ItemDecoration {

    private final Paint paint = new Paint();
    private final int height;

    public ListDividerDecoration(Context context) {
        paint.setColor(AppearanceSettings.color(context, R.attr.paletteDivider));
        height = Math.max(1,
                context.getResources().getDimensionPixelSize(R.dimen.divider_height));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) {
            return;
        }

        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        int last = adapter == null ? -1 : adapter.getItemCount() - 1;
        // Отступ под линию нужен всем строкам, кроме последней.
        outRect.bottom = position < last ? height : 0;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent,
                       @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null) {
            return;
        }

        int last = adapter.getItemCount() - 1;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION || position >= last) {
                continue;
            }
            int top = child.getBottom();
            // Линия по габаритам строки (с отступом от краёв панели) —
            // совпадает по ширине с подсветкой активного трека.
            c.drawRect(child.getLeft(), top, child.getRight(), top + height, paint);
        }
    }
}
