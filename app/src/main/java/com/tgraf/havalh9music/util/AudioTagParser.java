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

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Быстрое чтение тегов MP3/FLAC БЕЗ нативных вызовов
 * {@link android.media.MediaMetadataRetriever}: на ГУ он тратит на каждый
 * файл ~0,5–1,5 с (нативная инициализация декодера), из-за чего скан
 * 1800 файлов занимал ~20 минут. Здесь читаем только заголовки:
 * <ul>
 *   <li>MP3 — ID3v2 (2.2/2.3/2.4: TIT2/TPE1/TALB/TRCK, latin1/utf16/utf8)
 *       + ID3v1 в конце файла + Xing/VBRI/битрейт для длительности;</li>
 *   <li>FLAC — STREAMINFO (длительность из samples/sampleRate)
 *       + Vorbis Comment (TITLE/ARTIST/ALBUM/TRACKNUMBER).</li>
 * </ul>
 * Метод никогда не бросает исключений наружу: при любой ошибке возвращает
 * {@link Tags} с доступными полями (title/artist/album = null, durationMs = -1).
 */
public final class AudioTagParser {

    private static final String TAG = "TagParser";

    /**
     * Предел чтения ID3v2 (практически всегда ≤ 256 КБ).
     */
    private static final int MAX_ID3V2 = 4 * 1024 * 1024;

    /**
     * Сколько байт аудио читаем для поиска заголовка фрейма и Xing/VBRI.
     */
    private static final int FRAME_SCAN_BYTES = 64 * 1024;

    /**
     * Предел размера блока комментариев FLAC.
     */
    private static final int MAX_FLAC_COMMENT = 1 << 20;

    private AudioTagParser() {
    }

    /**
     * Результат разбора: null-поля = тег не найден; durationMs < 0 = неизвестна.
     */
    public static final class Tags {
        public String title;
        public String artist;
        public String album;
        public int trackNumber;
        public long durationMs = -1;
    }

    /**
     * Разбор файла по расширению (неизвестные тоже пробуем как MP3).
     */
    public static Tags read(File f) {
        if (f == null || !f.isFile()) {
            return new Tags();
        }
        String name = f.getName().toLowerCase(Locale.US);
        try {
            if (name.endsWith(".flac")) {
                return readFlac(f);
            }
            return readMp3(f);
        } catch (Throwable t) {
            Log.w(TAG, "Ошибка разбора тегов: " + f.getAbsolutePath());
            return new Tags();
        }
    }

    // ------------------------------------------------------------------
    // MP3
    // ------------------------------------------------------------------

    private static Tags readMp3(File file) throws IOException {
        Tags t = new Tags();
        long len = file.length();
        if (len < 4) {
            return t;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            UsbSpeedSimulator.opOpen();
            long audioStart = 0;

            // ---- ID3v2 ----
            byte[] hdr = new byte[10];
            if (readUpTo(raf, hdr, 10) == 10
                    && hdr[0] == 'I' && hdr[1] == 'D' && hdr[2] == '3') {
                int major = hdr[3] & 0xFF;
                int flags = hdr[5] & 0xFF;
                long tagSize = synchsafe(hdr, 6);
                if (tagSize > MAX_ID3V2) {
                    tagSize = MAX_ID3V2;
                }
                audioStart = 10 + tagSize;
                byte[] body = new byte[(int) tagSize];
                int got = readUpTo(raf, body, (int) tagSize);
                byte[] clean = (flags & 0x80) != 0 ? deUnsync(body, got) : body;
                if ((flags & 0x80) != 0) {
                    got = clean.length;
                    body = clean;
                }
                walkId3v2(body, got, major, t);
            }

            // ---- ID3v1 (последние 128 байт) ----
            boolean hasV1 = false;
            if (len >= 128) {
                byte[] v1 = new byte[128];
                UsbSpeedSimulator.opSeek();
                raf.seek(len - 128);
                if (readUpTo(raf, v1, 128) == 128
                        && v1[0] == 'T' && v1[1] == 'A' && v1[2] == 'G') {
                    hasV1 = true;
                    if (t.title == null) {
                        t.title = latin1(v1, 3, 30);
                    }
                    if (t.artist == null) {
                        t.artist = latin1(v1, 33, 30);
                    }
                    if (t.album == null) {
                        t.album = latin1(v1, 63, 30);
                    }
                    if (t.trackNumber == 0 && (v1[125] & 0xFF) != 0) {
                        t.trackNumber = v1[125] & 0xFF;
                    }
                }
            }

            // ---- Длительность ----
            long audioBytes = len - audioStart - (hasV1 ? 128L : 0L);
            if (audioBytes > 0) {
                long est = estimateDuration(raf, audioStart, audioBytes);
                if (est > 0) {
                    t.durationMs = est;
                }
            }
        }
        return t;
    }

    /**
     * Обход кадров ID3v2 (в v2.2 — 3-байтные идентификаторы).
     */
    private static void walkId3v2(byte[] body, int bodyLen, int major, Tags t) {
        if (bodyLen < 4) {
            return;
        }
        int pos = 0;
        // Расширенный заголовок: 4 байта размера (без самих 4 байт) — пропускаем.
        if (major >= 3 && bodyLen >= 8) {
            int ext = (int) (major == 4 ? synchsafe(body, 0) : (be32(body, 0) & 0xFFFFFFFFL));
            if (ext > 0 && ext < (1 << 20)) {
                pos = 4 + ext;
                if (pos > bodyLen) {
                    pos = bodyLen;
                }
            }
        }
        boolean v22 = major == 2;
        int hdrSize = v22 ? 6 : 10;
        int idLen = v22 ? 3 : 4;
        while (pos + hdrSize <= bodyLen) {
            String id = new String(body, pos, idLen, StandardCharsets.ISO_8859_1);
            if (!isValidFrameId(id, idLen)) {
                break;
            }
            long size;
            if (v22) {
                size = ((body[pos + 3] & 0xFFL) << 16)
                        | ((body[pos + 4] & 0xFFL) << 8) | (body[pos + 5] & 0xFFL);
            } else {
                size = major == 4 ? synchsafe(body, pos + 4) : (be32(body, pos + 4) & 0xFFFFFFFFL);
            }
            if (size <= 0 || size > bodyLen - pos - hdrSize) {
                break;
            }
            handleTextFrame(body, pos + hdrSize, (int) size, id, t);
            pos += hdrSize + (int) size;
            // защита от жуткого числа кадров в битом теге
            if (pos > bodyLen) {
                break;
            }
        }
    }

    private static boolean isValidFrameId(String id, int idLen) {
        if (id.length() != idLen) {
            return false;
        }
        for (int i = 0; i < idLen; i++) {
            char c = id.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static void handleTextFrame(byte[] body, int dataStart, int len, String id, Tags t) {
        if (len < 1) {
            return;
        }
        int enc = body[dataStart] & 0xFF;
        int textLen = len - 1;
        String value = decode(body, dataStart + 1, textLen, enc);
        if (value == null || value.isEmpty()) {
            return;
        }
        switch (id) {
            case "TIT2":
            case "TT2":
                if (t.title == null) {
                    t.title = value;
                }
                break;
            case "TPE1":
            case "TP1":
                if (t.artist == null) {
                    t.artist = value;
                }
                break;
            case "TALB":
            case "TAL":
                if (t.album == null) {
                    t.album = value;
                }
                break;
            case "TRCK":
            case "TRK":
                if (t.trackNumber == 0) {
                    t.trackNumber = parseTrackNo(value);
                }
                break;
            default:
                break;
        }
    }

    /**
     * Декодирование текстового поля ID3v2 по коду кодировки.
     */
    private static String decode(byte[] b, int off, int len, int enc) {
        if (len <= 0) {
            return null;
        }
        // отрезаем хвостовые нули
        int n = len;
        while (n > 0 && b[off + n - 1] == 0) {
            n--;
        }
        if (n <= 0) {
            return null;
        }
        String s;
        try {
            switch (enc) {
                case 0:
                    s = new String(b, off, n, StandardCharsets.ISO_8859_1);
                    break;
                case 2:
                    s = new String(b, off, n, StandardCharsets.UTF_16BE);
                    break;
                case 3:
                    s = new String(b, off, n, StandardCharsets.UTF_8);
                    break;
                default:
                    if (n >= 2 && (b[off] & 0xFF) == 0xFF && (b[off + 1] & 0xFF) == 0xFE) {
                        s = new String(b, off + 2, n - 2, StandardCharsets.UTF_16LE);
                    } else if (n >= 2 && (b[off] & 0xFF) == 0xFE && (b[off + 1] & 0xFF) == 0xFF) {
                        s = new String(b, off + 2, n - 2, StandardCharsets.UTF_16BE);
                    } else {
                        s = new String(b, off, n, StandardCharsets.UTF_16);
                    }
                    break;
            }
        } catch (Exception e) {
            return null;
        }
        // несколько значений в одном поле разделяются NUL — берём первое
        int nul = s.indexOf('\u0000');
        if (nul >= 0) {
            s = s.substring(0, nul);
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * ID3v1-поле: latin1, дополнено пробелами.
     */
    private static String latin1(byte[] b, int off, int len) {
        String s = new String(b, off, len, StandardCharsets.ISO_8859_1).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Оценка длительности: Xing/Info (MPEG1 и MPEG2), VBRI, иначе CBR по
     * битрейту первого аудиофрейма. Возвращает мс или -1.
     */
    private static long estimateDuration(RandomAccessFile raf, long audioStart, long audioBytes) {
        try {
            byte[] buf = new byte[FRAME_SCAN_BYTES];
            long skip = Math.min(audioStart, raf.length());
            UsbSpeedSimulator.opSeek();
            raf.seek(skip);
            int got = readUpTo(raf, buf, FRAME_SCAN_BYTES);
            if (got < 8) {
                return -1;
            }

            int hdr = findMpegHeader(buf, got, 0);
            if (hdr < 0) {
                return -1;
            }

            int b1 = buf[hdr + 1] & 0xFF;
            int b2 = buf[hdr + 2] & 0xFF;
            int version = (b1 >> 3) & 3;          // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
            int layer = (b1 >> 1) & 3;            // 1=III, 2=II, 3=I
            boolean crc = (b1 & 1) == 1;          // 1 = защита выключена
            int bitrateIdx = (b2 >> 4) & 0xF;
            int srateIdx = (b2 >> 2) & 3;

            int sampleRate = sampleRate(version, srateIdx);
            int bitrateKbps = bitrateKbps(version, layer, bitrateIdx);
            int spf = samplesPerFrame(version, layer);
            if (sampleRate <= 0 || bitrateKbps <= 0 || spf <= 0) {
                return -1;
            }

            // Xing/Info: после заголовка фрейма (у MPEG2 Layer III — через 17 байт side-info)
            int xingBase = hdr + 4 + (crc ? 2 : 0);
            if (layer == 1) {
                if (version != 3) {
                    xingBase += 17;
                }
                if (startsWith(buf, got, xingBase, "Xing")
                        || startsWith(buf, got, xingBase, "Info")) {
                    if (xingBase + 16 <= got) {
                        long frames = be32(buf, xingBase + 8) & 0xFFFFFFFFL;
                        if (frames > 0) {
                            return frames * (long) spf * 1000L / sampleRate;
                        }
                    }
                }
            }
            // VBRI: всегда на 32 байта после заголовка фрейма
            int vbriBase = hdr + 32;
            if (startsWith(buf, got, vbriBase, "VBRI") && vbriBase + 18 <= got) {
                long frames = be32(buf, vbriBase + 14) & 0xFFFFFFFFL;
                if (frames > 0) {
                    return frames * (long) spf * 1000L / sampleRate;
                }
            }
            // CBR-оценка (или VBR без Xing — грубая оценка по первому битрейту)
            long bits = audioBytes * 8;
            return bits / bitrateKbps; // биты / (кбит/с) = мс
        } catch (Exception e) {
            return -1;
        }
    }

    private static int findMpegHeader(byte[] buf, int len, int start) {
        for (int i = start; i + 4 <= len; i++) {
            if ((buf[i] & 0xFF) != 0xFF) {
                continue;
            }
            int b1 = buf[i + 1] & 0xFF;
            if ((b1 & 0xE0) != 0xE0) {
                continue;
            }
            int version = (b1 >> 3) & 3;
            int layer = (b1 >> 1) & 3;
            int b2 = buf[i + 2] & 0xFF;
            int bitrateIdx = (b2 >> 4) & 0xF;
            int srateIdx = (b2 >> 2) & 3;
            if (version == 1 || layer == 0) {
                continue;   // reserved
            }
            if (bitrateIdx == 0 || bitrateIdx == 15) {
                continue;
            }
            if (srateIdx == 3) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static int sampleRate(int version, int idx) {
        switch (version) {
            case 0:
                return new int[]{11025, 12000, 8000, 0}[idx];
            case 2:
                return new int[]{22050, 24000, 16000, 0}[idx];
            case 3:
                return new int[]{44100, 48000, 32000, 0}[idx];
            default:
                return 0;
        }
    }

    private static int bitrateKbps(int version, int layer, int idx) {
        if (idx < 1 || idx > 14) {
            return 0;
        }
        int[] table;
        if (layer == 3) {
            table = BITRATE_L1;
        } else if (layer == 2) {
            table = BITRATE_L2;
        } else if (version == 3) {
            table = BITRATE_L3_MPEG1;
        } else {
            table = BITRATE_L3_MPEG2;
        }
        return table[idx];
    }

    private static int samplesPerFrame(int version, int layer) {
        if (layer == 3) {
            return 384;                                        // Layer I
        }
        if (layer == 2) {
            return 1152;                                       // Layer II
        }
        return version == 3 ? 1152 : 576;                                  // Layer III
    }

    private static final int[] BITRATE_L3_MPEG1 =
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BITRATE_L3_MPEG2 =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] BITRATE_L2 =
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
    private static final int[] BITRATE_L1 =
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};

    private static boolean startsWith(byte[] buf, int len, int off, String s) {
        if (off < 0 || off + s.length() > len) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if ((char) (buf[off + i] & 0xFF) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // FLAC
    // ------------------------------------------------------------------

    private static Tags readFlac(File file) throws IOException {
        Tags t = new Tags();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            UsbSpeedSimulator.opOpen();
            byte[] magic = new byte[4];
            if (readUpTo(raf, magic, 4) != 4) {
                return t;
            }
            if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') {
                return t;
            }

            boolean needStream = true, needComment = true;
            while (needStream || needComment) {
                byte[] bh = new byte[4];
                if (readUpTo(raf, bh, 4) != 4) {
                    break;
                }
                int hdr = bh[0] & 0xFF;
                boolean last = (hdr & 0x80) != 0;
                int type = hdr & 0x7F;
                long blockLen = ((bh[1] & 0xFFL) << 16) | ((bh[2] & 0xFFL) << 8) | (bh[3] & 0xFFL);

                if (type == 0 && needStream) {
                    // STREAMINFO: 34 байта
                    byte[] info = new byte[34];
                    if (readUpTo(raf, info, 34) != 34) {
                        break;
                    }
                    long sampleRate = ((info[10] & 0xFFL) << 12)
                            | ((info[11] & 0xFFL) << 4) | ((info[12] & 0xFFL) >> 4);
                    // 36-битный счётчик сэмплов: байт 13 (низкий ниббл 0x0F) + байты 14..17
                    long totalSamples = ((info[13] & 0x0FL) << 32)
                            | ((info[14] & 0xFFL) << 24) | ((info[15] & 0xFFL) << 16)
                            | ((info[16] & 0xFFL) << 8) | (info[17] & 0xFFL);
                    if (sampleRate > 0 && totalSamples > 0) {
                        t.durationMs = totalSamples * 1000L / sampleRate;
                        needStream = false;
                    } else if (last) {
                        break;
                    }
                } else if (type == 4 && needComment) {
                    int want = (int) Math.min(blockLen, MAX_FLAC_COMMENT);
                    byte[] buf = new byte[want];
                    int got = readUpTo(raf, buf, want);
                    parseVorbisComment(buf, got, t);
                    needComment = false;
                } else {
                    if (!skipFully(raf, blockLen)) {
                        break;
                    }
                }
                if (last) {
                    break;
                }
            }
        }
        return t;
    }

    /**
     * Vorbis Comment: LE32 длина vendor, vendor, LE32 число полей, поля KEY=value.
     */
    private static void parseVorbisComment(byte[] b, int len, Tags t) {
        if (len < 8) {
            return;
        }
        long pos = 0;
        long vendorLen = le32(b, (int) pos) & 0xFFFFFFFFL;
        pos += 4;
        if (pos + vendorLen + 4 > len) {
            return;
        }
        pos += vendorLen;
        long count = le32(b, (int) pos) & 0xFFFFFFFFL;
        pos += 4;
        long guard = 0;
        while (guard++ < 10000 && count-- > 0) {
            if (pos + 4 > len) {
                return;
            }
            long entryLen = le32(b, (int) pos) & 0xFFFFFFFFL;
            pos += 4;
            if (entryLen <= 0 || pos + entryLen > len) {
                return;
            }
            String kv = new String(b, (int) pos, (int) entryLen, StandardCharsets.UTF_8);
            pos += entryLen;
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = kv.substring(0, eq).toUpperCase(Locale.US);
            String val = kv.substring(eq + 1).trim();
            if (val.isEmpty()) {
                continue;
            }
            switch (key) {
                case "TITLE":
                    if (t.title == null) {
                        t.title = val;
                    }
                    break;
                case "ARTIST":
                    if (t.artist == null) {
                        t.artist = val;
                    }
                    break;
                case "ALBUM":
                    if (t.album == null) {
                        t.album = val;
                    }
                    break;
                case "TRACKNUMBER":
                    if (t.trackNumber == 0) {
                        t.trackNumber = parseTrackNo(val);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static int parseTrackNo(String s) {
        // "5" или "5/12"
        String[] parts = s.split("/");
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Низкоуровневые помощники
    // ------------------------------------------------------------------

    /**
     * Читает до max байт (сколько реально удалось).
     */
    private static int readUpTo(RandomAccessFile raf, byte[] buf, int max) throws IOException {
        int off = 0;
        while (off < max) {
            int r = raf.read(buf, off, max - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        UsbSpeedSimulator.opRead(off);
        return off;
    }

    /**
     * Пропуск n байт вперёд (переход по смещению).
     */
    private static boolean skipFully(RandomAccessFile raf, long n) {
        try {
            UsbSpeedSimulator.opSeek();
            raf.seek(raf.getFilePointer() + n);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static long synchsafe(byte[] b, int off) {
        return ((b[off] & 0x7FL) << 21)
                | ((b[off + 1] & 0x7FL) << 14)
                | ((b[off + 2] & 0x7FL) << 7)
                | (b[off + 3] & 0x7FL);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    /**
     * Де-синхронизация ID3v2: последовательность 0xFF 0x00 → 0xFF.
     */
    private static byte[] deUnsync(byte[] in, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        for (int i = 0; i < len; i++) {
            out.write(in[i]);
            if ((in[i] & 0xFF) == 0xFF && i + 1 < len && (in[i + 1] & 0xFF) == 0x00) {
                i++;
            }
        }
        return out.toByteArray();
    }
}