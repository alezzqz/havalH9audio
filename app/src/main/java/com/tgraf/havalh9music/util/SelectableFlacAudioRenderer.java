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
import android.os.Handler;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.flac.LibflacAudioRenderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;

/**
 * Ручной системный выбор должен обходить libFLAC, иначе сравнение декодеров невозможно.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SelectableFlacAudioRenderer extends LibflacAudioRenderer {
    private final Context context;

    public SelectableFlacAudioRenderer(Context context, Handler handler,
                                       AudioRendererEventListener listener, AudioSink sink) {
        super(handler, listener, sink);
        this.context = context.getApplicationContext();
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        return FlacDecoderSettings.useSoftware(context)
                ? super.supportsFormatInternal(format) : C.FORMAT_UNSUPPORTED_TYPE;
    }
}
