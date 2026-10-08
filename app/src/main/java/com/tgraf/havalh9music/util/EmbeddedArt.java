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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Быстрая проверка наличия встроенной обложки в flac/mp3 БЕЗ вызова
 * {@link android.media.MediaMetadataRetriever#getEmbeddedPicture()}.
 * Причина: нативный слой getEmbeddedPicture() на каждый файл без картинки
 * пишет в logcat ошибку
 * E MediaMetadataRetriever: getEmbeddedPicture: Call to getEmbeddedPicture failed.
 * Это системный лог — proguard-обрезка логов приложения его не убирает.
 * Здесь мы читаем только заголовок контейнера (форматы ограничены flac/mp3),
 * а сам getEmbeddedPicture() вызываем лишь когда картинка точно есть.
 */
public final class EmbeddedArt {

    private static final byte[] FLAC_MAGIC = {'f', 'L', 'a', 'C'};
    private static final byte[] APIC = {'A', 'P', 'I', 'C'};
    /**
     * Обложка, вложенная в Vorbis-комментарий FLAC (base64).
     */
    private static final byte[] VORBIS_PICTURE = "METADATA_BLOCK_PICTURE".getBytes();

    private static final int MAX_TAG_BYTES = 8 * 1024 * 1024; // разумный предел тега

    private EmbeddedArt() {
    }

    /**
     * true — в файле есть встроенная обложка (flac/mp3).
     */
    public static boolean hasPicture(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".flac")) {
                return flacHasPicture(file);
            }
            if (name.endsWith(".mp3")) {
                return id3HasApic(file);
            }
        } catch (IOException ignored) {
            // Нет доступа/битый файл — считаем, что картинки нет.
        }
        return false;
    }

    /**
     * FLAC: идём по блокам метаданных, ищем PICTURE (тип 6).
     */
    private static boolean flacHasPicture(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            UsbSpeedSimulator.opOpen();
            byte[] magic = new byte[4];
            if (readFully(in, magic) != 4) {
                return false;
            }
            if (!equals(magic, FLAC_MAGIC)) {
                return false;
            }

            while (true) {
                UsbSpeedSimulator.opRead(1);
                int header = in.read();
                if (header < 0) {
                    return false;
                }
                boolean last = (header & 0x80) != 0;
                int type = header & 0x7F;
                long len = read24(in);
                if (len < 0) {
                    return false;
                }

                if (type == 6) {
                    return true; // METADATA_BLOCK_PICTURE
                }
                if (type == 4) {
                    // VORBIS_COMMENT: картинка может лежать как base64 в поле
                    // METADATA_BLOCK_PICTURE.
                    if (containsAscii(in, len, VORBIS_PICTURE)) {
                        return true;
                    }
                } else {
                    if (skipFully(in, len) != len) {
                        return false;
                    }
                }
                if (last) {
                    return false; // последний блок — картинки нет
                }
            }
        }
    }

    /**
     * MP3: в теге ID3v2 ищем кадр APIC (встроенная картинка).
     */
    private static boolean id3HasApic(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            UsbSpeedSimulator.opOpen();
            byte[] h = new byte[10];
            if (readFully(in, h) != 10) {
                return false;
            }
            if (h[0] != 'I' || h[1] != 'D' || h[2] != '3') {
                return false;
            }
            // Размер ID3v2 — synchsafe (по 7 бит на байт).
            long size = ((h[6] & 0x7FL) << 21) | ((h[7] & 0x7FL) << 14)
                    | ((h[8] & 0x7FL) << 7) | (h[9] & 0x7FL);
            return containsAscii(in, Math.min(size, MAX_TAG_BYTES), APIC);
        }
    }

    /**
     * Читает до {@code len} байт потока, ища байтовую последовательность.
     */
    private static boolean containsAscii(InputStream in, long len,
                                         byte[] needle) throws IOException {
        if (len <= 0 || needle.length == 0) {
            return false;
        }
        byte[] buf = new byte[16 * 1024];
        int matched = 0;
        long remaining = len;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int r = in.read(buf, 0, want);
            if (r <= 0) {
                break;
            }
            UsbSpeedSimulator.opRead(r);
            remaining -= r;
            for (int i = 0; i < r; i++) {
                byte b = buf[i];
                if (b == needle[matched]) {
                    if (++matched == needle.length) {
                        return true;
                    }
                } else {
                    matched = (b == needle[0]) ? 1 : 0;
                }
            }
        }
        return false;
    }

    private static int read24(InputStream in) throws IOException {
        UsbSpeedSimulator.opRead(3);
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        if (b0 < 0 || b1 < 0 || b2 < 0) {
            return -1;
        }
        return (b0 << 16) | (b1 << 8) | b2;
    }

    private static long readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        UsbSpeedSimulator.opRead(off);
        return off;
    }

    private static long skipFully(InputStream in, long n) throws IOException {
        UsbSpeedSimulator.opSeek();
        long total = 0;
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                break;
            }
            total += s;
            n -= s;
        }
        return total;
    }

    private static boolean equals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
