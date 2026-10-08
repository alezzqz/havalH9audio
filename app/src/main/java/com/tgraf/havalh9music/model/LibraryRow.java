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

import java.util.Locale;

/**
 * Строка списка текущего каталога: либо подкаталог, либо трек.
 */
public class LibraryRow {

    public enum Kind {FOLDER, TRACK}

    public final Kind kind;

    // Поля для каталога
    public final String folderName;
    public final String folderPath;
    public final int folderTrackCount;

    // Поле для трека
    public final TrackEntity track;

    private LibraryRow(Kind kind, String folderName, String folderPath, int folderTrackCount,
                       TrackEntity track) {
        this.kind = kind;
        this.folderName = folderName;
        this.folderPath = folderPath;
        this.folderTrackCount = folderTrackCount;
        this.track = track;
    }

    public static LibraryRow folder(String name, String path, int trackCount) {
        return new LibraryRow(Kind.FOLDER, name, path, trackCount, null);
    }

    public static LibraryRow track(TrackEntity t) {
        return new LibraryRow(Kind.TRACK, null, null, 0, t);
    }

    /**
     * Отображаемый заголовок строки.
     */
    public String label() {
        return kind == Kind.FOLDER ? folderName : track.displayName();
    }

    /**
     * Подпись под названием (каталог — "X треков", трек — длительность).
     */
    public String subtitle() {
        if (kind == Kind.FOLDER) {
            if (folderTrackCount <= 0) {
                return "";
            }
            return formatTrackCount(folderTrackCount);
        } else {
            return formatTime(track.durationMs);
        }
    }

    public static String formatTrackCount(int count) {
        int rem100 = count % 100;
        int rem10 = count % 10;
        if (rem100 >= 11 && rem100 <= 19) {
            return count + " треков";
        }
        if (rem10 == 1) {
            return count + " трек";
        }
        if (rem10 >= 2 && rem10 <= 4) {
            return count + " трека";
        }
        return count + " треков";
    }

    /**
     * Форматирование времени: m:ss или h:mm:ss.
     */
    public static String formatTime(long ms) {
        if (ms < 0) {
            return "0:00";
        }
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
