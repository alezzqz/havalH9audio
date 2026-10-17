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

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.PrimaryKey;

/**
 * Запись о корне накопителя для валидации кэша.
 * <p>
 * Кэш считается действительным, пока по UUID тома есть запись с
 * {@link #complete} = true: содержимое флешки повторно не сверяем
 * (политика — «если кэш есть, он валиден»), перечитывание запускается
 * только принудительно (кнопка «обновить»).
 * <p>
 * Ключ — {@link #volumeUuid}: накопитель опознаётся стабильно, даже если
 * точка монтирования сменилась.
 */
@Entity(tableName = "root")
public class RootEntity {

    /**
     * UUID тома (или стабильный хэш пути) — первичный ключ.
     */
    @PrimaryKey
    @NonNull
    public String volumeUuid;

    /**
     * Путь корня флешки на момент сканирования.
     */
    public String rootPath;

    /**
     * Число аудиофайлов (mp3/flac) — информационно (для сообщения о скане).
     */
    public int fileCount;

    /**
     * Скан дошёл до конца: true — кэш полный и действителен.
     */
    public boolean complete;

    @ColumnInfo(defaultValue = "0")
    public int genreVersion;

    /**
     * Время последнего сканирования.
     */
    public long scannedAt;

    public RootEntity() {
        volumeUuid = "";
    }
}
