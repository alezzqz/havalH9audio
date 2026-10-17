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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.tgraf.havalh9music.ui;

import android.content.Context;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tgraf.havalh9music.R;

/** Общая рамка окна, включая штатные кнопки, по образцу эквалайзера. */
public final class PlayerDialogBuilder extends MaterialAlertDialogBuilder {
    public PlayerDialogBuilder(Context context) {
        this(context, R.style.ThemeOverlay_HavalH9Music_EqDialog);
    }

    public PlayerDialogBuilder(Context context, int themeResId) {
        super(context, themeResId);
    }

    @NonNull
    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        Window window = dialog.getWindow();
        if (window != null) {
            // Фон Material тонирует панель; рамка на всём окне также охватывает
            // стандартные кнопки подтверждения под пользовательским содержимым.
            window.setBackgroundDrawable(AppCompatResources.getDrawable(
                    getContext(), R.drawable.bg_eq_window));
        }
        return dialog;
    }
}
