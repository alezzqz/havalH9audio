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

package com.tgraf.havalh9music.ui;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.tgraf.havalh9music.MusicService;
import com.tgraf.havalh9music.R;
import com.tgraf.havalh9music.model.BreadcrumbLevel;
import com.tgraf.havalh9music.model.LibraryRow;
import com.tgraf.havalh9music.model.TrackEntity;
import com.tgraf.havalh9music.model.UsbDevice;
import com.tgraf.havalh9music.util.CoverArtLoader;
import com.tgraf.havalh9music.util.EqualizerManager;
import com.tgraf.havalh9music.util.LibraryCacheManager;
import com.tgraf.havalh9music.util.LoudnessManager;
import com.tgraf.havalh9music.util.UsbFailureSimulator;
import com.tgraf.havalh9music.util.UsbStorageHelper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ViewModel: состояние библиотеки (каталог + крошки + строки списка),
 * состояние плеера (через MediaController к MusicService) и эквалайзер.
 */
public class PlayerViewModel extends AndroidViewModel {

    private static final String TAG = "PlayerViewModel";
    private static final String PREFS_PLAY = "playback_state";
    /**
     * Выбранный пользователем корень библиотеки (запоминается, чтобы не спрашивать каждый старт).
     */
    private static final String PREFS_ROOT = "usb_root";
    private static final String KEY_ROOT_PATH = "path";
    private static final String KEY_ROOT_UUID = "uuid";
    private static final int MAX_BREADCRUMBS = 6; // включая корень
    /**
     * Перепроверки ложного «отключения»: 3 попытки по 500 мс, прежде чем сбросить.
     */
    private static final int UNMOUNT_RECHECKS = 3;
    private static final long UNMOUNT_RECHECK_MS = 500L;
    /**
     * Повторы пустой нумерации томов: 3 попытки по 700 мс (том мог ещё не подняться).
     */
    private static final int USB_INIT_RETRIES = 3;
    private static final long USB_INIT_RETRY_MS = 700L;

    // ---- LiveData ----
    private final MutableLiveData<Boolean> usbAvailable = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> scanning = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> splashVisible = new MutableLiveData<>(true);
    private final MutableLiveData<String> scanProgress = new MutableLiveData<>("");

    /**
     * Диалоговый выбор накопителя: после скана нужно раскрыть корень (навигация сброшена).
     */
    private boolean resetNavOnScanComplete = false;
    private final MutableLiveData<List<BreadcrumbLevel>> breadcrumbs =
            new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<LibraryRow>> rows =
            new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<TrackEntity> currentTrack = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isPlaying = new MutableLiveData<>(false);
    private final MutableLiveData<Long> position = new MutableLiveData<>(0L);
    private final MutableLiveData<Long> duration = new MutableLiveData<>(0L);
    /**
     * Режим воспроизведения: false — последовательный, true — случайный.
     */
    private final MutableLiveData<Boolean> shuffle = new MutableLiveData<>(false);
    private final MutableLiveData<String> infoMessage = new MutableLiveData<>();
    // Однократное событие: после восстановления на старте прокрутить список к треку
    private final MutableLiveData<Boolean> scrollToRestoredTrack = new MutableLiveData<>(false);
    /**
     * Список найденных накопителей для диалога выбора (не null — показать диалог).
     */
    private final MutableLiveData<List<UsbDevice>> usbDevicesPicker = new MutableLiveData<>();
    /**
     * Однократное событие: файл трека не найден (устаревший кеш / смена накопителя).
     */
    private final MutableLiveData<TrackEntity> missingTrackError = new MutableLiveData<>();
    /**
     * Однократное событие: сообщение об ошибке воспроизведения.
     */
    private final MutableLiveData<String> playbackError = new MutableLiveData<>();

    // ---- Службы ----
    private final Context app;
    private final LibraryCacheManager cache;
    private final CoverArtLoader covers;
    private final EqualizerManager eq;
    private final LoudnessManager loudness;

    // ---- Навигация ----
    private File usbRoot;
    private boolean initialized;
    /**
     * Осталось перепроверок «отключения» (см. {@link #onUsbUnmounted()}).
     */
    private int unmountChecksLeft;
    /**
     * Осталось повторов нумерации томов при пустом ответе (см. {@link #startLibraryInit()}).
     */
    private int usbInitRetriesLeft = USB_INIT_RETRIES;
    private int navGen; // счётчик асинхронных загрузок списка (защита от гонок)
    private List<String> currentSegments = new ArrayList<>();
    private final List<TrackEntity> currentQueue = new ArrayList<>();
    /**
     * Каталог, чьи треки сейчас в очереди (для перехода между подкаталогами).
     */
    private String currentQueueDir;
    /**
     * Каталоги с треками в порядке обхода «папки → файлы» (сквозное воспроизведение).
     */
    private List<String> orderedDirs = new ArrayList<>();
    private int dirLoadGen; // счётчик асинхронных загрузок очереди каталога
    /**
     * Текущий режим: false — последовательный, true — случайный.
     */
    private boolean shuffleMode;
    /**
     * Все треки библиотеки — для случайного выбора.
     */
    private List<TrackEntity> allTracks = new ArrayList<>();
    /**
     * История случайного воспроизведения — для кнопки «предыдущий».
     */
    private final ArrayDeque<TrackEntity> shuffleHistory = new ArrayDeque<>();
    private final Random random = new Random();
    /**
     * Однократно прокрутить список к играющему треку (после смены каталога).
     */
    private boolean pendingScrollToTrack;
    /**
     * Подавляем автопереход после окончания трека, пока идёт смена накопителя/скан:
     * во время переключения старая очередь очищена, а ENDED из-за пустого таймлайна
     * не должен запускать следующий трек (тем более — старой библиотеки).
     */
    private boolean suppressAutoAdvance;

    // ---- Плеер ----
    private MediaController controller;
    private boolean connecting;
    /**
     * Живой ExoPlayer, на который уже повешен {@link #playerListener}.
     */
    private ExoPlayer attachedPlayer;
    /**
     * Последний виденный индекс текущего трека (для смены трека без событий).
     */
    private int lastMediaItemIndex = -1;
    /**
     * Сессия, к которой уже привязан эквалайзер.
     */
    private int eqSessionId = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // UI-данные читаем напрямую из живого ExoPlayer (тот же процесс):
            // MediaController на ГУ (API 28) отдаёт устаревшие значения
            // (позиция 0, isPlaying=false, метаданные null), из-за чего правая
            // панель не обновлялась, хотя плеер играл.
            ExoPlayer p = MusicService.getPlayerRef();
            // Слушатель вешаем и на живой плеер: MediaController на ГУ может не
            // донести onPlayerError, а прямой ExoPlayer в том же процессе донесёт
            // (именно поэтому позиция/состояние читаются отсюда же).
            if (p == null) {
                attachedPlayer = null; // сервис освободил плеер
            } else if (p != attachedPlayer) {
                if (attachedPlayer != null) {
                    try {
                        attachedPlayer.removeListener(playerListener);
                    } catch (Exception ignored) {
                    }
                }
                attachedPlayer = p;
                p.addListener(playerListener);
            }
            if (p != null && p.getPlaybackState() == Player.STATE_READY) {
                // Пишем только при изменении: LiveData уведомляет наблюдателей
                // на каждый setValue, а частые одинаковые значения зря
                // перерисовывают правую панель (и не дают UI «затихнуть»).
                long pos = p.getCurrentPosition();
                Long curPos = position.getValue();
                if (curPos == null || curPos != pos) {
                    position.setValue(pos);
                }
                long dur = p.getDuration();
                Long curDur = duration.getValue();
                if (curDur == null || curDur != dur) {
                    duration.setValue(dur);
                }
                boolean playing = p.isPlaying();
                if (!Boolean.valueOf(playing).equals(isPlaying.getValue())) {
                    isPlaying.setValue(playing);
                }
                int idx = p.getCurrentMediaItemIndex();
                if (idx != lastMediaItemIndex) {
                    lastMediaItemIndex = idx;
                    updateCurrentTrack();
                }
                // EQ привязываем здесь, а не в слушателе плеера: слушатель
                // живёт на потоке воспроизведения, и исключение там роняет
                // плеер (видели зависание state=2). В try/catch — на случай
                // отсутствия поддержки эффектов на устройстве.
                int sid = MusicService.getAudioSessionId();
                if (sid > 0 && sid != eqSessionId) {
                    eqSessionId = sid;
                    try {
                        eq.attach(sid);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loudness.attach(sid); // усиление привязывается к той же сессии
                    } catch (Throwable ignored) {
                    }
                }
            }
            // Debug-симулятор сбоев USB (в release — no-op): ложный unmount или
            // пустая нумерация томов, как это бывает на прошивках ГУ.
            int simMode = UsbFailureSimulator.poll(app);
            if (simMode == UsbFailureSimulator.FALSE_UNMOUNT
                    || simMode == UsbFailureSimulator.PERSISTENT_LOSS) {
                onUsbUnmounted();
            } else if (simMode == UsbFailureSimulator.EMPTY_ENUMERATION) {
                startLibraryInit();
            }
            handler.postDelayed(this, 500);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                    || events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                updateCurrentTrack();
            }
            // Позицию/длительность/состояние/текущий трек обновляет ticker
            // напрямую из живого ExoPlayer — MediaController на ГУ (API 28)
            // отдаёт устаревшие значения. EQ тоже привязывается в ticker
            // (исключения на потоке плеера не должны его ронять).
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            // Трек доиграл: в случайном режиме берём случайный по библиотеке,
            // в последовательном — следующий трек каталога (или следующий каталог).
            if (state == Player.STATE_ENDED) {
                onTrackFinished();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            // На ГУ logcat недоступен: отказ воспроизведения показываем на экране.
            String info = app.getString(R.string.playback_error_message);
            Log.e(TAG, "Ошибка воспроизведения: " + info);
            playbackError.setValue(info);
        }
    };

    public PlayerViewModel(@NonNull Application application) {
        super(application);
        this.app = application.getApplicationContext();
        this.cache = new LibraryCacheManager(app);
        this.covers = new CoverArtLoader();
        this.eq = new EqualizerManager(app);
        this.loudness = new LoudnessManager(app);
    }

    // ------------------------------------------------------------------
    // Подключение к сервису
    // ------------------------------------------------------------------

    /**
     * Асинхронно подключает MediaController к MusicService.
     */
    public void connect() {
        // Смена палитры пересоздаёт Activity, но сохраняет ViewModel и живой плеер.
        // Повторное подключение запускало бы восстановление очереди с паузы.
        if (controller != null || connecting) {
            return;
        }
        connecting = true;
        ComponentName cn = new ComponentName(app, MusicService.class);
        SessionToken token = new SessionToken(app, cn);
        ListenableFuture<MediaController> future =
                new MediaController.Builder(app, token).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.addListener(playerListener);
                // Позицию/длительность/состояние UI читает напрямую из живого
                // ExoPlayer (ticker) — контроллер на ГУ отдаёт устаревшие значения.
                // Повтор и случайность ведём сами; ExoPlayer всегда последователен
                // внутри своего плейлиста (иначе не дойдём до STATE_ENDED).
                Player pl = player();
                if (pl != null) {
                    pl.setRepeatMode(Player.REPEAT_MODE_OFF);
                    pl.setShuffleModeEnabled(false);
                }
                shuffle.setValue(shuffleMode);
                position.setValue(0L);
                duration.setValue(0L);
                updateCurrentTrack();
                handler.post(ticker);
                restorePlaybackState();
            } catch (Exception e) {
                Log.e(TAG, "Нет соединения с MusicService", e);
            } finally {
                connecting = false;
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Живой ExoPlayer сервиса (тот же процесс). На ГУ/эмуляторе (API 28)
     * MediaController теряет часть команд: setMediaItems/prepare доходят, а
     * play() — нет, и плеер остаётся в READY с playWhenReady=false (позиция
     * стоит на 0). Поэтому и чтения, и команды направляем напрямую в плеер,
     * а контроллер оставляем запасным путём до готовности сервиса.
     */
    private Player player() {
        ExoPlayer p = MusicService.getPlayerRef();
        return p != null ? p : controller;
    }

    public void playPause() {
        Player p = player();
        if (p == null) {
            return;
        }
        if (p.isPlaying()) {
            p.pause();
        } else {
            p.play();
        }
    }

    /**
     * Старт воспроизведения (кнопка руля).
     */
    public void play() {
        Player p = player();
        if (p != null) {
            p.play();
        }
    }

    /**
     * Пауза (кнопка руля).
     */
    public void pause() {
        Player p = player();
        if (p != null) {
            p.pause();
        }
    }

    /**
     * Стоп: пауза и возврат к началу текущего трека.
     */
    public void stop() {
        Player p = player();
        if (p == null) {
            return;
        }
        p.pause();
        p.seekTo(0);
    }

    /**
     * Следующий трек: в случайном режиме — из всей библиотеки, иначе последовательно.
     */
    public void next() {
        Player p = player();
        if (p == null) {
            return;
        }
        if (shuffleMode) {
            playRandom();
            return;
        }
        if (currentQueue.isEmpty()) {
            playAdjacentDir(+1, false);
            return;
        }
        int idx = currentItemIndex();
        if (currentQueue.size() > 1 && idx >= 0 && idx < currentQueue.size() - 1) {
            TrackEntity nextTrack = currentQueue.get(idx + 1);
            if (missingSourceFile(nextTrack)) {
                notifyMissingFile(nextTrack);
                return;
            }
            p.seekToNextMediaItem();
        } else {
            sequentialNext(currentTrack.getValue());
        }
    }

    /**
     * Предыдущий трек; в начале трека — назад (в случайном режиме — по истории).
     */
    public void previous() {
        Player p = player();
        if (p == null) {
            return;
        }
        if (p.getCurrentPosition() > 3000) {
            p.seekTo(0); // рестарт текущего трека
            return;
        }
        if (shuffleMode) {
            previousInShuffle();
            return;
        }
        if (currentQueue.isEmpty()) {
            playAdjacentDir(-1, true);
            return;
        }
        int idx = currentItemIndex();
        if (currentQueue.size() > 1 && idx > 0) {
            TrackEntity prevTrack = currentQueue.get(idx - 1);
            if (missingSourceFile(prevTrack)) {
                notifyMissingFile(prevTrack);
                return;
            }
            p.seekToPreviousMediaItem();
        } else {
            sequentialPrev(currentTrack.getValue());
        }
    }

    public void seekTo(long ms) {
        Player p = player();
        if (p != null) {
            p.seekTo(ms);
        }
    }

    /**
     * Переключение режима: true — случайный выбор из всей библиотеки,
     * false — последовательное воспроизведение по каталогам.
     * При включении случайного режима плейлист ExoPlayer сжимается до текущего
     * трека (без прерывания звука) — тогда по окончании придёт STATE_ENDED
     * и мы выберем следующий случайный трек.
     */
    public void setShuffleMode(boolean enabled) {
        if (shuffleMode == enabled) {
            return;
        }
        shuffleMode = enabled;
        shuffle.setValue(enabled);
        shuffleHistory.clear();
        Player p = player();
        if (p != null) {
            p.setShuffleModeEnabled(false);
        }
        if (enabled) {
            shrinkQueueToCurrent();
        }
    }

    /**
     * Сброс режима в последовательный (после пересканирования), без перестройки очереди.
     */
    private void resetModeToSequential() {
        shuffleMode = false;
        shuffle.setValue(false);
        shuffleHistory.clear();
        Player p = player();
        if (p != null) {
            p.setShuffleModeEnabled(false);
        }
    }

    // ------------------------------------------------------------------
    // Инициализация библиотеки
    // ------------------------------------------------------------------

    public void startLibraryInit() {
        if (initialized && usbRoot != null) {
            // Уже инициализировано — скрываем заставку (на случай повторного вызова)
            splashVisible.setValue(false);
            return;
        }
        if (usbDevicesPicker.getValue() != null) {
            return; // диалог выбора накопителя уже показан
        }
        initialized = true;

        // 1) Ранее выбранный корень: если он доступен — открываем сразу, без
        //    перечисления томов и без диалога. Так обычный холодный старт не
        //    спрашивает источник и не перечитывает библиотеку.
        File saved = savedRootFile();
        if (saved != null && rootReadable(saved)) {
            usbInitRetriesLeft = USB_INIT_RETRIES;
            initWithRoot(saved);
            return;
        }

        // 2) Сохранённого корня нет/он недоступен — перечисляем тома.
        List<UsbDevice> devices = UsbStorageHelper.findAllUsbRoots(app);
        if (UsbFailureSimulator.consumeEmptyEnumeration(app)) {
            devices = new ArrayList<>(); // debug-симулятор: «тома не найдены»
        }
        if (devices.isEmpty()) {
            // Тома не видны, но библиотека уже работает и корень читается —
            // ложный «пустой» ответ (том на миг занят/ещё монтируется):
            // библиотеку не рушим, только снимаем заглушку.
            if (usbRoot != null && rootReadable(usbRoot)) {
                usbAvailable.setValue(true);
                splashVisible.setValue(false);
                return;
            }
            // Тома ещё не поднялись (сразу после монтирования) — пробуем ещё раз,
            // прежде чем показать «нет USB».
            if (usbInitRetriesLeft-- > 0) {
                handler.postDelayed(this::startLibraryInit, USB_INIT_RETRY_MS);
                return;
            }
            usbRoot = null;
            usbAvailable.setValue(false);
            rows.setValue(new ArrayList<>());
            currentSegments.clear();
            updateBreadcrumbs(new ArrayList<>());
            currentQueue.clear();
            splashVisible.setValue(false); // нет USB — нечего ждать
            return;
        }
        usbInitRetriesLeft = USB_INIT_RETRIES;

        // 3) Тот же том мог сменить точку монтирования — ищем по сохранённому UUID.
        String savedUuid = savedRootUuid();
        if (savedUuid != null) {
            for (UsbDevice d : devices) {
                if (savedUuid.equals(UsbStorageHelper.getVolumeUuid(d.root))) {
                    initWithRoot(d.root);
                    return;
                }
            }
        }

        if (devices.size() == 1) {
            initWithRoot(devices.get(0).root);
        } else {
            // Первый запуск (или сохранённый том не найден) и томов несколько —
            // пользователь выбирает в диалоге.
            usbDevicesPicker.setValue(devices);
        }
    }

    /**
     * Инициализация библиотеки на выбранном корне (кэш или полный скан).
     */
    private void initWithRoot(File root) {
        usbRoot = root;
        saveRootSelection(root); // запоминаем выбор — больше не спрашиваем на старте
        usbAvailable.setValue(true);
        handler.removeCallbacks(unmountRecheck); // том найден — проверки «отключения» не нужны
        unmountChecksLeft = 0;
        refreshOrderedDirs(null); // порядок каталогов для сквозного воспроизведения
        refreshAllTracks(null);   // все треки — для случайного режима
        // Проверка кэша — в фоне (Room нельзя трогать из главного потока)
        cache.checkCacheAsync(root, result -> {
            if (result == LibraryCacheManager.CacheCheck.VALID) {
                suppressAutoAdvance = false; // кэш готов — автопереход снова работает
                // Кэш валиден: загружаем корневой каталог и скрываем заставку
                // после того, как строки подгрузятся из Room.
                openDir(new ArrayList<>(), () -> splashVisible.setValue(false));
                restorePlaybackState(); // безвредно до готовности контроллера
            } else {
                scanLibrary(true); // кэша на этот том нет — полный скан
            }
        });
    }

    /**
     * Событие «том подключён». Ложные повторные события игнорируем.
     */
    public void onUsbMounted() {
        // Том вернулся — отменяем отложенную перепроверку «отключения».
        handler.removeCallbacks(unmountRecheck);
        unmountChecksLeft = 0;
        if (initialized && usbRoot != null) {
            return; // библиотека уже работает с тем же корнем
        }
        initialized = false;
        usbRoot = null;
        startLibraryInit();
    }

    /**
     * Событие «том отключён». Ложные события прошивки игнорируем молча.
     */
    public void onUsbUnmounted() {
        // Прошивки ГУ шлют MEDIA_UNMOUNTED/REMOVED/EJECT без реального отключения
        // (фоновый анализ тома, «моргание» монтирования). Не сбрасываем сразу:
        // на дешёвой флешке перечисление каталога может на миг «отказать», и тогда
        // поверх рабочего списка вылезает заглушка «нет USB». Даём тому успокоиться
        // и перепроверяем читаемость корня несколько раз.
        if (usbRoot != null) {
            if (usbRootStillReadable()) {
                return; // корень читается — событие ложное
            }
            unmountChecksLeft = UNMOUNT_RECHECKS;
            handler.removeCallbacks(unmountRecheck);
            handler.postDelayed(unmountRecheck, UNMOUNT_RECHECK_MS);
            return;
        }
        resetLibraryAfterUnmount();
    }

    /**
     * Перепроверка «отключения»: корень вернулся — событие ложно, иначе сброс.
     */
    private final Runnable unmountRecheck = new Runnable() {
        @Override
        public void run() {
            if (usbRoot == null) {
                unmountChecksLeft = 0;
                return;
            }
            if (usbRootStillReadable()) {
                unmountChecksLeft = 0;
                Log.i(TAG, "unmount: корень снова читается — ложное событие");
                return;
            }
            if (--unmountChecksLeft > 0) {
                handler.postDelayed(this, UNMOUNT_RECHECK_MS);
                return;
            }
            Log.i(TAG, "unmount: корень недоступен — сбрасываем библиотеку");
            resetLibraryAfterUnmount();
        }
    };

    /**
     * Реальное отключение: чистим библиотеку, навигацию, очереди и плеер.
     */
    private void resetLibraryAfterUnmount() {
        initialized = false;
        usbRoot = null;
        usbAvailable.setValue(false);
        rows.setValue(new ArrayList<>());
        currentSegments.clear();
        updateBreadcrumbs(new ArrayList<>());
        currentQueue.clear();
        currentQueueDir = null;
        orderedDirs = new ArrayList<>();
        allTracks = new ArrayList<>();
        shuffleHistory.clear();
        resetModeToSequential();
        pendingScrollToTrack = false;
        dirLoadGen++;   // отменяем фоновые загрузки очередей/каталогов отключённого накопителя
        navGen++;
        suppressAutoAdvance = true; // глушим автопереход, пока не завершится повторный скан
        resetPlayerSession();
    }

    /**
     * Корень библиотеки всё ещё читается (защита от ложных событий отключения).
     */
    private boolean usbRootStillReadable() {
        return rootReadable(usbRoot);
    }

    /**
     * Корень читается: существует, каталог, доступен листинг. StatFs не
     * перечисляет каталог (listFiles() на дешёвой vfat-флешке под нагрузкой
     * может на миг вернуть null) и падает, только если том отмонтирован.
     */
    private boolean rootReadable(File root) {
        if (UsbFailureSimulator.forceUnreadable(app)) {
            return false; // debug-симулятор
        }
        if (root == null || !root.exists() || !root.isDirectory() || !root.canRead()) {
            return false;
        }
        try {
            new StatFs(root.getAbsolutePath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Запомненный выбор накопителя
    // ------------------------------------------------------------------

    /**
     * Запоминает выбранный корень (путь + UUID тома) между запусками.
     */
    private void saveRootSelection(File root) {
        if (root == null) {
            return;
        }
        app.getSharedPreferences(PREFS_ROOT, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROOT_PATH, root.getAbsolutePath())
                .putString(KEY_ROOT_UUID, UsbStorageHelper.getVolumeUuid(root))
                .apply();
    }

    /**
     * Сохранённый корень библиотеки или null, если пользователь ещё не выбирал.
     */
    private File savedRootFile() {
        String path = app.getSharedPreferences(PREFS_ROOT, Context.MODE_PRIVATE)
                .getString(KEY_ROOT_PATH, null);
        return path == null ? null : new File(path);
    }

    /**
     * Сохранённый UUID тома или null (поиск тома при смене точки монтирования).
     */
    private String savedRootUuid() {
        return app.getSharedPreferences(PREFS_ROOT, Context.MODE_PRIVATE)
                .getString(KEY_ROOT_UUID, null);
    }

    /**
     * Полный сброс плеера и информации о треке при смене/отключении накопителя:
     * текущего трека в новой библиотеке может больше не быть. Параллельно чистим
     * сохранённое состояние, чтобы при следующем старте не восстановился старый трек.
     */
    private void resetPlayerSession() {
        // Прямой сброс через ExoPlayer в том же процессе: команды MediaController
        // на части прошивок/эмуляторах не доходят до сессии.
        MusicService.resetPlaybackNow();
        if (MusicService.getPlayerRef() == null && controller != null) {
            // Запасной путь, если сервис ещё не успел создать плеер.
            try {
                controller.pause();
                controller.clearMediaItems();
            } catch (Exception e) {
                Log.w(TAG, "resetPlayerSession: сброс плеера не удался", e);
            }
        }
        isPlaying.setValue(false);
        currentTrack.setValue(null);
        position.setValue(0L);
        duration.setValue(0L);
        app.getSharedPreferences(PREFS_PLAY, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    /**
     * Принудительное обновление библиотеки (кнопка): находим все медиаустройства
     * и показываем диалог выбора — пользователь указывает, какой накопитель сканировать.
     * Тост «Нет USB» — только если активной библиотеки нет вовсе: надпись не
     * должна вылезать поверх живого списка во время воспроизведения.
     */
    public void refreshLibrary() {
        List<UsbDevice> devices = UsbStorageHelper.findAllUsbRoots(app);
        if (!devices.isEmpty()) {
            usbDevicesPicker.setValue(devices);
            return;
        }
        if (usbRoot == null) {
            infoMessage.setValue(app.getString(R.string.usb_not_found));
            return;
        }
        if (usbRootStillReadable()) {
            // Накопитель не виден списком томов, но считывается — просто
            // пересканируем его (восстановление после сбоя томов прошивки).
            scanLibrary(false);
        }
        // иначе: томов нет и корень не читается — заглушка «нет USB» уже показана
    }

    /**
     * Пользователь выбрал накопитель в диалоге: сброс состояния и полный скан.
     */
    public void selectUsbDevice(UsbDevice dev) {
        usbDevicesPicker.setValue(null);
        if (dev == null || dev.root == null) {
            return;
        }
        File newRoot = dev.root;
        if (!newRoot.equals(usbRoot)) {
            usbRoot = newRoot;
        }
        saveRootSelection(newRoot); // запоминаем выбор — на старте не спрашиваем снова
        // Перечитывание библиотеки: и на том же накопителе, и на другом сбрасываем
        // навигацию, очереди, режимы и плеер — текущий трек может исчезнуть из
        // библиотеки (файлы переименованы/удалены) или принадлежать другой флешке.
        currentSegments.clear();
        updateBreadcrumbs(new ArrayList<>());
        rows.setValue(new ArrayList<>());
        currentQueue.clear();
        currentQueueDir = null;
        orderedDirs = new ArrayList<>();
        allTracks = new ArrayList<>();
        shuffleHistory.clear();
        resetModeToSequential();
        pendingScrollToTrack = false;
        dirLoadGen++;   // отменяем фоновые загрузки очередей/каталогов
        navGen++;       // и устаревшие загрузки строк
        suppressAutoAdvance = true; // глушим автопереход до завершения скана
        resetNavOnScanComplete = true; // после скана — раскрыть корень
        resetPlayerSession();
        scanLibrary(false);
    }

    /**
     * Отмена выбора накопителя: ничего не сканируем; без корня — заглушка «нет USB».
     */
    public void cancelUsbPick() {
        usbDevicesPicker.setValue(null);
        suppressAutoAdvance = false; // скана не будет — автопереход снова разрешён
        if (usbRoot == null) {
            usbAvailable.setValue(false);
            rows.setValue(new ArrayList<>());
            currentSegments.clear();
            updateBreadcrumbs(new ArrayList<>());
            currentQueue.clear();
            splashVisible.setValue(false);
        }
    }

    /**
     * Скан библиотеки; restoreAfterScan — восстановить после скана (старт при невалидном кэше).
     */
    private void scanLibrary(boolean restoreAfterScan) {
        if (usbRoot == null) {
            return;
        }
        suppressAutoAdvance = true; // на время скана автопереход глушим
        scanning.setValue(true);
        scanProgress.setValue("");
        cache.scan(usbRoot, new LibraryCacheManager.ScanListener() {
            @Override
            public void onProgress(int completed, int total) {
                scanProgress.setValue(app.getString(R.string.scan_progress, completed));
            }

            @Override
            public void onBrokenCue(String fileName) {
                infoMessage.setValue(app.getString(R.string.broken_cue, fileName));
            }

            @Override
            public void onComplete(File root, int trackCount) {
                Log.i(TAG, "scan done: tracks=" + trackCount + " root=" + root.getAbsolutePath());
                suppressAutoAdvance = false; // скан завершён — автопереход можно снова
                scanning.setValue(false);
                splashVisible.setValue(false); // скан завершён — скрываем заставку
                refreshOrderedDirs(null); // кэш переписан — обновляем порядок каталогов
                refreshAllTracks(null);   // и список всех треков для случайного режима
                resetModeToSequential();  // после пересканирования — всегда последовательный
                if (restoreAfterScan) {
                    // Стартовый скан (MISS/STALE при загрузке): раскрываем корень и
                    // восстанавливаем позицию.
                    currentSegments.clear();
                    navigateTo(0);
                    restorePlaybackState(); // старт с последнего каталога и трека
                } else if (resetNavOnScanComplete) {
                    // Пользователь выбрал накопитель в диалоге: навигация была сброшена,
                    // после скана раскрываем корень.
                    currentSegments.clear();
                    navigateTo(0);
                }
                // Третий случай: тихий перескан (refreshLibrary, usbRootStillReadable) —
                // позицию пользователя НЕ трогаем, иначе его выбрасывает в корень.
                resetNavOnScanComplete = false;
                infoMessage.setValue(app.getString(R.string.scan_done, trackCount));
            }

            @Override
            public void onError(String message) {
                suppressAutoAdvance = false;
                scanning.setValue(false);
                splashVisible.setValue(false); // ошибка — тоже скрываем заставку
                infoMessage.setValue(message);
            }
        });
    }

    // ------------------------------------------------------------------
    // Навигация
    // ------------------------------------------------------------------

    public void navigateRoot() {
        navigateTo(0);
    }

    /**
     * Переход на уровень выше; если уже в корне — false (навигации не было).
     */
    public boolean navigateUp() {
        if (usbRoot == null || currentSegments.isEmpty()) {
            return false;
        }
        navigateTo(currentSegments.size() - 1);
        return true;
    }

    /**
     * depth = уровень крошек (0 — корень).
     */
    public void navigateTo(int depth) {
        if (usbRoot == null) {
            return;
        }
        if (depth < 0) {
            depth = 0;
        }
        if (depth > currentSegments.size()) {
            depth = currentSegments.size();
        }
        List<String> segs = new ArrayList<>(currentSegments.subList(0, depth));
        openDir(segs);
    }

    /**
     * Открыть произвольный подкаталог флешки.
     */
    public void openFolder(String path) {
        if (usbRoot == null || path == null) {
            return;
        }
        List<String> segs = segmentsBetween(usbRoot, new File(path));
        openDir(segs);
    }

    /**
     * Кнопка «Список»: показать в списке играющий трек. Если открыт другой
     * каталог — переходим в каталог трека, после загрузки строк прокручиваем
     * список к нему (и подсвечиваем его как активный).
     */
    public void jumpToCurrentTrack() {
        TrackEntity cur = currentTrack.getValue();
        if (usbRoot == null || cur == null || cur.parentPath == null) {
            scrollToRestoredTrack.setValue(true);
            return;
        }
        // Трек уже в текущем списке — достаточно прокрутки
        List<LibraryRow> list = rows.getValue();
        if (list != null) {
            for (LibraryRow r : list) {
                if (r.kind == LibraryRow.Kind.TRACK
                        && cur.path != null && cur.path.equals(r.track.path)) {
                    scrollToRestoredTrack.setValue(true);
                    return;
                }
            }
        }
        // Открываем каталог трека; прокрутка произойдёт после загрузки строк
        openDir(segmentsBetween(usbRoot, new File(cur.parentPath)),
                () -> scrollToRestoredTrack.setValue(true));
    }

    private void openDir(List<String> segs) {
        openDir(segs, null);
    }

    /**
     * Открыть каталог; after — вызывается на главном потоке после загрузки строк.
     */
    private void openDir(List<String> segs, Runnable after) {
        File dir = usbRoot;
        for (String s : segs) {
            dir = new File(dir, s);
        }
        currentSegments = segs;
        loadRows(dir, segs, after);
    }

    /**
     * Строки текущего каталога: папки (по имени) + треки из кэша (по имени).
     * Треки грузим асинхронно (Room запрещает главный поток); navGen отбрасывает
     * устаревшие ответы при быстрых свайпах/кликах по крошкам.
     */
    private void loadRows(File dir, List<String> segs, Runnable after) {
        final int gen = ++navGen;
        cache.loadRowsAsync(dir, result -> {
            Log.i(TAG, "loadRows " + dir.getAbsolutePath() + " -> " + result.size());
            if (gen != navGen) {
                return; // устаревший запрос
            }
            rows.setValue(result);
            // Библиотека реально работает — снимаем возможную залипшую заглушку
            // «нет USB», если корень читается (ложное событие отключения).
            if (usbRoot != null && usbRootStillReadable()
                    && !Boolean.TRUE.equals(usbAvailable.getValue())) {
                usbAvailable.setValue(true);
            }
            updateBreadcrumbs(segs);
            // Скрываем заставку при первой же реально применённой загрузке
            // списка. Так надёжнее, чем только через after: при старте идут
            // подряд openDir(корень, hideSplash) и openDir(последний каталог),
            // и второй «инвалидирует» колбэк первого по navGen — заставка
            // оставалась поверх экрана навсегда.
            if (Boolean.TRUE.equals(splashVisible.getValue())) {
                splashVisible.setValue(false);
            }
            if (after != null) {
                after.run();
            }
        });
    }

    private void updateBreadcrumbs(List<String> segs) {
        List<String> all = new ArrayList<>();
        all.add(app.getString(R.string.root_label));
        all.addAll(segs);

        List<BreadcrumbLevel> levels = new ArrayList<>();
        if (all.size() > MAX_BREADCRUMBS) {
            // длинный путь сворачиваем: USB > … > последние 4
            levels.add(new BreadcrumbLevel(all.get(0), 0, false, false));
            levels.add(new BreadcrumbLevel(app.getString(R.string.ellipsis), -1, false, true));
            int start = all.size() - 4;
            for (int i = start; i < all.size(); i++) {
                levels.add(new BreadcrumbLevel(all.get(i), i,
                        i == all.size() - 1, false));
            }
        } else {
            for (int i = 0; i < all.size(); i++) {
                levels.add(new BreadcrumbLevel(all.get(i), i,
                        i == all.size() - 1, false));
            }
        }
        breadcrumbs.setValue(levels);
    }

    private List<String> segmentsBetween(File root, File dir) {
        List<String> segs = new ArrayList<>();
        String rootAbs = root.getAbsolutePath();
        File cur = dir;
        while (cur != null && !cur.getAbsolutePath().equals(rootAbs)) {
            segs.add(0, cur.getName());
            cur = cur.getParentFile();
        }
        return segs;
    }

    /**
     * Каталог лежит внутри usbRoot (или равен ему).
     */
    private boolean isInsideUsb(File dir) {
        String rootAbs = usbRoot.getAbsolutePath();
        return dir.getAbsolutePath().equals(rootAbs)
                || dir.getAbsolutePath().startsWith(rootAbs + File.separator);
    }

    // ------------------------------------------------------------------
    // Воспроизведение
    // ------------------------------------------------------------------

    /**
     * Клик по строке списка: каталог открываем, трек — ставим очередь.
     */
    public void playRow(int index) {
        List<LibraryRow> list = rows.getValue();
        if (list == null || index < 0 || index >= list.size() || controller == null) {
            return;
        }
        LibraryRow row = list.get(index);
        if (row.kind == LibraryRow.Kind.FOLDER) {
            openFolder(row.folderPath);
            return;
        }
        if (shuffleMode) {
            // Случайный режим: очередь — один трек, далее выберется случайный
            pushShuffleHistory(currentTrack.getValue());
            startSingle(row.track);
            return;
        }
        // Последовательный: очередь = все треки текущего каталога, старт — с выбранного.
        // Дальше воспроизведение идёт сквозь все каталоги (см. playAdjacentDir).
        currentQueueDir = currentDirPath();
        currentQueue.clear();
        int start = 0;
        for (LibraryRow r : list) {
            if (r.kind == LibraryRow.Kind.TRACK) {
                currentQueue.add(r.track);
                if (r.track.path.equals(row.track.path)) {
                    start = currentQueue.size() - 1;
                }
            }
        }
        buildAndPlay(start, true);
    }

    /**
     * Собирает MediaItem из текущей очереди и запускает воспроизведение.
     * autoplay=false — только prepare (восстановление позиции при старте).
     */
    private void buildAndPlay(int startIndex, boolean autoplay) {
        if (controller == null || currentQueue.isEmpty()) {
            return;
        }
        int idx = Math.max(0, Math.min(startIndex, currentQueue.size() - 1));
        // Устаревший кеш: файл первого трека отсутствует — не ставим очередь,
        // а показываем диалог, чтобы пользователь понял причину.
        TrackEntity start = currentQueue.get(idx);
        if (missingSourceFile(start)) {
            notifyMissingFile(start);
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (TrackEntity t : currentQueue) {
            items.add(toMediaItem(t));
        }
        Player p = player();
        if (p == null) {
            return;
        }
        p.setMediaItems(items, idx, 0);
        p.prepare();
        if (autoplay) {
            p.play();
        }
        // Эквалайзер привяжется в тикере (по готовности плеера).
        // Эквалайзер привяжется по событию EVENT_AUDIO_SESSION_ID
    }

    // ------------------------------------------------------------------
    // Сквозное воспроизведение (последовательный режим): после последнего
    // трека каталога — первый трек следующего каталога, и наоборот.
    // ------------------------------------------------------------------

    /**
     * Загружает каталог и запускает воспроизведение с указанного трека
     * (fromEnd — с последнего). Левая панель переводится в этот каталог.
     *
     * @param startIndex индекс трека в каталоге (при fromEnd не используется)
     * @param fromEnd    true — старт с последнего трека (переход «назад»)
     */
    private void playDir(String dirPath, int startIndex, boolean fromEnd, boolean autoplay) {
        if (usbRoot == null || dirPath == null) {
            return;
        }
        final int gen = ++dirLoadGen;
        cache.tracksInAsync(new File(dirPath), tracks -> {
            if (gen != dirLoadGen) {
                return; // устаревшая загрузка
            }
            int start = fromEnd ? (tracks.size() - 1) : startIndex;
            startQueue(dirPath, tracks, start, autoplay);
        });
    }

    /**
     * Ставит очередь из готового списка треков каталога и запускает её.
     * При необходимости переводит левую панель в этот каталог и просит
     * список прокрутиться к играющему треку.
     */
    private void startQueue(String dirPath, List<TrackEntity> tracks, int startIndex,
                            boolean autoplay) {
        if (tracks == null || tracks.isEmpty()) {
            pendingScrollToTrack = false;
            return;
        }
        currentQueueDir = dirPath;
        currentQueue.clear();
        currentQueue.addAll(tracks);
        pendingScrollToTrack = true;
        String shown = currentDirPath();
        if (dirPath != null && !dirPath.equals(shown)) {
            openDir(segmentsBetween(usbRoot, new File(dirPath)), null);
        }
        int idx = Math.max(0, Math.min(startIndex, currentQueue.size() - 1));
        buildAndPlay(idx, autoplay);
    }

    /**
     * Переход к соседнему подкаталогу: delta = +1 вперёд, -1 назад (по кругу).
     */
    private void playAdjacentDir(int delta, boolean fromEnd) {
        playAdjacentDir(delta, fromEnd, false);
    }

    private void playAdjacentDir(int delta, boolean fromEnd, boolean retried) {
        if (orderedDirs.isEmpty()) {
            if (retried) {
                return;
            }
            // порядок каталогов ещё не построен — построим и повторим попытку
            refreshOrderedDirs(() -> playAdjacentDir(delta, fromEnd, true));
            return;
        }
        int n = orderedDirs.size();
        int idx = currentQueueDir == null ? -1 : orderedDirs.indexOf(currentQueueDir);
        if (idx < 0) {
            idx = delta > 0 ? -1 : 0;
        }
        int target = idx + delta;
        if (target < 0 || target >= n) {
            target = ((target % n) + n) % n; // по кругу через всю библиотеку
        }
        playDir(orderedDirs.get(target), 0, fromEnd, true);
    }

    /**
     * Трек доиграл: выбираем следующий по текущему режиму.
     */
    private void onTrackFinished() {
        if (suppressAutoAdvance) {
            return; // смена накопителя/скан — автопереход не запускаем
        }
        if (shuffleMode) {
            playRandom();
        } else {
            sequentialNext(currentTrack.getValue());
        }
    }

    // ------------------------------------------------------------------
    // Последовательный режим
    // ------------------------------------------------------------------

    /**
     * Следующий трек каталога текущего трека, иначе — следующий каталог.
     */
    private void sequentialNext(TrackEntity from) {
        if (from == null || from.parentPath == null) {
            playAdjacentDir(+1, false);
            return;
        }
        final int gen = ++dirLoadGen;
        cache.tracksInAsync(new File(from.parentPath), tracks -> {
            if (gen != dirLoadGen) {
                return;
            }
            int i = indexOfTrack(tracks, from);
            if (i >= 0 && i + 1 < tracks.size()) {
                startQueue(from.parentPath, tracks, i + 1, true);
            } else {
                playAdjacentDir(+1, false);
            }
        });
    }

    /**
     * Предыдущий трек каталога текущего трека, иначе — предыдущий каталог (с конца).
     */
    private void sequentialPrev(TrackEntity from) {
        if (from == null || from.parentPath == null) {
            playAdjacentDir(-1, true);
            return;
        }
        final int gen = ++dirLoadGen;
        cache.tracksInAsync(new File(from.parentPath), tracks -> {
            if (gen != dirLoadGen) {
                return;
            }
            int i = indexOfTrack(tracks, from);
            if (i > 0) {
                startQueue(from.parentPath, tracks, i - 1, true);
            } else {
                playAdjacentDir(-1, true);
            }
        });
    }

    // ------------------------------------------------------------------
    // Случайный режим
    // ------------------------------------------------------------------

    /**
     * Случайный трек из всей библиотеки.
     */
    private void playRandom() {
        playRandom(false);
    }

    private void playRandom(boolean retried) {
        if (allTracks.isEmpty()) {
            if (retried) {
                return;
            }
            refreshAllTracks(() -> playRandom(true)); // индекс ещё не готов
            return;
        }
        TrackEntity cur = currentTrack.getValue();
        TrackEntity pick = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            TrackEntity cand = allTracks.get(random.nextInt(allTracks.size()));
            // не повторяем текущий трек (если в библиотеке больше одного)
            if (cur == null || allTracks.size() == 1 || !sameTrack(cand, cur)) {
                pick = cand;
                break;
            }
        }
        if (pick == null) {
            pick = allTracks.get(random.nextInt(allTracks.size()));
        }
        pushShuffleHistory(cur);
        startSingle(pick);
    }

    /**
     * Предыдущий трек в случайном режиме — из истории проигранного.
     */
    private void previousInShuffle() {
        if (!shuffleHistory.isEmpty()) {
            startSingle(shuffleHistory.removeLast());
        } else {
            Player p = player();
            if (p != null) {
                p.seekTo(0); // истории нет — просто рестарт
            }
        }
    }

    /**
     * Ставит очередь из одного трека (случайный режим).
     */
    private void startSingle(TrackEntity t) {
        if (usbRoot == null || t == null) {
            return;
        }
        dirLoadGen++; // отменяем возможные фоновые загрузки каталогов
        currentQueueDir = t.parentPath;
        currentQueue.clear();
        currentQueue.add(t);
        pendingScrollToTrack = true;
        if (t.parentPath != null && !t.parentPath.equals(currentDirPath())) {
            openDir(segmentsBetween(usbRoot, new File(t.parentPath)), null);
        }
        buildAndPlay(0, true);
    }

    /**
     * В случайном режиме плейлист содержит один трек: убираем лишние элементы,
     * не прерывая текущее воспроизведение (removeMediaItems позицию не сбрасывает).
     */
    private void shrinkQueueToCurrent() {
        Player p = player();
        if (p == null) {
            return;
        }
        int n = p.getMediaItemCount();
        int i = p.getCurrentMediaItemIndex();
        if (n <= 1 || i < 0 || i >= n) {
            return;
        }
        TrackEntity cur = currentTrack.getValue();
        if (cur != null) {
            currentQueueDir = cur.parentPath;
            currentQueue.clear();
            currentQueue.add(cur);
        }
        if (i + 1 < n) {
            p.removeMediaItems(i + 1, n);
        }
        if (i > 0) {
            p.removeMediaItems(0, i);
        }
    }

    /**
     * Добавляет трек в историю случайного воспроизведения (ограниченную по размеру).
     */
    private void pushShuffleHistory(TrackEntity t) {
        if (t == null) {
            return;
        }
        shuffleHistory.addLast(t);
        while (shuffleHistory.size() > 50) {
            shuffleHistory.removeFirst();
        }
    }

    private static boolean sameTrack(TrackEntity a, TrackEntity b) {
        return a != null && b != null && a.path != null && a.path.equals(b.path)
                && a.cueStartMs == b.cueStartMs;
    }

    private static int indexOfTrack(List<TrackEntity> list, TrackEntity t) {
        if (list == null || t == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (sameTrack(list.get(i), t)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Обновляет кэш всех треков библиотеки (для случайного режима).
     */
    private void refreshAllTracks(Runnable after) {
        if (usbRoot == null) {
            allTracks = new ArrayList<>();
            if (after != null) {
                after.run();
            }
            return;
        }
        cache.loadAllTracksAsync(list -> {
            allTracks = list != null ? list : new ArrayList<>();
            if (after != null) {
                after.run();
            }
        });
    }

    /**
     * Обновляет упорядоченный список каталогов с треками.
     */
    private void refreshOrderedDirs(Runnable after) {
        if (usbRoot == null) {
            orderedDirs = new ArrayList<>();
            if (after != null) {
                after.run();
            }
            return;
        }
        cache.loadOrderedTrackDirsAsync(usbRoot, dirs -> {
            orderedDirs = dirs != null ? dirs : new ArrayList<>();
            if (after != null) {
                after.run();
            }
        });
    }

    /**
     * Абсолютный путь текущего открытого каталога.
     */
    private String currentDirPath() {
        if (usbRoot == null) {
            return null;
        }
        File f = usbRoot;
        for (String s : currentSegments) {
            f = new File(f, s);
        }
        return f.getAbsolutePath();
    }

    /**
     * MediaItem: для CUE-треков — клиппинг на границы INDEX.
     */
    private MediaItem toMediaItem(TrackEntity t) {
        MediaItem.Builder b = new MediaItem.Builder()
                .setUri(Uri.fromFile(new File(t.isCue ? t.cueSourceFile : t.path)));
        if (t.isCue) {
            b.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(t.cueStartMs)
                    // TIME_END_OF_SOURCE, не TIME_UNSET: setEndPositionMs принимает
                    // только TIME_END_OF_SOURCE (Long.MIN_VALUE) или >= 0.
                    .setEndPositionMs(t.cueEndMs > 0 ? t.cueEndMs : C.TIME_END_OF_SOURCE)
                    .build());
        }
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .setTitle(t.displayName())
                .setArtist(t.artist == null ? "" : t.artist);
        if (t.album != null) {
            mb.setAlbumTitle(t.album);
        }
        if (t.coverPath != null) {
            mb.setArtworkUri(Uri.fromFile(new File(t.coverPath)));
        }
        b.setMediaMetadata(mb.build());
        return b.build();
    }

    /**
     * Реальный исходный файл трека (для CUE-трека — физический FLAC).
     */
    private static String sourcePathOf(TrackEntity t) {
        if (t == null) {
            return null;
        }
        return t.isCue ? t.cueSourceFile : t.path;
    }

    /**
     * Файл трека отсутствует на накопителе (устаревший кеш, смена флешки).
     */
    private static boolean missingSourceFile(TrackEntity t) {
        String src = sourcePathOf(t);
        return src == null || !new File(src).exists();
    }

    /**
     * Сообщаем UI о треке с отсутствующим файлом (покажет диалог).
     */
    private void notifyMissingFile(TrackEntity t) {
        if (t == null) {
            return;
        }
        Log.w(TAG, "Файл трека не найден: " + sourcePathOf(t));
        missingTrackError.setValue(t);
    }

    /**
     * Индекс текущего трека: сначала живой ExoPlayer, затем контроллер.
     */
    private int currentItemIndex() {
        ExoPlayer p = MusicService.getPlayerRef();
        if (p != null) {
            return p.getCurrentMediaItemIndex();
        }
        if (controller != null) {
            return controller.getCurrentMediaItemIndex();
        }
        return -1;
    }

    private void updateCurrentTrack() {
        int idx = currentItemIndex();
        if (idx >= 0 && idx < currentQueue.size()) {
            currentTrack.setValue(currentQueue.get(idx));
            // На ГУ питание может пропасть без onPause — фиксируем состояние
            // при каждой смене трека, чтобы восстанавливался именно последний
            // играющий трек, а не устаревший из прошлого onPause.
            savePlaybackStateNow();
            // после перехода в другой каталог список уже перезагружен —
            // просим UI прокрутиться к играющему треку
            if (pendingScrollToTrack) {
                pendingScrollToTrack = false;
                scrollToRestoredTrack.setValue(true);
            }
        }
    }

    /**
     * Обложка для трека (файл каталога или встроенная).
     */
    public void loadCover(TrackEntity t, CoverArtLoader.Callback cb) {
        File dir = t.parentPath != null ? new File(t.parentPath) : null;
        String src = t.isCue && t.cueSourceFile != null ? t.cueSourceFile : t.path;
        covers.load(dir, src, cb);
    }

    // ------------------------------------------------------------------
    // Сохранение позиции (SharedPreferences)
    // ------------------------------------------------------------------

    public void savePlaybackStateNow() {
        SharedPreferences sp = app.getSharedPreferences(PREFS_PLAY, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        TrackEntity cur = currentTrack.getValue();
        if (cur != null) {
            e.putString("path", cur.path);
        }
        // Индекс строки трека в текущем списке — запасной способ восстановления:
        // надёжно выбирает строку даже при совпадении путей (повторные TRACK NN в
        // CUE дают одинаковые path "...#номер") и при смене префикса точки монтирования.
        // Для CUE строка уточняется по началу клипа (cueStartMs) — оно уникально.
        e.putInt("rowIndex", -1);
        e.putLong("cueStart", cur != null ? cur.cueStartMs : 0);
        List<LibraryRow> rowsNow = rows.getValue();
        if (cur != null && rowsNow != null) {
            for (int i = 0; i < rowsNow.size(); i++) {
                LibraryRow r = rowsNow.get(i);
                if (r.kind == LibraryRow.Kind.TRACK
                        && cur.path.equals(r.track.path)
                        && cur.cueStartMs == r.track.cueStartMs) {
                    e.putInt("rowIndex", i);
                    break;
                }
            }
        }
        // Каталог для восстановления: играющий трек — его каталог, иначе
        // последний просматриваемый. Так после старта открывается та же папка.
        String folder = (cur != null && currentQueueDir != null)
                ? currentQueueDir : currentDirPath();
        if (folder != null) {
            e.putString("folder", folder);
        }
        Player p = player();
        e.putLong("pos", p != null ? p.getCurrentPosition() : 0);
        e.putBoolean("playing", p != null && p.isPlaying());
        e.apply();
    }

    /**
     * Восстановление последнего каталога и трека при старте (без автозапуска).
     */
    private void restorePlaybackState() {
        SharedPreferences sp = app.getSharedPreferences(PREFS_PLAY, Context.MODE_PRIVATE);
        final String path = sp.getString("path", null);
        final String folderAbs = sp.getString("folder", null);
        final long pos = sp.getLong("pos", 0);
        if (usbRoot == null || (path == null && folderAbs == null)) {
            return;
        }

        // Каталог старта: последний просматриваемый (folder) или родитель трека
        File dir = null;
        if (folderAbs != null) {
            dir = new File(folderAbs);
        }
        if (path != null
                && (dir == null || !dir.exists() || !isInsideUsb(dir))) {
            // сохранённой папки нет (удалена/флешка перемонтирована) —
            // пробуем родителя последнего трека
            File parent = new File(path).getParentFile();
            if (parent != null && parent.exists()) {
                dir = parent;
            }
        }
        if (dir == null || !dir.exists() || !isInsideUsb(dir)) {
            return; // флешка сменилась/папки нет — остаёмся в корне
        }

        // Открываем каталог и ждём его строк — среди них ищем последний трек
        openDir(segmentsBetween(usbRoot, dir), () -> {
            List<LibraryRow> list = rows.getValue();
            if (list == null || path == null || controller == null) {
                return;
            }
            final int savedRow = sp.getInt("rowIndex", -1);
            final long savedCueStart = sp.getLong("cueStart", 0);
            currentQueue.clear();
            List<Integer> exact = new ArrayList<>();   // путь + cueStart совпали (точная строка)
            List<Integer> byPath = new ArrayList<>();  // совпал только путь
            for (int i = 0; i < list.size(); i++) {
                LibraryRow r = list.get(i);
                if (r.kind == LibraryRow.Kind.TRACK) {
                    currentQueue.add(r.track);
                    if (path.equals(r.track.path)) {
                        byPath.add(i);
                        // Для обычных файлов cueStartMs = 0 и проверка тривиальна;
                        // для CUE начало клипа уникально и различает строки с одним путём.
                        if (r.track.cueStartMs == savedCueStart) {
                            exact.add(i);
                        }
                    }
                }
            }
            // Выбор кандидата:
            //  1) одна строка с путь+cueStart → она (устойчиво к перестановкам и коллизиям)
            //  2) несколько → ближайшая к сохранённой позиции
            //  3) cueStart изменился (файл/разметка пересозданы) → только путь, ближайший к позиции
            //  4) путь не совпал вовсе (сменился префикс монтирования и т.п.) → по позиции строки
            int targetRow = -1;
            if (exact.size() == 1) {
                targetRow = exact.get(0);
            } else if (exact.size() > 1 && savedRow >= 0) {
                targetRow = nearestMatch(exact, savedRow);
            } else if (byPath.size() == 1) {
                targetRow = byPath.get(0);
            } else if (byPath.size() > 1 && savedRow >= 0) {
                targetRow = nearestMatch(byPath, savedRow);
            } else if (savedRow >= 0 && savedRow < list.size()
                    && list.get(savedRow).kind == LibraryRow.Kind.TRACK) {
                targetRow = savedRow;
            }
            if (targetRow >= 0) {
                int start = 0; // индекс трека в очереди = число треков до targetRow
                for (int i = 0; i < targetRow; i++) {
                    if (list.get(i).kind == LibraryRow.Kind.TRACK) {
                        start++;
                    }
                }
                restoreQueue(start, pos); // prepare без play() — старт с паузы
                // трек может быть ниже видимой области списка — прокрутим к нему,
                // когда контроллер сообщит актуальный элемент (в updateCurrentTrack)
                pendingScrollToTrack = true;
                Log.d(TAG, "restore: строка=" + targetRow + " очередь=" + start
                        + " exact=" + exact.size() + " byPath=" + byPath.size()
                        + " savedRow=" + savedRow + " cueStart=" + savedCueStart);
            } else {
                Log.d(TAG, "restore: трек не найден path=" + path + " savedRow=" + savedRow);
            }
        });
    }

    /**
     * Ближайшая к savedRow строка из кандидатов с одинаковым путём.
     */
    private static int nearestMatch(List<Integer> candidates, int savedRow) {
        int best = candidates.get(0);
        int bestDist = Math.abs(best - savedRow);
        for (int i : candidates) {
            int d = Math.abs(i - savedRow);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Восстанавливает очередь и позицию выбранного трека, НЕ запуская воспроизведение.
     */
    private void restoreQueue(int startIndex, long seekPosMs) {
        Player p = player();
        if (p == null || currentQueue.isEmpty()) {
            return;
        }
        currentQueueDir = currentDirPath();
        int idx = Math.max(0, Math.min(startIndex, currentQueue.size() - 1));
        // Восстанавливаемый трек удалён (кеш устарел/флешка сменилась) — показываем диалог.
        TrackEntity start = currentQueue.get(idx);
        if (missingSourceFile(start)) {
            notifyMissingFile(start);
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (TrackEntity t : currentQueue) {
            items.add(toMediaItem(t));
        }
        p.setMediaItems(items, idx, 0);
        p.prepare(); // без play(): трек загружен, но играть не начинаем
        if (seekPosMs > 0) {
            final long seekPos = seekPosMs;
            handler.postDelayed(() -> {
                Player pp = player();
                if (pp != null) {
                    try {
                        pp.seekTo(seekPos);
                    } catch (Exception ignored) {
                    }
                }
            }, 700);
        }
    }

    // ------------------------------------------------------------------
    // Геттеры
    // ------------------------------------------------------------------

    public LiveData<Boolean> getUsbAvailable() {
        return usbAvailable;
    }

    public LiveData<Boolean> getScanning() {
        return scanning;
    }

    public LiveData<Boolean> getSplashVisible() {
        return splashVisible;
    }

    public LiveData<String> getScanProgress() {
        return scanProgress;
    }

    public LiveData<List<BreadcrumbLevel>> getBreadcrumbs() {
        return breadcrumbs;
    }

    public LiveData<List<LibraryRow>> getRows() {
        return rows;
    }

    public LiveData<TrackEntity> getCurrentTrack() {
        return currentTrack;
    }

    public LiveData<Boolean> getScrollToRestoredTrack() {
        return scrollToRestoredTrack;
    }

    /**
     * Сброс однократного события прокрутки к восстановленному треку.
     */
    public void consumeScrollToRestoredTrack() {
        scrollToRestoredTrack.setValue(false);
    }

    public LiveData<List<UsbDevice>> getUsbDevicesPicker() {
        return usbDevicesPicker;
    }

    /**
     * Сброс однократного события выбора накопителя (диалог показан).
     */
    public void consumeUsbDevicesPicker() {
        usbDevicesPicker.setValue(null);
    }

    /**
     * Однократное событие: файл трека не найден (старый кеш / смена накопителя).
     */
    public LiveData<TrackEntity> getMissingTrackError() {
        return missingTrackError;
    }

    /**
     * Сброс однократного события «файл не найден» (диалог показан).
     */
    public void consumeMissingTrackError() {
        missingTrackError.setValue(null);
    }

    /**
     * Однократное событие: сообщение об ошибке воспроизведения.
     */
    public LiveData<String> getPlaybackError() {
        return playbackError;
    }

    /**
     * Сброс однократного события «ошибка воспроизведения» (диалог показан).
     */
    public void consumePlaybackError() {
        playbackError.setValue(null);
    }

    public LiveData<Boolean> getIsPlaying() {
        return isPlaying;
    }

    public LiveData<Long> getPosition() {
        return position;
    }

    public LiveData<Long> getDuration() {
        return duration;
    }

    public LiveData<Boolean> getShuffle() {
        return shuffle;
    }

    public LiveData<String> getInfoMessage() {
        return infoMessage;
    }

    public void clearInfoMessage() {
        infoMessage.setValue(null);
    }

    public EqualizerManager getEqualizer() {
        return eq;
    }

    public LoudnessManager getLoudness() {
        return loudness;
    }

    @Override
    protected void onCleared() {
        handler.removeCallbacks(ticker);
        savePlaybackStateNow();
        super.onCleared();
    }
}
