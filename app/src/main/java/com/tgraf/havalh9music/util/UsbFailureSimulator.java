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
 * Симулятор сбоев USB для проверки реакции UI на ложные/реальные отключения
 * накопителя. Проблема, которую он воспроизводит: прошивка (или медленная
 * флешка) на миг делает корень нечитаемым, приложение считает накопитель
 * отключённым и показывает «Подключите USB-накопитель» поверх рабочего списка.
 * <p>
 * Режим читается из Settings.Global — пересобирать приложение не нужно:
 * <pre>
 * adb shell settings put global haval_sim_usb 1   // ложный unmount (корень «не читается» один раз)
 * adb shell settings put global haval_sim_usb 2   // пустая нумерация томов (один раз)
 * adb shell settings put global haval_sim_usb 3   // устойчивая недоступность (реальное извлечение)
 * adb shell settings put global haval_sim_usb 0   // выключено
 * </pre>
 * Событие имитирует тикер {@code PlayerViewModel}: он видит новый режим и
 * вызывает соответствующий путь. Работает ТОЛЬКО в debug-сборке: здесь
 * BuildConfig.DEBUG, а в release R8 сворачивает все вызовы в пустые операции
 * и выкидывает класс — на ГУ поведение не меняется.
 */
public final class UsbFailureSimulator {

    private static final String TAG = "UsbFailSim";
    private static final String KEY = "haval_sim_usb";

    public static final int OFF = 0;
    /**
     * Ложный unmount: первый вызов проверки читаемости неудачен.
     */
    public static final int FALSE_UNMOUNT = 1;
    /**
     * Пустая нумерация томов: findAllUsbRoots один раз возвращает пусто.
     */
    public static final int EMPTY_ENUMERATION = 2;
    /**
     * Устойчивая недоступность корня: имитация реального извлечения флешки.
     */
    public static final int PERSISTENT_LOSS = 3;

    /**
     * Режим, событие для которого уже отправлено (0 — можно слать снова).
     */
    private static int lastFired = OFF;
    /**
     * Транзиентная недоступность уже израсходована (режим 1).
     */
    private static boolean consumedUnreadable;
    /**
     * Пустая нумерация уже израсходована (режим 2).
     */
    private static boolean consumedEmpty;

    private UsbFailureSimulator() {
    }

    private static int mode(Context app) {
        if (!BuildConfig.DEBUG) {
            return OFF;
        }
        try {
            ContentResolver cr = app.getContentResolver();
            return Settings.Global.getInt(cr, KEY);
        } catch (Exception e) {
            return OFF;
        }
    }

    /**
     * Вызывается из тикера: возвращает режим, для которого нужно один раз
     * сымитировать событие. Повторно один и тот же режим не срабатывает, пока
     * его не сбросят в 0 (тогда тест можно повторить).
     */
    public static int poll(Context app) {
        int m = mode(app);
        if (m == OFF) {
            // Сброс: следующий тест можно начинать с тем же режимом.
            lastFired = OFF;
            consumedUnreadable = false;
            consumedEmpty = false;
            return OFF;
        }
        if (m != lastFired) {
            lastFired = m;
            Log.i(TAG, "sim mode=" + m + ": имитируем событие");
            return m;
        }
        return OFF;
    }

    /**
     * true — проверка читаемости корня должна притворяться неудачной.
     */
    public static boolean forceUnreadable(Context app) {
        int m = mode(app);
        if (m == FALSE_UNMOUNT) {
            // Транзиент: первый вызов неудачен, дальше корень «снова читается».
            if (!consumedUnreadable) {
                consumedUnreadable = true;
                return true;
            }
            return false;
        }
        return m == PERSISTENT_LOSS;
    }

    /**
     * true — нумерация томов должна один раз вернуть пусто (режим 2).
     */
    public static boolean consumeEmptyEnumeration(Context app) {
        if (mode(app) == EMPTY_ENUMERATION && !consumedEmpty) {
            consumedEmpty = true;
            return true;
        }
        return false;
    }
}
