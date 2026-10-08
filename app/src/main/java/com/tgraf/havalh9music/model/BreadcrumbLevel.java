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

package com.tgraf.havalh9music.model;

/**
 * Уровень хлебных крошек. depth = количество сегментов пути от корня
 * (depth 0 — корень «USB», depth 1 — первый подкаталог и т.д.).
 */
public class BreadcrumbLevel {

    public final String label;
    public final int depth;
    /**
     * true — последний (текущий) уровень, подсвечивается акцентом.
     */
    public final boolean last;
    /**
     * true — заглушка «…» для свернутого длинного пути (не кликабельна).
     */
    public final boolean ellipsis;

    public BreadcrumbLevel(String label, int depth, boolean last, boolean ellipsis) {
        this.label = label;
        this.depth = depth;
        this.last = last;
        this.ellipsis = ellipsis;
    }
}