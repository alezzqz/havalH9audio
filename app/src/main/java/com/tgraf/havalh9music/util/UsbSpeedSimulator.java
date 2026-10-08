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

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.tgraf.havalh9music.BuildConfig;

/**
 * Симулятор скорости чтения USB-флешки для замеров на эмуляторе.
 * <p>
 * На эмуляторе /sdcard лежит на быстром диске хоста (NVMe/SSD), поэтому он не
 * отражает реальную флешку: там основную роль играет НЕ пропускная способность,
 * а латентность каждой мелкой операции — открытия файла, чтения заголовка,
 * перестановки позиции, перечисления каталога, плюс очередь одной команды
 * контроллера, из-за которой параллельные потоки не ускоряют чтение.
 * <p>
 * Этот класс добавляет искусственные задержки ко всем операциям ввода-вывода
 * во время скана, чтобы увидеть, как приложение поведёт себя на медленном
 * накопителе (и что частичный кэш, навигация и молчаливый перескан работают
 * и при 30–60-секундном скане).
 * <p>
 * Параметры читаются из Settings.Global — пересобирать приложение для разных
 * «флешек» не нужно, меняются одной adb-командой до запуска скана:
 * <pre>
 * adb shell settings put global haval_usb_sim_open_ms 1     // цена открытия файла, мс
 * adb shell settings put global haval_usb_sim_read_ms 2     // цена чтения/seek, мс
 * adb shell settings put global haval_usb_sim_dir_ms  1     // цена листинга каталога, мс
 * adb shell settings put global haval_usb_sim_bps    25000000 // предел пропускной способности, байт/с
 * ...
 * adb shell settings put global haval_usb_sim_open_ms 0     // (и остальные в 0) — выключить
 * </pre>
 * Работает ТОЛЬКО в debug-сборке: тут BuildConfig.DEBUG, а в release R8
 * сворачивает все вызовы в пустые операции и выкидывает этот класс вместе
 * с ними — на ГУ поведение не меняется.
 * <p>
 * Все задержки сериализуются общим замком {@link #DISK} — как единая очередь
 * команд реального накопителя: суммарное время симулируемого чтения равно сумме
 * всех латентностей, а не делится на число потоков разбора.
 */
public final class UsbSpeedSimulator {

    private static final String TAG = "UsbSim";

    /**
     * null = симуляция выключена.
     */
    private static volatile Cfg cfg;

    /**
     * Параметры текущей «флешки».
     */
    public static final class Cfg {
        public final int openMs;
        public final int readMs;
        public final int dirMs;
        public final long bytesPerSec;

        Cfg(int openMs, int readMs, int dirMs, long bytesPerSec) {
            this.openMs = openMs;
            this.readMs = readMs;
            this.dirMs = dirMs;
            this.bytesPerSec = bytesPerSec;
        }
    }

    private UsbSpeedSimulator() {
    }

    /**
     * Включить симуляцию на время скана с параметрами из настроек.
     * Если все параметры нулевые (или ключей нет) — остаётся выключенной.
     */
    public static void beginScan(Context app) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        ContentResolver cr = app.getContentResolver();
        int openMs = intSetting(cr, "haval_usb_sim_open_ms");
        int readMs = intSetting(cr, "haval_usb_sim_read_ms");
        int dirMs = intSetting(cr, "haval_usb_sim_dir_ms");
        long bps = longSetting(cr, "haval_usb_sim_bps");
        if (openMs <= 0 && readMs <= 0 && dirMs <= 0 && bps <= 0) {
            cfg = null;
            return;
        }
        cfg = new Cfg(openMs, readMs, dirMs, bps);
        Log.i(TAG, "USB-sim включён: open=" + openMs + " read=" + readMs
                + " dir=" + dirMs + " bps=" + bps);
    }

    /**
     * Выключить симуляцию (скан завершён/отменён).
     */
    public static void endScan() {
        cfg = null;
    }

    /**
     * Открытие файла.
     */
    public static void opOpen() {
        Cfg c = cfg;
        if (c == null) {
            return;
        }
        sleep(c, 0, c.openMs);
    }

    /**
     * Чтение n байт: фиксированная латентность операции + время передачи.
     */
    public static void opRead(long bytes) {
        Cfg c = cfg;
        if (c == null) {
            return;
        }
        sleep(c, bytes, c.readMs);
    }

    /**
     * Перестановка позиции чтения (seek).
     */
    public static void opSeek() {
        Cfg c = cfg;
        if (c == null) {
            return;
        }
        sleep(c, 0, c.readMs);
    }

    /**
     * Перечисление каталога.
     */
    public static void opDir() {
        Cfg c = cfg;
        if (c == null) {
            return;
        }
        sleep(c, 0, c.dirMs);
    }

    /**
     * Общий «диск»: одна очередь команд, как у реальной флешки.
     */
    private static final Object DISK = new Object();

    private static void sleep(Cfg c, long bytes, int fixedMs) {
        long total = fixedMs;
        if (bytes > 0 && c.bytesPerSec > 0) {
            long ns = bytes * 1_000_000_000L / c.bytesPerSec;
            total += (ns + 999_999L) / 1_000_000L;
        }
        if (total <= 0) {
            return;
        }
        synchronized (DISK) {
            try {
                Thread.sleep(total);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int intSetting(ContentResolver cr, String key) {
        try {
            return Settings.Global.getInt(cr, key);
        } catch (Settings.SettingNotFoundException e) {
            return 0;
        }
    }

    private static long longSetting(ContentResolver cr, String key) {
        try {
            return Settings.Global.getLong(cr, key);
        } catch (Settings.SettingNotFoundException e) {
            return 0;
        }
    }
}
