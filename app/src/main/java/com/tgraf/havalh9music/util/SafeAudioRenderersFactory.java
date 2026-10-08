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
import android.media.AudioManager;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;

/**
 * Фабрика рендереров с «безопасным» аудиовыходом для ГУ: любой поток перед
 * AudioTrack приводится к 16 битам и штатной частоте устройства.
 * <p>
 * libFLAC декодирует hi-res файлы независимо от системного Qualcomm-кодека,
 * который на ГУ зависал без ошибки. Декодированный PCM приводим к совместимому
 * с Android 9 формату; частота входного файла при этом может быть 96/192 кГц.
 * <p>
 * Частота вывода — {@link #preferredOutputRateHz(Context)}: штатная частота
 * устройства ({@code PROPERTY_OUTPUT_SAMPLE_RATE}), с запасным значением 48 кГц.
 * Так hi-res приводится к штатному формату вывода, а обычные файлы
 * (если штатная частота совпадает) не затрагиваются.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SafeAudioRenderersFactory extends DefaultRenderersFactory {

    /**
     * Запасная частота вывода, если устройство не сообщает штатную.
     */
    private static final int FALLBACK_OUTPUT_RATE_HZ = 48000;

    public SafeAudioRenderersFactory(Context context) {
        super(context);
        setMediaCodecSelector(FlacDecoderSettings.selector(context));
    }

    @Override
    protected void buildAudioRenderers(@NonNull Context context, int extensionRendererMode,
                                       @NonNull MediaCodecSelector selector, boolean fallback,
                                       @NonNull AudioSink sink,
                                       @NonNull Handler handler,
                                       @NonNull AudioRendererEventListener listener,
                                       ArrayList<Renderer> out) {
        // libFLAC получает приоритет для FLAC и общий безопасный PCM-выход.
        // MP3 не поддерживается этим рендерером и остаётся у системного декодера.
        // Штатные расширения отключены: иначе Media3 может добавить второй FLAC-рендерер.
        out.add(new SelectableFlacAudioRenderer(context, handler, listener, sink));
        super.buildAudioRenderers(context, EXTENSION_RENDERER_MODE_OFF,
                selector, fallback, sink, handler, listener, out);
    }

    @Override
    @NonNull
    protected AudioSink buildAudioSink(@NonNull Context context,
                                       boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        SonicAudioProcessor resampler = new SonicAudioProcessor();
        resampler.setOutputSampleRateHz(preferredOutputRateHz(context));
        return new DefaultAudioSink.Builder(context)
                // Сначала 24 бита → 16 бит (Sonic умеет только 16-битный PCM),
                // затем ресемплинг любой частоты → штатная частота устройства.
                .setAudioProcessors(new AudioProcessor[]{
                        new ToInt16PcmAudioProcessor(),
                        resampler
                })
                // enableFloatOutput намеренно игнорируется: Sonic требует PCM 16 бит.
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build();
    }

    /**
     * Штатная частота аудиовыхода устройства, которую сообщает система
     * ({@code AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE}). Именно к ней безопасно
     * приводить hi-res, чтобы не упираться в ограничения HAL. Если система не
     * сообщает разумного значения — 48 кГц.
     */
    private static int preferredOutputRateHz(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                String v = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                if (v != null) {
                    int rate = Integer.parseInt(v.trim());
                    if (rate >= 8000 && rate <= 192000) {
                        return rate;
                    }
                }
            }
        } catch (Exception ignored) {
            // Некоторые прошивки бросают/возвращают мусор — используем запасное значение.
        }
        return FALLBACK_OUTPUT_RATE_HZ;
    }
}
