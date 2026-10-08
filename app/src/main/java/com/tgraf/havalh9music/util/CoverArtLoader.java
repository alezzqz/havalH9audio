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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Загрузка обложек с приоритетом:
 * 1) cover.jpg  2) cover.png  3) folder.jpg  4) встроенная (ID3/Vorbis) 5) default_cover (в UI).
 * Кэш в памяти: повторное отображение обложки не требует чтения накопителя.
 */
public class CoverArtLoader {

    /**
     * Имена файлов обложек в порядке приоритета (без расширения).
     * front.jpg — стандарт рипперов (EAC/foobar); Cover.jpg у некоторых
     * релизов с заглавной буквы, поэтому сравнение регистронезависимое.
     */
    private static final String[] COVER_BASES = {"cover", "folder", "front", "album", "albumart"};
    private static final String[] COVER_EXTS = {"jpg", "jpeg", "png"};
    private static final int DESIRED_SIZE = 512;

    public interface Callback {
        void onLoaded(Bitmap bitmap); // null → показать default_cover
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> memCache = new LruCache<>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * Ищет файл обложки в каталоге. Регистр и расширение значения не имеют
     * (cover/folder/front/album/albumart × jpg/jpeg/png).
     */
    public static String findCoverPath(File dir) {
        if (dir == null) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        UsbSpeedSimulator.opDir();
        for (String base : COVER_BASES) {
            for (String ext : COVER_EXTS) {
                String target = base + "." + ext;
                for (File f : files) {
                    if (f.isFile() && f.canRead() && f.getName().equalsIgnoreCase(target)) {
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Асинхронно грузит обложку для каталога/трека.
     */
    public void load(File dir, String audioFilePath, Callback callback) {
        String cover = findCoverPath(dir);
        final String cacheKey =
                cover != null ? cover : (audioFilePath != null ? audioFilePath : "");
        if (cacheKey.isEmpty()) {
            main.post(() -> callback.onLoaded(null));
            return;
        }
        Bitmap cached = memCache.get(cacheKey);
        if (cached != null) {
            main.post(() -> callback.onLoaded(cached));
            return;
        }
        io.execute(() -> {
            Bitmap bmp = cover != null ? decodeFile(cover) : decodeEmbedded(audioFilePath);
            if (bmp != null) {
                memCache.put(cacheKey, bmp);
            }
            final Bitmap result = bmp;
            main.post(() -> callback.onLoaded(result));
        });
    }

    /**
     * Декодирует изображение с даунсэмплингом (предотвращает OOM).
     */
    private Bitmap decodeFile(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Достаёт встроенную обложку (ID3 APIC / Vorbis Comment METADATA_BLOCK_PICTURE).
     */
    private Bitmap decodeEmbedded(String audioPath) {
        if (audioPath == null) {
            return null;
        }
        // Сначала проверяем наличие картинки без MediaMetadataRetriever:
        // нативный getEmbeddedPicture() на файлах без обложки пишет в logcat
        // ошибку "getEmbeddedPicture: Call to getEmbeddedPicture failed."
        if (!EmbeddedArt.hasPicture(new File(audioPath))) {
            return null;
        }
        // try-with-resources невозможен на API 28: close() у MediaMetadataRetriever
        // появился только в API 29 (NoSuchMethodError на устройстве). Ресурсы
        // освобождаем штатным для 28 способом — release() в finally ниже.
        //noinspection IOResourceWithoutTryWithResources
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(audioPath);
            byte[] art = mmr.getEmbeddedPicture();
            if (art == null || art.length == 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, opts);
            opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(art, 0, art.length, opts);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
    }

    private int computeSampleSize(int w, int h) {
        int sample = 1;
        int maxDim = Math.max(w, h);
        while (maxDim / sample > DESIRED_SIZE * 2) {
            sample *= 2;
        }
        return sample;
    }

}
