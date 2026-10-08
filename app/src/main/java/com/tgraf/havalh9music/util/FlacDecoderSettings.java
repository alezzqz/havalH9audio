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

import androidx.annotation.OptIn;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.flac.FlacLibrary;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * libFLAC обходит зависание системного Qualcomm-декодера на hi-res файлах ГУ.
 */
@OptIn(markerClass = UnstableApi.class)
public final class FlacDecoderSettings {
    public static final String SOFTWARE = "libFLAC";

    private FlacDecoderSettings() {
    }

    public static String selected(Context context) {
        String name = context.getSharedPreferences("audio_decoder", Context.MODE_PRIVATE)
                .getString("flac", SOFTWARE);
        // Старый вариант «Автоматически» теперь означает встроенный декодер.
        return name == null || name.isEmpty() ? SOFTWARE : name;
    }

    public static void select(Context context, String name) {
        context.getSharedPreferences("audio_decoder", Context.MODE_PRIVATE).edit()
                .putString("flac", name).apply();
    }

    public static boolean useSoftware(Context context) {
        return SOFTWARE.equals(selected(context));
    }

    /**
     * Перечисление выполняется вне UI; список содержит доступные Media3 декодеры.
     */
    public static List<String> discover() {
        List<String> result = new ArrayList<>();
        if (FlacLibrary.isAvailable()) {
            result.add(SOFTWARE);
        }
        try {
            for (MediaCodecInfo info : MediaCodecSelector.DEFAULT.getDecoderInfos(
                    MimeTypes.AUDIO_FLAC, false, false)) {
                if (!result.contains(info.name)) {
                    result.add(info.name);
                }
            }
        } catch (Exception ignored) {
            // Ошибка перечисления прошивки не мешает встроенной библиотеке.
        }
        return result;
    }

    public static MediaCodecSelector selector(Context context) {
        Context app = context.getApplicationContext();
        return (mime, secure, tunneling) -> {
            List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mime, secure, tunneling);
            if (!MimeTypes.AUDIO_FLAC.equals(mime)) {
                return infos;
            }
            String choice = selected(app);
            if (SOFTWARE.equals(choice)) {
                return Collections.emptyList();
            }
            for (MediaCodecInfo info : infos) {
                if (choice.equals(info.name)) {
                    return Collections.singletonList(info);
                }
            }
            return Collections.emptyList();
        };
    }
}
