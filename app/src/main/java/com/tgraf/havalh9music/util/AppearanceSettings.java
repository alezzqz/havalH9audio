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

package com.tgraf.havalh9music.util;

import android.content.Context;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

/**
 * Настройки оформления переживают перезапуск приложения и смену конфигурации.
 */
public final class AppearanceSettings {
    private AppearanceSettings() {
    }

    public static boolean warm(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getBoolean("warm", false);
    }

    public static boolean swapped(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getBoolean("swapped", false);
    }

    public static void save(Context context, boolean warm, boolean swapped) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                .putBoolean("warm", warm).putBoolean("swapped", swapped).apply();
    }

    public static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId == 0 ? value.data
                : ContextCompat.getColor(context, value.resourceId);
    }
}
