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

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.annotation.NonNull;

import com.tgraf.havalh9music.model.RootEntity;
import com.tgraf.havalh9music.model.TrackEntity;

/**
 * База данных кэша иерархии флешки.
 * <p>
 * Схема v4: {@code root} — запись на том (PK volumeUuid) с флагом
 * {@code complete} и версией чтения жанров. Миграция v3 → v4 сохраняет кэш;
 * жанры старой библиотеки читаются только после ручного обновления.
 * Для более старых схем без пути миграции кэш пересоздаётся при первом
 * запуске новой версии; пользовательские настройки хранятся отдельно.
 */
@Database(entities = {TrackEntity.class, RootEntity.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract TrackDao trackDao();

    public abstract RootDao rootDao();

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "library.db")
                            .fallbackToDestructiveMigration()
                            .addMigrations(new Migration(3, 4) {
                                @Override
                                public void migrate(@NonNull SupportSQLiteDatabase database) {
                                    database.execSQL("ALTER TABLE root ADD COLUMN genreVersion"
                                            + " INTEGER NOT NULL DEFAULT 0");
                                }
                            })
                            .build();
                }
            }
        }
        return instance;
    }
}
