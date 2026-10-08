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
import android.media.audiofx.LoudnessEnhancer;
import android.util.Log;

/**
 * Gain boost on the player audio session (system volume untouched).
 * Uses LoudnessEnhancer (pure gain, widely supported). Gain 0 = off: no effect
 * object is created, so 0 dB cannot change the sound.
 * Prefs: loudness(gainDb). Default: 0 dB.
 */
public class LoudnessManager {

    private static final String TAG = "LoudnessManager";
    private static final String PREFS = "loudness";
    private static final String KEY_GAIN = "gainDb";
    /**
     * Ниже этого усиления эффект в тракт не ставится вообще.
     */
    private static final float MIN_ACTIVE_DB = 0.5f;

    public static final float MIN_GAIN_DB = 0f;
    public static final float MAX_GAIN_DB = 12f;

    private final SharedPreferences prefs;
    private float gainDb = 0f;
    private int sessionId = 0;
    private LoudnessEnhancer effect;

    public LoudnessManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public float getGainDb() {
        return gainDb;
    }

    public void ensureAttached(int audioSessionId) {
        if (audioSessionId <= 0) {
            return;
        }
        if (sessionId != audioSessionId) {
            release();
            sessionId = audioSessionId;
            Log.i(TAG, "attach(session=" + audioSessionId + ")");
        }
        sync();
    }

    /**
     * Привязка по смене сессии (для тикера): no-op, если сессия та же.
     */
    public void attach(int audioSessionId) {
        if (audioSessionId <= 0) {
            return;
        }
        if (sessionId == audioSessionId) {
            return;
        }
        ensureAttached(audioSessionId);
    }

    /**
     * Усиление 0..12 дБ, применяется мгновенно; 0 = выключено.
     */
    public void setGainDb(float db) {
        gainDb = clamp(db);
        save();
        sync();
    }

    public void release() {
        if (effect != null) {
            try {
                effect.release();
            } catch (Exception ignored) {
            }
            effect = null;
        }
        // sessionId НЕ сбрасываем: он нужен, чтобы включение заново
        // создало эффект без повторного ensureAttached.
    }

    /**
     * Приводит тракт в соответствие состоянию: эффект существует только когда
     * усиление выше MIN_ACTIVE_DB. При 0 дБ эффект не создаётся — нулевое
     * усиление не меняет звук (эффект на части HAL просаживал уровень).
     */
    private void sync() {
        boolean want = gainDb > MIN_ACTIVE_DB && sessionId > 0;
        if (want) {
            if (effect == null) {
                try {
                    LoudnessEnhancer le = new LoudnessEnhancer(sessionId);
                    le.setEnabled(true);
                    effect = le;
                    Log.i(TAG, "LoudnessEnhancer attached (gain=" + gainDb + ")");
                } catch (Exception e) {
                    Log.w(TAG, "loudness effect unavailable", e);
                    effect = null;
                }
            }
            if (effect != null) {
                try {
                    effect.setTargetGain(Math.round(gainDb * 100f));
                } catch (Exception e) {
                    Log.w(TAG, "setTargetGain failed", e);
                }
            }
        } else if (effect != null) {
            release();
        }
        Log.i(TAG, "sync: gain=" + gainDb + " active=" + (effect != null));
    }

    private void load() {
        gainDb = clamp(prefs.getFloat(KEY_GAIN, 0f));
    }

    private void save() {
        prefs.edit().putFloat(KEY_GAIN, gainDb).apply();
    }

    private static float clamp(float db) {
        return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, db));
    }
}
