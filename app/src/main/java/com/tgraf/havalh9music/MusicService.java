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

package com.tgraf.havalh9music;

import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioManager;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.tgraf.havalh9music.util.SafeAudioRenderersFactory;

/**
 * Фоновая служба воспроизведения: MediaSessionService + ExoPlayer.
 * Foreground Service с типом mediaPlayback (манифест) — сервис автоматически
 * поднимается в foreground при начале воспроизведения, показывая уведомление
 * и обрабатывая медиа-кнопки руля через MediaSession.
 */
public class MusicService extends MediaSessionService {

    /**
     * Аудио-сессия ExoPlayer (для привязки эквалайзера из UI).
     */
    private static volatile int audioSessionId;

    /**
     * Живой ExoPlayer (для прямого сброса из UI-процесса — MediaController
     * на части прошивок/эмуляторах не доносит команды до сессии).
     */
    private static volatile ExoPlayer playerRef;

    private ExoPlayer player;
    private MediaSession session;

    public static int getAudioSessionId() {
        return audioSessionId;
    }

    /**
     * Живой ExoPlayer для чтения UI-данных (позиция/состояние/трек) напрямую.
     * MediaController на части прошивок/эмуляторов (в т.ч. ГУ API 28) отдаёт
     * устаревшие значения — нужен прямой доступ к плееру в том же процессе.
     */
    public static ExoPlayer getPlayerRef() {
        return playerRef;
    }

    /**
     * Останавливает воспроизведение и очищает очередь напрямую через ExoPlayer.
     * UI хранит MediaController к этой же сессии, но команды контроллера на
     * некоторых прошивках теряются — прямой доступ к плееру в том же процессе надёжнее.
     */
    public static void resetPlaybackNow() {
        ExoPlayer p = playerRef;
        if (p == null) {
            return;
        }
        try {
            p.pause();
            p.clearMediaItems();
        } catch (Exception ignored) {
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();

        // Release: глушим внутренние логи Media3 (ExoPlayer/MediaSession) —
        // при проигрывании их сообщений очень много, а диагностика нужна
        // только в debug. Логи нашего кода вырезает R8 (proguard-rules.pro).
        if (!BuildConfig.DEBUG) {
            androidx.media3.common.util.Log.setLogLevel(
                    androidx.media3.common.util.Log.LOG_LEVEL_OFF);
        }

        // Генерируем валидный audio session id и сразу фиксируем его в статике.
        // Полагаться на onAudioSessionIdChanged нельзя: колбек не вызывается
        // на некоторых прошивках/эмуляторах, и тогда EQ вообще не привязывается.
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioSessionId = am.generateAudioSessionId();

        // Буфер ExoPlayer: 50000 / 50000 / 2500 (мс)
        player = new ExoPlayer.Builder(this, new SafeAudioRenderersFactory(this))
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(50000, 50000, 2500, 5000)
                        .build())
                .build();

        playerRef = player;

        // Явно задаём сессию (плеер ещё idle) — EQ в UI будет привязываться к ней.
        player.setAudioSessionId(audioSessionId);

        // Тикер UI привязывает эквалайзер к актуальной аудио-сессии.
        player.addListener(new Player.Listener() {
            @Override
            @OptIn(markerClass = UnstableApi.class)
            public void onAudioSessionIdChanged(int id) {
                audioSessionId = id;
            }
        });

        // Сессия: возврат в Activity по тапу на уведомление
        Intent activityIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(
                this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .build();
    }

    /**
     * MediaSessionService требует реализацию onGetSession.
     */
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        playerRef = null;
        super.onDestroy();
    }
}
