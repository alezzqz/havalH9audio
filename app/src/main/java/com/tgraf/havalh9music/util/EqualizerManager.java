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

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 6-полосный эквалайзер на android.media.audiofx.Equalizer.
 * Полосы: 60 / 175 / 400 / 1k / 3.5k / 12k. Диапазон: −15…+15 дБ.
 * Виртуальные полосы маппятся на ближайшие физические; полосы, делящие одну
 * физическую, агрегируются суммой дБ (applyAll).
 * Сохранение в SharedPreferences.
 */
public class EqualizerManager {

    private static final String TAG = "EqualizerManager";
    private static final String PREFS = "equalizer";
    private static final String KEY_GAINS = "gains";

    /**
     * Виртуальные частоты полос (Гц).
     */
    public static final int[] BAND_HZ = {60, 175, 400, 1000, 3500, 12000};
    public static final int BAND_COUNT = BAND_HZ.length;
    public static final float MIN_DB = -15f;
    public static final float MAX_DB = 15f;

    /**
     * Пресеты: имя → усиление по полосам, дБ.
     */
    public static final java.util.Map<String, float[]> PRESETS = buildPresets();

    private final SharedPreferences prefs;
    private final float[] gains = new float[BAND_COUNT];
    private Equalizer equalizer;
    private int sessionId = 0;
    /**
     * Кэш маппинга виртуальных полос на физические; пересчитывается при привязке.
     */
    private List<Integer> mapping;

    public EqualizerManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private static java.util.Map<String, float[]> buildPresets() {
        java.util.Map<String, float[]> m = new java.util.HashMap<>();
        m.put("Flat", new float[]{0, 0, 0, 0, 0, 0});
        m.put("Rock", new float[]{4, 3, 1, -1, 2, 3});
        m.put("Metal", new float[]{7, 4, 0, 0, 3, 10});
        m.put("Pop", new float[]{-1, 2, 4, 3, -1, -1});
        m.put("Jazz", new float[]{3, 2, -1, 1, 2, 4});
        m.put("Classical", new float[]{2, 1, 0, 1, 2, 3});
        m.put("Bass Boost", new float[]{6, 4, 1, 0, -1, -1});
        return m;
    }

    /**
     * Привязывает EQ к аудио-сессии плеера. Вызывать после prepare()/аудио-сессии.
     */
    public void attach(int audioSessionId) {
        Log.i(TAG, "attach(" + audioSessionId + ")");
        if (audioSessionId <= 0) {
            return;
        }
        if (audioSessionId == sessionId && equalizer != null) {
            return;
        }
        ensureAttached(audioSessionId);
    }

    /**
     * Привязывает EQ к сессии: создаёт эффект, включает его и применяет
     * сохранённые значения. Если эффект уже привязан к этой же сессии —
     * ничего не пересоздаёт: release+new на живой сессии слышны как
     * щелчок/скачок уровня (и иногда дают искажённый звук до следующего
     * пересоздания). Пересоздание — только при смене сессии или если
     * предыдущая привязка не удалась. Безопасно вызывать перед показом диалога.
     */
    public void ensureAttached(int audioSessionId) {
        if (audioSessionId <= 0) {
            return;
        }
        if (audioSessionId == sessionId && equalizer != null) {
            return; // уже привязан к этой сессии — эффект не трогаем
        }
        release();
        try {
            equalizer = new Equalizer(0, audioSessionId);
            sessionId = audioSessionId;
            equalizer.setEnabled(true);
            mapping = null; // новая инстанс эффекта — маппинг надо пересчитать
            applyAll();
            Log.i(TAG, "EQ attached, session=" + audioSessionId
                    + ", physical bands=" + equalizer.getNumberOfBands()
                    + ", enabled=" + equalizer.getEnabled());
        } catch (Exception e) {
            Log.w(TAG, "EQ недоступен", e);
            equalizer = null;
            sessionId = 0;
        }
    }

    public void release() {
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {
            }
            equalizer = null;
        }
        sessionId = 0;
        mapping = null;
    }

    /**
     * Устанавливает усиление полосы (индекс 0..5).
     */
    public void setBand(int index, float db) {
        if (index < 0 || index >= BAND_COUNT) {
            return;
        }
        gains[index] = clamp(db);
        applyAll();
    }

    public float getBand(int index) {
        return index >= 0 && index < BAND_COUNT ? gains[index] : 0f;
    }

    /**
     * Применяет пресет по имени.
     */
    public void applyPreset(String name) {
        float[] p = PRESETS.get(name);
        if (p == null) {
            return;
        }
        for (int i = 0; i < BAND_COUNT; i++) {
            gains[i] = clamp(prefs.getFloat("preset_" + name + "_" + i, p[i]));
        }
        applyAll();
    }

    /**
     * Сохраняет отдельную копию текущих полос: дальнейшая настройка EQ
     * не должна менять пресет, а перезапуск приложения — терять его.
     */
    public void savePreset(String name) {
        if (!PRESETS.containsKey(name)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < BAND_COUNT; i++) {
            editor.putFloat("preset_" + name + "_" + i, gains[i]);
        }
        editor.apply();
    }

    /**
     * Сброс в ноль (Flat).
     */
    public void reset() {
        Arrays.fill(gains, 0f);
        applyAll();
    }

    /**
     * Профиль альбома меняет тракт, но не общие настройки: их хранит ViewModel.
     * Применяем полосы одним вызовом, без промежуточных состояний и пересоздания EQ.
     */
    public void setGains(float[] values) {
        if (values == null || values.length != BAND_COUNT) {
            return;
        }
        for (int i = 0; i < BAND_COUNT; i++) {
            gains[i] = clamp(values[i]);
        }
        applyAll();
    }

    public float[] getGains() {
        return gains.clone();
    }

    /**
     * Синхронизирует состояния из SharedPreferences.
     */
    public void load() {
        String raw = prefs.getString(KEY_GAINS, "");
        String[] parts = raw.split(",");
        for (int i = 0; i < BAND_COUNT; i++) {
            gains[i] = 0f;
            if (i < parts.length && !parts[i].isEmpty()) {
                try {
                    gains[i] = clamp(Float.parseFloat(parts[i]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /**
     * Применяет все полосы. Виртуальные полосы агрегируются по физическим:
     * сумма дБ с клампом в диапазон эффекта.
     * Физические полосы, на которые не мапится ни одна виртуальная, сбрасываются в 0.
     */
    private void applyAll() {
        if (equalizer == null) {
            return;
        }
        try {
            if (!equalizer.getEnabled()) {
                equalizer.setEnabled(true);
            }
            short[] range = equalizer.getBandLevelRange();
            int min = range[0];
            int max = range[1];
            List<Integer> m = mapping();
            int count = equalizer.getNumberOfBands();
            float[] sumDb = new float[count];
            boolean[] used = new boolean[count];
            for (int i = 0; i < BAND_COUNT; i++) {
                int phys = m.get(i);
                sumDb[phys] += gains[i];
                used[phys] = true;
            }
            StringBuilder lg = new StringBuilder("apply:");
            for (int b = 0; b < count; b++) {
                int level = used[b] ? Math.round(sumDb[b]) * 100 : 0;
                if (level < min) {
                    level = min;
                }
                if (level > max) {
                    level = max;
                }
                equalizer.setBandLevel((short) b, (short) level);
                lg.append(' ').append('b').append(b).append('=')
                        .append(level / 100);
            }
            Log.i(TAG, lg.toString());
        } catch (Exception e) {
            Log.w(TAG, "applyAll() failed", e);
        }
    }

    /**
     * Маппинг виртуальных полос на физические (кэшируется до перевязки).
     */
    private List<Integer> mapping() {
        List<Integer> m = mapping;
        if (m == null) {
            m = mapVirtualToPhysical();
            mapping = m;
        }
        return m;
    }

    /**
     * Маппит виртуальные полосы на физические: ближайшая по частоте.
     * getCenterFreq() возвращает частоту в МИЛЛИгерцах (Гц × 1000) —
     * обязательно приводим к Гц, иначе вся шкала перекашивается в сторону баса.
     * При нехватке физических полос (например, 5 физических < 6 виртуальных)
     * часть полос делит ближайшую физическую — сумма дБ считается в applyAll(),
     * поэтому обе виртуальные полосы влияют на звук.
     */
    private List<Integer> mapVirtualToPhysical() {
        short count = equalizer.getNumberOfBands();
        List<Integer> result = new ArrayList<>();
        StringBuilder mapLog = new StringBuilder();
        for (int v = 0; v < BAND_COUNT; v++) {
            int best = 0;
            long bestDelta = Long.MAX_VALUE;
            for (short b = 0; b < count; b++) {
                long cfHz = equalizer.getCenterFreq(b) / 1000L; // мГц -> Гц
                long delta = Math.abs(cfHz - BAND_HZ[v]);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = b;
                }
            }
            result.add(best);
            mapLog.append(BAND_HZ[v]).append("Hz->band").append(best).append(' ');
        }
        Log.i(TAG, "mapping: " + mapLog);
        return result;
    }

    private static float clamp(float db) {
        return Math.max(MIN_DB, Math.min(MAX_DB, db));
    }
}
