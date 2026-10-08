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

package com.tgraf.havalh9music.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.tgraf.havalh9music.model.TrackEntity;

import java.util.List;

@Dao
public interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<TrackEntity> tracks);

    @Query("DELETE FROM tracks")
    void clearAll();

    /**
     * Удаляет треки каталога (инкрементальная перезапись при скане).
     */
    @Query("DELETE FROM tracks WHERE parentPath = :dir")
    void deleteInParent(String dir);

    /**
     * Треки в указанном каталоге.
     * Обычные — по имени (без учёта регистра); CUE-треки — в порядке CUE:
     * сначала по имени физического файла (группа альбома), затем по диску и номеру трека
     * (иначе «#10» сортируется раньше «#2» из-за лексикографического сравнения).
     */
    @Query("SELECT * FROM tracks WHERE parentPath = :dir "
            + "ORDER BY CASE WHEN isCue = 1 THEN fileName ELSE name END COLLATE NOCASE, "
            + "isCue, diskIndex, trackNumber")
    List<TrackEntity> tracksIn(String dir);

    /**
     * Каталоги, в которых есть треки. Используется для сквозного обхода
     * библиотеки: после последнего трека каталога переходим в следующий каталог.
     */
    @Query("SELECT DISTINCT parentPath FROM tracks")
    List<String> allParentPaths();

    /**
     * Все треки библиотеки — для случайного режима воспроизведения.
     */
    @Query("SELECT * FROM tracks")
    List<TrackEntity> allTracks();

    /**
     * Каталоги, в которых есть треки (для автозапуска последней папки).
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE parentPath = :dir")
    int countIn(String dir);
}
