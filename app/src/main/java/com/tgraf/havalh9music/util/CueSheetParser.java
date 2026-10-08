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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Парсер CUE-листов, включая многодисковые (несколько FILE-блоков).
 * Кодировки: UTF-8, CP1251 (fallback), UTF-16LE/BE (по BOM или эвристике NUL-байтов —
 * как пишет CUE «MedievaL CUE Splitter»). При ошибке — CueParseException,
 * вызывающий код откатывается к воспроизведению FLAC как единого трека.
 */
public final class CueSheetParser {

    private CueSheetParser() {
    }

    /**
     * Битый/неподдерживаемый CUE.
     */
    public static class CueParseException extends Exception {
        public CueParseException(String message) {
            super(message);
        }
    }

    /**
     * Виртуальный трек из CUE.
     */
    public static class CueTrack {
        /**
         * Путь к CUE-файлу.
         */
        public String cuePath;
        /**
         * Номер TRACK NN (1-based).
         */
        public int number;
        public String title = "";
        public String performer = "";
        public String genre = "";
        public String date = "";
        /**
         * Физический FLAC-файл (абсолютный путь).
         */
        public String sourceFile;
        /**
         * Старт (INDEX 01) в мс от начала файла.
         */
        public long startMs;
        /**
         * Конец в мс; -1 — до конца файла.
         */
        public long endMs = -1;
        /**
         * Номер диска (индекс FILE-блока, 1-based).
         */
        public int diskIndex;
    }

    /**
     * Разбирает CUE-файл.
     *
     * @param cueFile путь к .cue
     * @return виртуальные треки (по одному на TRACK), в порядке появления
     * @throws CueParseException при любых ошибках разбора
     */
    public static List<CueTrack> parse(File cueFile) throws CueParseException {
        if (cueFile == null || !cueFile.isFile()) {
            throw new CueParseException("CUE-файл не найден: " + cueFile);
        }

        byte[] raw = readBytes(cueFile); // читаем CUE файл целиком

        // Кодировка: BOM → сразу UTF-16LE/BE (или UTF-8 с BOM без fallback),
        // иначе — эвристика по NUL-байтам (UTF-16 без BOM, как у MedievaL CUE Splitter),
        // иначе — пробуем по порядку UTF-8 → CP1251.
        String[] encodings;
        int offset = 0;
        if (raw.length >= 2 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xFE) {
            encodings = new String[]{"UTF-16LE", "UTF-16BE"};
            offset = 2;
        } else if (raw.length >= 2 && raw[0] == (byte) 0xFE && raw[1] == (byte) 0xFF) {
            encodings = new String[]{"UTF-16BE", "UTF-16LE"};
            offset = 2;
        } else if (raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB
                && raw[2] == (byte) 0xBF) {
            encodings = new String[]{"UTF-8"};
            offset = 3;
        } else if (looksLikeUtf16Le(raw)) {
            encodings = new String[]{"UTF-16LE", "UTF-8", "windows-1251"};
        } else if (looksLikeUtf16Be(raw)) {
            encodings = new String[]{"UTF-16BE", "UTF-8", "windows-1251"};
        } else {
            encodings = new String[]{"UTF-8", "windows-1251"};
        }

        byte[] data = offset > 0 ? Arrays.copyOfRange(raw, offset, raw.length) : raw;
        Exception last = null;
        for (String enc : encodings) {
            try {
                List<CueTrack> tracks = parseInternal(data, enc, cueFile);
                for (CueTrack t : tracks) {
                    t.cuePath = cueFile.getAbsolutePath();
                }
                return tracks;
            } catch (IOException | CueParseException e) {
                last = e;
            }
        }
        // encodings всегда непустой, успешный разбор ушёл бы в return —
        // значит, до этой строки цикл прошёл с исключением минимум один раз.
        throw new CueParseException(
                "Не удалось разобрать CUE: " + cueFile + " (" + last.getMessage() + ")");
    }

    /**
     * Читает CUE-файл целиком (до 1 МБ) в память.
     */
    private static byte[] readBytes(File cueFile) throws CueParseException {
        try (FileInputStream in = new FileInputStream(cueFile)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(
                    Math.max(256, (int) Math.min(cueFile.length(), 1 << 20)));
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new CueParseException("Не удалось прочитать CUE: " + cueFile);
        }
    }

    private static List<CueTrack> parseInternal(byte[] data, String encoding, File cueFile)
            throws IOException, CueParseException {
        // Строгий декодер: битая последовательность в одной кодировке
        // → IOException → fallback к следующей (UTF-8 → CP1251)
        CharsetDecoder decoder = Charset.forName(encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        File dir = cueFile.getParentFile();
        List<CueTrack> result = new ArrayList<>();
        String currentFile = null;
        int diskIndex = 0;
        String albumPerformer = ""; // PERFORMER до первого TRACK — исполнитель альбома
        String albumGenre = "";   // REM GENRE до первого TRACK — жанр альбома
        String albumDate = "";    // REM DATE до первого TRACK — год альбома

        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(data), decoder))) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String upper = t.toUpperCase(Locale.ROOT);

                if (upper.startsWith("FILE ")) {
                    // FILE "cd1.flac" WAVE ...
                    currentFile = extractFileName(t);
                    if (currentFile == null) {
                        throw new CueParseException("строка FILE без имени, строка " + lineNo);
                    }
                    currentFile = new File(dir, currentFile).getAbsolutePath();
                    diskIndex++;
                } else if (upper.startsWith("TRACK ")) {
                    if (currentFile == null) {
                        throw new CueParseException("TRACK до FILE, строка " + lineNo);
                    }
                    CueTrack ct = new CueTrack();
                    ct.sourceFile = currentFile;
                    ct.diskIndex = diskIndex;
                    ct.performer = albumPerformer;
                    ct.genre = albumGenre;
                    ct.date = albumDate;
                    // TRACK NN AUDIO → номер
                    String rest = t.substring(t.indexOf("TRACK") + 5).trim();
                    String numStr = rest.split("\\s+")[0];
                    try {
                        ct.number = Integer.parseInt(numStr);
                    } catch (NumberFormatException ignored) {
                        // номер необязателен для воспроизведения
                    }
                    result.add(ct);
                } else if (upper.startsWith("TITLE")) {
                    String title = extractQuoted(t);
                    if (title != null && !result.isEmpty()) {
                        result.get(result.size() - 1).title = title;
                    }
                    // TITLE до первого TRACK — название альбома, игнорируем
                } else if (upper.startsWith("PERFORMER")) {
                    String perf = extractQuoted(t);
                    if (perf == null) {
                        continue;
                    }
                    if (!result.isEmpty()) {
                        result.get(result.size() - 1).performer = perf;
                    } else {
                        albumPerformer = perf;
                    }
                } else if (upper.startsWith("REM GENRE")) {
                    String g = extractQuoted(t);
                    if (!result.isEmpty()) {
                        if (g != null) {
                            result.get(result.size() - 1).genre = g;
                        }
                    } else {
                        if (g != null) {
                            albumGenre = g;
                        }
                    }
                } else if (upper.startsWith("REM DATE")) {
                    String d = extractQuoted(t);
                    if (!result.isEmpty()) {
                        if (d != null) {
                            result.get(result.size() - 1).date = d;
                        }
                    } else {
                        if (d != null) {
                            albumDate = d;
                        }
                    }
                } else if (upper.startsWith("INDEX 01")) {
                    if (result.isEmpty()) {
                        continue;
                    }
                    String msf = t.substring(t.indexOf("01") + 2).trim();
                    result.get(result.size() - 1).startMs = parseMsf(msf, lineNo);
                } else if (upper.startsWith("INDEX 02") || upper.startsWith("INDEX 03")) {
                    // Окончание текущего трека (endMs до -1 не задавался)
                    if (result.isEmpty()) {
                        continue;
                    }
                    CueTrack last = result.get(result.size() - 1);
                    if (last.endMs < 0) {
                        // Надёжно отрезаем префикс "INDEX 02"/"INDEX 03",
                        // чтобы в parseMsf не попал номер индекса.
                        String msf = t.trim()
                                .replaceFirst("(?i)^INDEX\\s+0[23]\\s*", "")
                                .trim();
                        last.endMs = parseMsf(msf, lineNo);
                    }
                }
                // INDEX 00 — pregap, игнорируем
            }
        }

        // endMs: для каждой позиции — старт следующего трека в том же файле, иначе -1 (до конца)
        for (int i = 0; i < result.size(); i++) {
            CueTrack cur = result.get(i);
            if (i + 1 < result.size()) {
                CueTrack next = result.get(i + 1);
                if (next.sourceFile.equals(cur.sourceFile)) {
                    cur.endMs = next.startMs;
                }
            }
        }

        if (result.isEmpty()) {
            throw new CueParseException("в CUE нет ни одного TRACK");
        }
        return result;
    }

    /**
     * Эвристика: UTF-16LE без BOM — каждый второй (нечётный) байт в начале файла — NUL.
     */
    private static boolean looksLikeUtf16Le(byte[] data) {
        return nulPattern(data, true);
    }

    /**
     * Эвристика: UTF-16BE без BOM — каждый второй (чётный) байт в начале файла — NUL.
     */
    private static boolean looksLikeUtf16Be(byte[] data) {
        return nulPattern(data, false);
    }

    /**
     * Проверяет префикс файла на типичный для UTF-16 текста узор:
     * примерно каждый второй байт — NUL (у ASCII-символов в UTF-16 второй байт всегда 0).
     * le=true — NUL на нечётных позициях (little-endian), le=false — на чётных.
     */
    private static boolean nulPattern(byte[] data, boolean le) {
        int n = Math.min(data.length, 2048);
        if (n < 8) {
            return false;
        }
        int nulOnSide = 0;    // NUL на «ожидаемых» позициях
        int nulOffSide = 0;   // NUL на противоположных позициях
        int limit = Math.min(n, 512);
        for (int i = 0; i < limit; i++) {
            byte b = data[i];
            if (b != 0) {
                continue;
            }
            boolean odd = (i & 1) == 1;
            if ((le && odd) || (!le && !odd)) {
                nulOnSide++;
            } else {
                nulOffSide++;
            }
        }
        return nulOnSide >= 8 && nulOnSide > nulOffSide * 3;
    }

    /**
     * Извлекает имя файла из "..." в строке FILE.
     */
    private static String extractFileName(String line) {
        String q = extractQuoted(line);
        if (q != null) {
            return q;
        }
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            return parts[1].replace("\"", "");
        }
        return null;
    }

    /**
     * Извлекает содержимое в кавычках.
     */
    private static String extractQuoted(String line) {
        int a = line.indexOf('"');
        if (a < 0) {
            return null;
        }
        int b = line.indexOf('"', a + 1);
        if (b < 0) {
            return null;
        }
        return line.substring(a + 1, b);
    }

    /**
     * MM:SS:FF → миллисекунды (75 кадров/сек).
     */
    private static long parseMsf(String msf, int lineNo) throws CueParseException {
        String[] p = msf.split(":");
        if (p.length != 3) {
            throw new CueParseException("неверный INDEX (ожидалось MM:SS:FF), строка " + lineNo);
        }
        try {
            int mm = Integer.parseInt(p[0].trim());
            int ss = Integer.parseInt(p[1].trim());
            int ff = Integer.parseInt(p[2].trim());
            long total = ((long) mm * 60 + ss) * 75 + ff;
            return total * 1000 / 75;
        } catch (NumberFormatException e) {
            throw new CueParseException("неверный INDEX (ожидалось MM:SS:FF), строка " + lineNo);
        }
    }
}