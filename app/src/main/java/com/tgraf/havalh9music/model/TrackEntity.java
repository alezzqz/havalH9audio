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

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Трек библиотеки (кэш иерархии флешки).
 * Один «виртуальный» трек = одна строка в списке.
 * Для обычных файлов: isCue = false.
 * Для CUE-разметки: isCue = true, cueSourceFile — физический FLAC,
 * cueStartMs/cueEndMs — границы клиппинга внутри файла.
 */
@Entity(tableName = "tracks", indices = {@Index(value = "parentPath")})
public class TrackEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /**
     * Уникальный идентификатор (путь виртуального/физического трека).
     */
    public String path;

    /**
     * Каталог, в котором находится трек (для списка и навигации).
     */
    public String parentPath;

    /**
     * Имя для отображения (TITLE или имя файла без расширения).
     */
    public String name;

    /**
     * Полное имя файла.
     */
    public String fileName;

    /**
     * Расширение: flac | mp3.
     */
    public String extension;

    /**
     * Размер файла (байт).
     */
    public long size;

    /**
     * Длительность, мс.
     */
    public long durationMs;

    // Метаданные (ID3 / Vorbis Comment)
    public String title;
    public String artist;
    public String album;
    public String genre;
    public String date;
    public int trackNumber;

    /**
     * Есть обложка (файл в каталоге или встроенная).
     */
    public boolean hasCover;

    /**
     * Путь к файлу обложки (cover.jpg / cover.png / folder.jpg), если есть.
     */
    public String coverPath;

    // ---- CUE-привязка ----
    public boolean isCue;

    /**
     * Путь к CUE-файлу.
     */
    public String cuePath;

    /**
     * Физический FLAC, на который ссылается FILE-строка CUE.
     */
    public String cueSourceFile;

    /**
     * Стартовая позиция в файле (INDEX 01), мс.
     */
    public long cueStartMs;

    /**
     * Конечная позиция (-1 = до конца файла).
     */
    public long cueEndMs;

    /**
     * Номер диска в многодисковом CUE (0 — неизвестно).
     */
    public int diskIndex;

    public TrackEntity() {
        cueEndMs = -1;
    }

    /**
     * Отображаемое имя: TITLE из метаданных или имя файла.
     */
    public String displayName() {
        if (title != null && !title.trim().isEmpty()) {
            return title;
        }
        return name;
    }

    /**
     * Метка формата для бейджа: FLAC / MP3 / FLAC (CUE).
     */
    @Ignore
    public String formatLabel() {
        String base = "flac".equalsIgnoreCase(extension) ? "FLAC" : "MP3";
        return isCue ? base + " (CUE)" : base;
    }
}