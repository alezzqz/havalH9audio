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

package com.tgraf.havalh9music.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Снимок данных накопителя: старые пути нужны для очистки после перемонтирования.
 * Реестр независим от Room, поэтому флешку с одними профилями тоже можно забыть.
 */
public final class SavedVolume {
    public final String id;
    public final String label;
    public final List<String> rootPaths;
    public final List<String> cacheUuids;
    public final boolean connected;

    public SavedVolume(String id, String label, List<String> rootPaths,
                       List<String> cacheUuids, boolean connected) {
        this.id = id;
        this.label = label;
        this.rootPaths = new ArrayList<>(rootPaths);
        this.cacheUuids = new ArrayList<>(cacheUuids);
        this.connected = connected;
    }
}
