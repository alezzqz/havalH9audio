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

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tgraf.havalh9music.databinding.ActivityMainBinding;
import com.tgraf.havalh9music.databinding.DialogEqualizerBinding;
import com.tgraf.havalh9music.databinding.DialogSettingsBinding;
import com.tgraf.havalh9music.databinding.DialogSavedVolumesBinding;
import com.tgraf.havalh9music.databinding.ItemEqualizerBandBinding;
import com.tgraf.havalh9music.model.LibraryRow;
import com.tgraf.havalh9music.model.SavedVolume;
import com.tgraf.havalh9music.model.TrackEntity;
import com.tgraf.havalh9music.model.UsbDevice;
import com.tgraf.havalh9music.ui.BreadcrumbAdapter;
import com.tgraf.havalh9music.ui.GenreFilterDialog;
import com.tgraf.havalh9music.ui.ListDividerDecoration;
import com.tgraf.havalh9music.ui.PlayerViewModel;
import com.tgraf.havalh9music.ui.TrackAdapter;
import com.tgraf.havalh9music.util.AppearanceSettings;
import com.tgraf.havalh9music.util.FlacDecoderSettings;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Главный экран: левая панель (навигация + список), правая панель («Сейчас играет»).
 * Запрос разрешений API 28, жесты (свайп влево/вверх), крошки, EQ-диалог.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final long EQ_PRESET_SAVE_HOLD_MS = 2000L;

    private ActivityMainBinding b;
    private PlayerViewModel vm;
    private TrackAdapter trackAdapter;
    private BreadcrumbAdapter breadcrumbAdapter;
    private boolean userScrubbing = false;
    private AlertDialog scanDialog;
    // true — пользователь ушёл выдавать разрешение (в настройки/системный экран):
    // при возврате перепроверяем и при успехе инициализируем библиотеку.
    private boolean awaitingPermission = false;
    private List<String> availableDecoders = new ArrayList<>();
    private DialogSettingsBinding settingsBinding;
    private int decoderQueryGeneration;

    private void discoverDecoders() {
        final int generation = ++decoderQueryGeneration;
        new Thread(() -> {
            List<String> found = FlacDecoderSettings.discover();
            runOnUiThread(() -> {
                if (generation != decoderQueryGeneration || isFinishing() || isDestroyed()) {
                    return;
                }
                availableDecoders = found;
                // После обновления прошивки сохранённый системный кодек может исчезнуть.
                if (!found.isEmpty() && !found.contains(FlacDecoderSettings.selected(this))) {
                    FlacDecoderSettings.select(this, found.get(0));
                }
                if (settingsBinding != null) {
                    populateDecoderSpinner(settingsBinding);
                }
            });
        }, "flac-decoder-discovery").start();
    }

    private ArrayAdapter<String> settingsAdapter(List<String> labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.settings_spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        return adapter;
    }

    private void populateDecoderSpinner(DialogSettingsBinding settings) {
        boolean ready = !availableDecoders.isEmpty();
        settings.decoder.setAdapter(settingsAdapter(ready ? availableDecoders
                :
                java.util.Collections.singletonList(getString(R.string.settings_decoder_loading))));
        settings.decoder.setEnabled(ready);
        int index = availableDecoders.indexOf(FlacDecoderSettings.selected(this));
        if (index >= 0) {
            settings.decoder.setSelection(index);
        }
    }

    private void applyPanelOrder() {
        ConstraintSet constraints = new ConstraintSet();
        constraints.clone(b.getRoot());
        int first = AppearanceSettings.swapped(this) ? R.id.rightPanel : R.id.leftPanel;
        int second = first == R.id.leftPanel ? R.id.rightPanel : R.id.leftPanel;
        for (int id : new int[]{first, second, R.id.panelDivider}) {
            constraints.clear(id, ConstraintSet.START);
            constraints.clear(id, ConstraintSet.END);
        }
        constraints.connect(first, ConstraintSet.START, ConstraintSet.PARENT_ID,
                ConstraintSet.START);
        constraints.connect(first, ConstraintSet.END, R.id.panelDivider, ConstraintSet.START);
        constraints.connect(R.id.panelDivider, ConstraintSet.START, first, ConstraintSet.END);
        constraints.connect(R.id.panelDivider, ConstraintSet.END, second, ConstraintSet.START);
        constraints.connect(second, ConstraintSet.START, R.id.panelDivider, ConstraintSet.END);
        constraints.connect(second, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
        constraints.applyTo(b.getRoot());
    }

    private void showSettingsDialog() {
        if (settingsBinding != null) {
            return;
        }
        DialogSettingsBinding settings = DialogSettingsBinding.inflate(getLayoutInflater());
        settingsBinding = settings;
        populateDecoderSpinner(settings);
        settings.panels.setAdapter(settingsAdapter(java.util.Arrays.asList(
                getString(R.string.settings_files_left),
                getString(R.string.settings_files_right))));
        settings.panels.setSelection(AppearanceSettings.swapped(this) ? 1 : 0);
        settings.palette.setAdapter(settingsAdapter(java.util.Arrays.asList(
                getString(R.string.settings_teal), getString(R.string.settings_warm))));
        settings.palette.setSelection(AppearanceSettings.warm(this) ? 1 : 0);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this,
                R.style.ThemeOverlay_HavalH9Music_EqDialog).setView(settings.getRoot())
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    AppCompatResources.getDrawable(this, R.drawable.bg_eq_window));
        }
        Runnable updateActions = () -> {
            boolean enabled = vm.isSoundReady()
                    && !Boolean.TRUE.equals(vm.getDataBusy().getValue());
            settings.refresh.setEnabled(enabled);
            settings.savedVolumes.setEnabled(enabled);
            settings.clearVolumeSounds.setEnabled(enabled);
            settings.clearFolderSounds.setEnabled(enabled);
        };
        Observer<Boolean> busyObserver = ignored -> updateActions.run();
        Observer<Integer> soundObserver = ignored -> updateActions.run();
        vm.getDataBusy().observe(this, busyObserver);
        vm.getSoundRevision().observe(this, soundObserver);
        dialog.setOnDismissListener(ignored -> {
            settingsBinding = null;
            vm.getDataBusy().removeObserver(busyObserver);
            vm.getSoundRevision().removeObserver(soundObserver);
        });
        settings.savedVolumes.setOnClickListener(v -> showSavedVolumesDialog(false));
        settings.clearVolumeSounds.setOnClickListener(v -> showSavedVolumesDialog(true));
        settings.clearFolderSounds.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HavalH9Music_EqDialog)
                        .setTitle(R.string.sound_clear_all)
                        .setMessage(R.string.sound_clear_confirm)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_delete,
                                (confirmation, which) -> vm.clearAllFolderSounds())
                        .show());
        settings.cancel.setOnClickListener(v -> dialog.dismiss());
        settings.refresh.setOnClickListener(v -> {
            dialog.dismiss();
            if (hasReadPermission()) {
                vm.refreshLibrary();
            } else {
                showPermissionDeniedDialog();
            }
        });
        settings.apply.setOnClickListener(v -> {
            boolean warm = settings.palette.getSelectedItemPosition() == 1;
            boolean recreateTheme = warm != AppearanceSettings.warm(this);
            AppearanceSettings.save(this, warm, settings.panels.getSelectedItemPosition() == 1);
            if (settings.decoder.isEnabled()) {
                String decoder = (String) settings.decoder.getSelectedItem();
                if (!decoder.equals(FlacDecoderSettings.selected(this))) {
                    FlacDecoderSettings.select(this, decoder);
                    // Через живой ExoPlayer: MediaController на ГУ теряет команды.
                    androidx.media3.exoplayer.ExoPlayer live = MusicService.getPlayerRef();
                    if (live != null && live.getMediaItemCount() > 0) {
                        boolean ready = live.getPlayWhenReady();
                        int index = live.getCurrentMediaItemIndex();
                        long position = live.getCurrentPosition();
                        live.stop();
                        live.seekTo(index, position);
                        live.prepare();
                        live.setPlayWhenReady(ready);
                    }
                }
            }
            dialog.dismiss();
            if (recreateTheme) {
                recreate();
            } else {
                applyPanelOrder();
            }
        });
        dialog.show();
    }

    private void updateGenreButton() {
        PlayerViewModel.GenreState state = vm.getGenreState().getValue();
        boolean filtered = state != null && state.selected != null;
        b.btnGenres.setImageTintList(android.content.res.ColorStateList.valueOf(
                AppearanceSettings.color(this,
                filtered && Boolean.TRUE.equals(vm.getShuffle().getValue())
                        ? R.attr.paletteAccent : R.attr.paletteIconInactive)));
        b.btnGenres.setEnabled(state != null && !Boolean.TRUE.equals(vm.getScanning().getValue())
                && !Boolean.TRUE.equals(vm.getDataBusy().getValue()));
    }

    private void showSavedVolumesDialog(boolean soundOnly) {
        DialogSavedVolumesBinding saved = DialogSavedVolumesBinding.inflate(getLayoutInflater());
        saved.title.setText(soundOnly ? R.string.sound_volume_picker_title
                : R.string.saved_volumes_title);
        final int pickerHint = soundOnly ? R.string.sound_volume_picker_hint
                : R.string.saved_volumes_hint;
        List<SavedVolume> volumes = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_saved_volume,
                new ArrayList<>());
        saved.volumes.setAdapter(adapter);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this,
                R.style.ThemeOverlay_HavalH9Music_EqDialog).setView(saved.getRoot())
                .setNegativeButton(R.string.action_close, null).create();
        Observer<List<SavedVolume>> volumeObserver = list -> {
            volumes.clear();
            adapter.clear();
            if (list == null) {
                saved.status.setText(R.string.saved_volumes_loading);
                saved.volumes.setEnabled(false);
                return;
            }
            volumes.addAll(list);
            for (SavedVolume volume : list) {
                String identity = volume.id;
                if (identity.startsWith("path:")) {
                    identity += "\n" + getString(R.string.volume_path_identity);
                }
                adapter.add(getString(R.string.saved_volume_row, volume.label,
                        getString(volume.connected ? R.string.volume_connected
                                : R.string.volume_disconnected), identity));
            }
            saved.status.setText(list.isEmpty() ? R.string.saved_volumes_empty
                    : pickerHint);
            saved.volumes.setEnabled(!Boolean.TRUE.equals(vm.getDataBusy().getValue()));
        };
        Observer<Boolean> busyObserver = busy -> {
            boolean working = Boolean.TRUE.equals(busy);
            saved.volumes.setEnabled(!working && vm.getSavedVolumes().getValue() != null);
            if (working) {
                saved.status.setText(soundOnly ? R.string.sound_profiles_deleting
                        : R.string.volume_deleting);
            } else if (vm.getSavedVolumes().getValue() != null) {
                saved.status.setText(volumes.isEmpty() ? R.string.saved_volumes_empty
                        : pickerHint);
            }
        };
        saved.volumes.setOnItemClickListener((parent, view, position, id) -> {
            if (Boolean.TRUE.equals(vm.getDataBusy().getValue()) || position >= volumes.size()) {
                return;
            }
            SavedVolume volume = volumes.get(position);
            new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_HavalH9Music_EqDialog)
                    .setTitle(soundOnly ? R.string.sound_volume_delete_title
                            : R.string.volume_delete_title)
                    .setMessage(soundOnly ? getString(R.string.sound_volume_delete_confirm,
                            volume.label) : getString(R.string.volume_delete_confirm,
                            volume.label, volume.id))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete,
                            (confirmation, which) -> {
                                if (soundOnly) {
                                    vm.clearFolderSounds(volume);
                                } else {
                                    vm.forgetVolume(volume);
                                }
                            })
                    .show();
        });
        vm.getSavedVolumes().observe(this, volumeObserver);
        vm.getDataBusy().observe(this, busyObserver);
        dialog.setOnDismissListener(ignored -> {
            vm.getSavedVolumes().removeObserver(volumeObserver);
            vm.getDataBusy().removeObserver(busyObserver);
        });
        dialog.show();
        vm.loadSavedVolumes();
    }

    // Runtime-разрешения: Android 13+ — READ_MEDIA_AUDIO + POST_NOTIFICATIONS,
    // Android 9–12 (в т.ч. ГУ) — классический READ_EXTERNAL_STORAGE.
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                        if (hasReadPermission()) {
                            maybeInitLibrary();
                        } else {
                            // Отказ не маскируем под «Подключите USB-накопитель»:
                            // без доступа к файлам список источников в принципе пуст.
                            showPermissionDeniedDialog();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(AppearanceSettings.warm(this)
                ? R.style.Theme_HavalH9Music_Warm : R.style.Theme_HavalH9Music);
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        applyPanelOrder();
        discoverDecoders();

        // Страховка под левую навигационную панель ГУ: сдвигаем контент вправо
        // на ширину системного левого inset и/или ручного отступа system_rail_inset.
        // На штатном 1920×1080 без панели inset = 0 — отступ не применяется,
        // дизайн остаётся прежним. Двойного сдвига нет: если левый бар уже учтён
        // декорацией, слушатель получит 0 и добавит только ручное значение.
        ViewCompat.setOnApplyWindowInsetsListener(b.getRoot(), (v, insets) -> {
            // getInsets(Type.systemBars()) — современная замена устаревшего
            // getSystemWindowInsetLeft(); на API 28 поведение то же.
            int systemLeft = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left;
            int rail = (int) getResources().getDimension(R.dimen.system_rail_inset);
            int padLeft = Math.max(systemLeft, rail);
            if (padLeft != v.getPaddingLeft()) {
                v.setPadding(padLeft, v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom());
            }
            return insets;
        });

        vm = new ViewModelProvider(this).get(PlayerViewModel.class);

        // Начальный текст на заставке
        b.splashStatus.setText(R.string.splash_loading);

        setupTrackList();
        setupBreadcrumbs();
        setupGestures();
        setupTransport();
        setupSeekBar();
        observeViewModel();

        vm.connect();
        requestPermissionsIfNeeded();
        registerUsbReceiver();
    }

    // ------------------------------------------------------------------
    // Инициализация списка и крошек
    // ------------------------------------------------------------------

    private void setupTrackList() {
        trackAdapter = new TrackAdapter(new TrackAdapter.Listener() {
            @Override
            public void onRowClick(LibraryRow row, int position) {
                vm.playRow(position);
            }

            @Override
            public void onMoreClick(LibraryRow row) {
                if (row.kind == LibraryRow.Kind.TRACK) {
                    showTrackInfo(row.track);
                } else {
                    // у каталогов меню скрыто (GONE), ветка на всякий случай
                    vm.openFolder(row.folderPath);
                }
            }

        });
        b.trackList.setLayoutManager(new LinearLayoutManager(this));
        b.trackList.addItemDecoration(new ListDividerDecoration(this));
        // Анимации строк выключены: после замены notifyDataSetChanged на точечные
        // события DefaultItemAnimator играет insert/remove/change-анимации, что на
        // слабом процессоре ГУ заметно замедляло перерисовку больших каталогов.
        b.trackList.setItemAnimator(null);
        b.trackList.setAdapter(trackAdapter);
    }

    private void setupBreadcrumbs() {
        breadcrumbAdapter = new BreadcrumbAdapter(getLayoutInflater(),
                b.breadcrumbContainer, level -> vm.navigateTo(level.depth));
    }

    // ------------------------------------------------------------------
    // Жест: свайп вниз с удержанием (список в самом верху) → родительский
    // каталог
    // ------------------------------------------------------------------

    /**
     * Минимальный путь пальца вниз для жеста «на уровень вверх», px.
     */
    private static final float GESTURE_MIN_DY = 120f;
    /**
     * Минимальное время удержания свайпа вниз, мс (защита от случайной прокрутки).
     */
    private static final long GESTURE_HOLD_MS = 1000L;

    /**
     * Y пальца в момент DOWN (в координатах текущего View).
     */
    private float gestureStartY;
    /**
     * eventTime момента DOWN; 0 — жест не начат.
     */
    private long gestureStartTime;

    /**
     * Жест «свайп вниз с удержанием» — переход на уровень вверх (в корне
     * ничего). Резкий флинг убрали: быстрая прокрутка списка к началу
     * иногда случайно уводила на уровень вверх. Теперь нужен осознанный
     * жест: прижать палец, провести вниз и подержать ~1 секунду.
     * Срабатывает только когда список уже в самом верху.
     * Возвращает true, если ACTION_UP надо забрать себе (навигация выполнена).
     */
    private boolean onNavSwipe(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartY = e.getY();
                gestureStartTime = e.getEventTime();
                return false;
            case MotionEvent.ACTION_UP: {
                boolean handled = false;
                if (gestureStartTime != 0) {
                    float dy = e.getY() - gestureStartY;
                    long hold = e.getEventTime() - gestureStartTime;
                    if (dy > GESTURE_MIN_DY && hold >= GESTURE_HOLD_MS
                            && !b.trackList.canScrollVertically(-1)
                            && vm.navigateUp()) {
                        b.trackList.scrollToPosition(0);
                        handled = true;
                    }
                    gestureStartTime = 0;
                }
                return handled;
            }
            case MotionEvent.ACTION_CANCEL:
                gestureStartTime = 0;
                return false;
            default:
                return false;
        }
    }

    // Инспекция ClickableViewAccessibility здесь неприменима: слушатель
    // реализует жест «свайп вниз с удержанием», а не клик — тапы он не
    // обрабатывает (возвращает false, клики штатно доходят до дочерних кнопок),
    // а navBar не кликабелен, поэтому performClick не нужен и лишь слал бы
    // ложные события доступности на каждый тап.
    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        // Обработка жеста на всей левой панели, не мешая скроллу RecyclerView.
        View.OnTouchListener touch = (v, e) -> onNavSwipe(e);
        b.navBar.setOnTouchListener(touch);

        // trackList: стандартный setOnTouchListener сюда НЕ подходит —
        // кликабельные строки списка съедают первое событие DOWN, а без
        // него не отследить начало жеста (раньше жест «работал» лишь если
        // начать свайп в пустой области). OnItemTouchListener видит все
        // события до того, как их заберут строки.
        b.trackList.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                return onNavSwipe(e);
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            }
        });
    }

    // ------------------------------------------------------------------
    // Транспорт и прогресс
    // ------------------------------------------------------------------

    private void setupTransport() {
        b.homeBtn.setOnClickListener(v -> vm.navigateRoot());
        b.settingsBtn.setOnClickListener(v -> showSettingsDialog());
        b.btnPrev.setOnClickListener(v -> vm.previous());
        b.btnPlay.setOnClickListener(v -> vm.playPause());
        b.btnNext.setOnClickListener(v -> vm.next());
        b.btnShuffle.setOnClickListener(v -> vm.setShuffleMode(true));
        b.btnGenres.setOnClickListener(v -> GenreFilterDialog.show(this, vm));
        b.btnSequential.setOnClickListener(v -> vm.setShuffleMode(false));
        b.btnPlaylist.setOnClickListener(v -> vm.jumpToCurrentTrack());
        b.btnEq.setOnClickListener(v -> showEqualizerDialog());
    }

    // ------------------------------------------------------------------
    // Кнопки на руле (медиа-кнопки). Пока приложение в фокусе — жёсткие
    // KEYCODE_MEDIA_* приходят сюда; когда приложение свёрнуто, их обрабатывает
    // MediaSession в MusicService через MediaButtonReceiver.
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleSteeringKey(event.getKeyCode());
                }
                // Потребляем и DOWN, и UP — чтобы событие не ушло дальше
                // в системную обработку медиа-кнопок (иначе будет двойной ход).
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleSteeringKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                vm.next();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                vm.previous();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                vm.playPause();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                vm.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                vm.pause();
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                vm.stop();
                break;
        }
    }

    private void setupSeekBar() {
        b.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    b.timeCurrent.setText(LibraryRow.formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userScrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userScrubbing = false;
                vm.seekTo(seekBar.getProgress());
            }
        });
    }

    private void scrollToCurrentTrack() {
        TrackEntity cur = vm.getCurrentTrack().getValue();
        List<LibraryRow> list = vm.getRows().getValue();
        if (cur == null || list == null) {
            b.trackList.smoothScrollToPosition(0);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            LibraryRow r = list.get(i);
            if (r.kind == LibraryRow.Kind.TRACK && cur.path.equals(r.track.path)) {
                b.trackList.smoothScrollToPosition(i);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Наблюдение за ViewModel
    // ------------------------------------------------------------------

    /**
     * Заглушка «нет USB»: только когда накопителя нет И список пуст.
     */
    private void updateNoUsbStub() {
        boolean noUsb = Boolean.FALSE.equals(vm.getUsbAvailable().getValue());
        List<LibraryRow> rows = vm.getRows().getValue();
        boolean empty = rows == null || rows.isEmpty();
        b.stubNoUsb.setVisibility(noUsb && empty ? View.VISIBLE : View.GONE);
    }

    private void observeViewModel() {
        // Заглушку «нет USB» показываем только если накопитель недоступен И список
        // пуст: ложное событие отключения не должно перекрывать рабочий список.
        vm.getUsbAvailable().observe(this, ok -> updateNoUsbStub());
        vm.getRows().observe(this, rows -> {
            trackAdapter.setItems(rows);
            updateNoUsbStub();
        });
        // Прокрутка списка к играющему треку (после смены каталога/восстановления)
        vm.getScrollToRestoredTrack().observe(this, flag -> {
            if (Boolean.TRUE.equals(flag)) {
                vm.consumeScrollToRestoredTrack();
                scrollToCurrentTrack();
            }
        });
        // Диалог выбора USB-накопителя (перечитывание библиотеки)
        vm.getUsbDevicesPicker().observe(this, devices -> {
            if (devices == null || devices.isEmpty()) {
                return;
            }
            vm.consumeUsbDevicesPicker();
            showUsbPickerDialog(devices);
        });
        vm.getBreadcrumbs().observe(this, levels -> {
            breadcrumbAdapter.bind(levels);
            scrollBreadcrumbsToEnd();
        });
        vm.getScanning().observe(this, scanning -> {
            // Во время заставки диалог сканирования не нужен —
            // прогресс отображается прямо на splash-экране.
            boolean splashUp = b.splashOverlay.getVisibility() == View.VISIBLE;
            if (scanning && !splashUp) {
                showScanDialog();
            } else if (!scanning) {
                dismissScanDialog();
            }
        });
        vm.getScanProgress().observe(this, text -> {
            if (scanDialog != null && scanDialog.isShowing()) {
                // обновляем текст прогресса
                scanDialog.setMessage(text);
            }
            // дублируем статус на заставку (если она видна)
            if (b.splashOverlay.getVisibility() == View.VISIBLE) {
                if (text != null && !text.isEmpty()) {
                    b.splashStatus.setText(text);
                }
            }
        });
        // Заставка: плавно скрываем, когда инициализация завершена
        vm.getSplashVisible().observe(this, visible -> {
            if (!visible && b.splashOverlay.getVisibility() == View.VISIBLE) {
                hideSplash();
            }
        });
        vm.getIsPlaying().observe(this, playing ->
                b.btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play));
        vm.getPosition().observe(this, pos -> {
            if (!userScrubbing) {
                b.seekBar.setProgress(pos.intValue());
                b.timeCurrent.setText(LibraryRow.formatTime(pos));
            }
        });
        vm.getDuration().observe(this, dur -> {
            b.seekBar.setMax(Math.max(0, dur.intValue()));
            b.timeTotal.setText(LibraryRow.formatTime(dur));
        });
        vm.getCurrentTrack().observe(this, this::onCurrentTrack);
        // Подсветка кнопок режима: активна ровно одна (случайный / последовательный)
        vm.getGenreState().observe(this, state -> updateGenreButton());
        vm.getScanning().observe(this, scanning -> updateGenreButton());
        vm.getDataBusy().observe(this, busy -> updateGenreButton());
        vm.getShuffle().observe(this, sh -> {
            updateGenreButton();
            boolean shuffle = Boolean.TRUE.equals(sh);
            b.btnShuffle.setImageTintList(android.content.res.ColorStateList.valueOf(
                    AppearanceSettings.color(this,
                            shuffle ? R.attr.paletteAccent : R.attr.paletteIconInactive)));
            b.btnSequential.setImageTintList(android.content.res.ColorStateList.valueOf(
                    AppearanceSettings.color(this,
                            shuffle ? R.attr.paletteIconInactive : R.attr.paletteAccent)));
        });
        vm.getInfoMessage().observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                vm.clearInfoMessage();
            }
        });
        // Диалог: файл трека не найден (устаревший кеш / смена накопителя)
        vm.getMissingTrackError().observe(this, track -> {
            if (track == null) {
                return;
            }
            vm.consumeMissingTrackError();
            showMissingFileDialog(track);
        });
        // Диалог: ошибка воспроизведения (диагностика — почему трек не играет)
        vm.getPlaybackError().observe(this, message -> {
            if (message == null || message.isEmpty()) {
                return;
            }
            vm.consumePlaybackError();
            showPlaybackErrorDialog(message);
        });
    }

    private void onCurrentTrack(TrackEntity t) {
        if (t == null) {
            b.trackTitle.setText("");
            b.trackArtist.setText("");
            b.trackAlbum.setText("");
            b.formatBadge.setText("");
            b.coverImage.setImageResource(R.drawable.default_cover);
            return;
        }
        trackAdapter.setActiveTrack(t.path);
        b.trackTitle.setText(t.displayName());
        b.trackArtist.setText(t.artist == null ? "" : t.artist);
        b.trackAlbum.setText(t.album == null ? "" : t.album);
        b.formatBadge.setText(t.formatLabel());
        vm.loadCover(t, bitmap -> {
            if (bitmap != null) {
                b.coverImage.setImageBitmap(bitmap);
            } else {
                b.coverImage.setImageResource(R.drawable.default_cover);
            }
        });
    }

    private void scrollBreadcrumbsToEnd() {
        b.breadcrumbContainer.post(() -> b.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    // ------------------------------------------------------------------
    // Разрешения API 33
    // ------------------------------------------------------------------

    /**
     * Выдан ли доступ к аудиофайлам (без него накопитель не читается).
     */
    private boolean hasReadPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Разрешение не выдано. Показываем явное объяснение вместо пустого состояния
     * «Подключите USB-накопитель» и предлагаем выдать доступ или повторить запрос.
     */
    private void showPermissionDeniedDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_needed_title)
                .setMessage(R.string.permission_needed_msg)
                .setPositiveButton(R.string.permission_open_settings, (d, w) -> {
                    d.dismiss();
                    awaitingPermission = true;
                    openAllFilesAccessSettings();
                })
                .setNegativeButton(R.string.action_retry, (d, w) -> {
                    d.dismiss();
                    requestPermissionsIfNeeded();
                })
                .setCancelable(true)
                .show();
    }

    private void requestPermissionsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: узкие разрешения на аудио и уведомления
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            // Android 9–12 (в т.ч. ГУ): READ_MEDIA_AUDIO не существует,
            // читаем внешнее хранилище и USB по классическому разрешению
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!need.isEmpty()) {
            permissionLauncher.launch(need.toArray(new String[0]));
        } else {
            maybeInitLibrary();
        }
    }

    /**
     * Проверка MANAGE_EXTERNAL_STORAGE → диалог с переходом в настройки.
     */
    private void maybeInitLibrary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            showManageStorageDialog();
        } else {
            vm.startLibraryInit();
        }
    }

    private void showManageStorageDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_manage_title)
                .setMessage(R.string.permission_manage_msg)
                .setPositiveButton(R.string.permission_open_settings, (d, w) -> {
                    d.dismiss();
                    openAllFilesAccessSettings();
                })
                .setNegativeButton(R.string.permission_later, (d, w) -> {
                    d.dismiss();
                    // возможно, точки монтирования читаются и без All Files Access
                    vm.startLibraryInit();
                })
                .setCancelable(true)
                .show();
    }

    /**
     * Открывает системные настройки «Доступ ко всем файлам».
     * Цепочка fallback: точечная страница → общий список → карточка приложения →
     * текстовую подсказку. Каждый шаг пишется в logcat (tag: MainActivity),
     * чтобы на гарнитуре было видно, почему ничего не открылось.
     */
    private void openAllFilesAccessSettings() {
        // 1) Точечная страница «Доступ ко всем файлам» для нашего пакета (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                Log.i(TAG, "opened MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                return;
            } catch (Exception e) {
                Log.w(TAG, "MANAGE_APP_ALL_FILES_ACCESS_PERMISSION failed", e);
            }
        }
        // 2) Общая страница «Все файлы» (список приложений, экран есть с API 30)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                Log.i(TAG, "opened MANAGE_ALL_FILES_ACCESS_PERMISSION");
                return;
            } catch (Exception e) {
                Log.w(TAG, "MANAGE_ALL_FILES_ACCESS_PERMISSION failed", e);
            }
        }
        // 3) Карточка нашего приложения в настройках — есть на любой прошивке
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            Log.i(TAG, "opened APPLICATION_DETAILS_SETTINGS");
            return;
        } catch (Exception e) {
            Log.w(TAG, "APPLICATION_DETAILS_SETTINGS failed", e);
        }
        // 4) Ни один системный экран не открылся — подсказываем вручную
        Toast.makeText(this,
                "Откройте «Настройки → Приложения → Haval H9 Audio → Доступ ко всем файлам»",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Возврат из настроек разрешений: если доступ выдали — стартуем библиотеку,
        // если нет — снова объясняем причину (отказ не маскируем).
        if (awaitingPermission) {
            awaitingPermission = false;
            if (hasReadPermission()) {
                maybeInitLibrary();
            } else {
                showPermissionDeniedDialog();
            }
            return;
        }
        // возврат из настроек «Доступ ко всем файлам»
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Environment.isExternalStorageManager()) {
            vm.startLibraryInit();
        }
    }

    // ------------------------------------------------------------------
    // Заставка (splash)
    // ------------------------------------------------------------------

    private void hideSplash() {
        b.splashOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    b.splashOverlay.setVisibility(View.GONE);
                    b.splashOverlay.setAlpha(1f);
                })
                .start();
    }

    // ------------------------------------------------------------------
    // Диалог сканирования и EQ
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Выбор USB-накопителя
    // ------------------------------------------------------------------

    private void showUsbPickerDialog(List<UsbDevice> devices) {
        if (devices == null || devices.isEmpty()) {
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            names[i] = devices.get(i).label;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.usb_picker_title)
                .setItems(names, (d, which) -> {
                    d.dismiss();
                    vm.selectUsbDevice(devices.get(which));
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    d.dismiss();
                    vm.cancelUsbPick();
                })
                .setOnCancelListener(d -> vm.cancelUsbPick())
                .setCancelable(true)
                .show();
    }

    private void showScanDialog() {
        if (scanDialog != null && scanDialog.isShowing()) {
            return;
        }
        scanDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scanning_title)
                .setMessage("")
                .setCancelable(false)
                .show();
    }

    private void dismissScanDialog() {
        if (scanDialog != null && scanDialog.isShowing()) {
            scanDialog.dismiss();
        }
        scanDialog = null;
    }

    private void showEqualizerDialog() {
        DialogEqualizerBinding eq = DialogEqualizerBinding.inflate(getLayoutInflater());

        final String[] labels = {"60", "175", "400", "1k", "3.5k", "12k"};
        ItemEqualizerBandBinding[] bands = {
                eq.band1, eq.band2, eq.band3, eq.band4, eq.band5, eq.band6};

        for (int i = 0; i < bands.length; i++) {
            final int idx = i;
            ItemEqualizerBandBinding bb = bands[i];
            bb.bandLabel.setText(labels[i]);
            bb.bandSeek.setMax(30); // −15…+15
            float g = vm.getEqualizer().getBand(i);
            bb.bandSeek.setProgress(Math.round(g) + 15);
            bb.bandValue.setText(String.format(Locale.US, "%+.0f dB", g));
            bb.bandSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private String soundContext;

                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    if (soundContext != null
                            && !soundContext.equals(vm.getSoundContextToken())) {
                        return;
                    }
                    float db = progress - 15f;
                    bb.bandValue.setText(String.format(Locale.US, "%+.0f dB", db));
                    vm.setSoundBand(idx, db);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    soundContext = vm.getSoundContextToken();
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    soundContext = null;
                }
            });
        }

        // Колонка усиления: 0..+12 дБ, 0 = выключено (эффекта в тракте нет).
        ItemEqualizerBandBinding gain = eq.band7;
        gain.bandLabel.setText(R.string.gain_label);
        gain.bandSeek.setMax(12);
        gain.bandSeek.setProgress(Math.round(vm.getLoudness().getGainDb()));
        updateGainValue(gain);
        gain.bandSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private String soundContext;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGainValue(gain);
                if (!fromUser) {
                    return;
                }
                if (soundContext != null && !soundContext.equals(vm.getSoundContextToken())) {
                    return;
                }
                vm.setSoundGain(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                soundContext = vm.getSoundContextToken();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                soundContext = null;
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this,
                R.style.ThemeOverlay_HavalH9Music_EqDialog)
                .setView(eq.getRoot())
                .setCancelable(true)
                .create();

        // Заменяем фирменный фон окна Material (с бирюзовой тонировкой) на
        // собственный фон с окантовкой: bg_eq_window рисует панель с контуром
        // 1dp btn_border, а bg_eq_content на контенте закрывает её изнутри.
        // getWindow() помечен @Nullable — на всякий случай проверяем (без окна
        // диалог всё равно не показать, но NPE недопустим).
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(
                    AppCompatResources.getDrawable(this, R.drawable.bg_eq_window));
        }

        eq.btnReset.setOnClickListener(v -> {
            vm.resetSoundBands();
            syncEqFromManager(bands);
        });
        // Чипы пресетов не имеют состояния «активен»: клик лишь применяет пресет.
        registerPresetChip(bands, eq.chipFlat);
        registerPresetChip(bands, eq.chipRock);
        registerPresetChip(bands, eq.chipMetal);
        registerPresetChip(bands, eq.chipPop);
        registerPresetChip(bands, eq.chipJazz);
        registerPresetChip(bands, eq.chipClassical);
        registerPresetChip(bands, eq.chipBass);

        final boolean[] syncing = {false};
        Runnable syncSound = () -> {
            syncing[0] = true;
            eq.folderSound.setChecked(vm.isFolderSoundEnabled());
            eq.folderSound.setEnabled(vm.canBindFolderSound());
            syncEqFromManager(bands);
            gain.bandSeek.setProgress(Math.round(vm.getLoudness().getGainDb()));
            updateGainValue(gain);
            boolean enabled = vm.isSoundReady()
                    && !Boolean.TRUE.equals(vm.getDataBusy().getValue());
            for (ItemEqualizerBandBinding band : bands) {
                band.bandSeek.setEnabled(enabled);
            }
            gain.bandSeek.setEnabled(enabled);
            eq.btnReset.setEnabled(enabled);
            for (Chip chip : new Chip[]{eq.chipFlat, eq.chipRock, eq.chipMetal, eq.chipPop,
                    eq.chipJazz, eq.chipClassical, eq.chipBass}) {
                chip.setEnabled(enabled);
            }
            syncing[0] = false;
        };
        eq.folderSound.setOnCheckedChangeListener((button, checked) -> {
            if (!syncing[0]) {
                vm.setFolderSoundEnabled(checked);
            }
        });
        Observer<Integer> soundObserver = ignored -> syncSound.run();
        Observer<Boolean> busyObserver = ignored -> syncSound.run();
        vm.getSoundRevision().observe(this, soundObserver);
        vm.getDataBusy().observe(this, busyObserver);
        dialog.setOnDismissListener(ignored -> {
            vm.getSoundRevision().removeObserver(soundObserver);
            vm.getDataBusy().removeObserver(busyObserver);
        });

        dialog.show();
    }

    /**
     * Клик применяет пресет, удержание 2 с перезаписывает его текущими полосами.
     * Системный long click срабатывает раньше 2 с, поэтому используем свой таймер.
     */
    // Lint ClickableViewAccessibility ругается на класс Chip (библиотечный View
    // не переопределяет performClick), но здесь это ложное срабатывание: в
    // ACTION_UP слушатель сам вызывает chip.performClick(), поэтому событие
    // доступности приходит. Отдельный setOnLongClickListener не подходит —
    // системный порог long press (~500 мс) срабатывает раньше нужных 2 с.
    @SuppressLint("ClickableViewAccessibility")
    private void registerPresetChip(ItemEqualizerBandBinding[] bands, Chip chip) {
        chip.setOnClickListener(v -> {
            vm.applySoundPreset(chip.getText().toString());
            syncEqFromManager(bands);
        });
        chip.setOnTouchListener(new View.OnTouchListener() {
            private boolean saved;
            private String soundContext;
            private final Runnable savePreset = () -> {
                if (!chip.isEnabled() || !chip.isPressed() || !chip.isAttachedToWindow()
                        || !java.util.Objects.equals(soundContext, vm.getSoundContextToken())) {
                    return;
                }
                saved = true;
                String name = chip.getText().toString();
                vm.getEqualizer().savePreset(name);
                Toast.makeText(MainActivity.this,
                        getString(R.string.eq_preset_saved, name), Toast.LENGTH_SHORT).show();
            };

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        saved = false;
                        soundContext = vm.getSoundContextToken();
                        chip.setPressed(true);
                        chip.postDelayed(savePreset, EQ_PRESET_SAVE_HOLD_MS);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (event.getX() < 0 || event.getX() >= chip.getWidth()
                                || event.getY() < 0 || event.getY() >= chip.getHeight()) {
                            chip.removeCallbacks(savePreset);
                            chip.setPressed(false);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        chip.removeCallbacks(savePreset);
                        boolean clicked = chip.isPressed() && !saved
                                && java.util.Objects.equals(soundContext, vm.getSoundContextToken());
                        chip.setPressed(false);
                        if (clicked) {
                            chip.performClick();
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        chip.removeCallbacks(savePreset);
                        chip.setPressed(false);
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        chip.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View view) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View view) {
                chip.setPressed(false);
            }
        });
    }

    private void syncEqFromManager(ItemEqualizerBandBinding[] bands) {
        for (int i = 0; i < bands.length; i++) {
            float g = vm.getEqualizer().getBand(i);
            bands[i].bandSeek.setProgress(Math.round(g) + 15);
            bands[i].bandValue.setText(String.format(Locale.US, "%+.0f dB", g));
        }
    }

    /**
     * Значение колонки усиления: 0 дБ = «Выкл» (эффекта в тракте нет).
     */
    private void updateGainValue(ItemEqualizerBandBinding gain) {
        int p = gain.bandSeek.getProgress();
        gain.bandValue.setText(p == 0
                ? getString(R.string.gain_off)
                : String.format(Locale.US, "%+d dB", p));
    }

    // ------------------------------------------------------------------
    // Информация о треке (⋮)
    // ------------------------------------------------------------------

    /**
     * Диалог: файл запрошенного трека отсутствует (кеш устарел, флешка сменилась).
     */
    private void showMissingFileDialog(TrackEntity t) {
        String name = t.displayName();
        String src = t.isCue ? t.cueSourceFile : t.path;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_missing_file_title)
                .setMessage(getString(R.string.error_missing_file_message,
                        name, src == null ? "" : src))
                .setPositiveButton(R.string.action_rescan, (d, w) -> vm.refreshLibrary())
                .setNegativeButton(R.string.info_ok, null)
                .show();
    }

    /**
     * Диалог: ошибка воспроизведения с технической причиной (код Media3 и
     * цепочка исключений). На ГУ logcat недоступен — это единственный способ
     * увидеть, почему трек не играет (например, FLAC 24 бита/96 кГц).
     */
    private void showPlaybackErrorDialog(String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.playback_error_title)
                .setMessage(message)
                .setPositiveButton(R.string.info_ok, null)
                .setCancelable(true)
                .show();
    }

    private void showTrackInfo(TrackEntity t) {
        StringBuilder sb = new StringBuilder();
        if (t.artist != null && !t.artist.isEmpty()) {
            sb.append("Artist: ").append(t.artist).append('\n');
        }
        if (t.album != null && !t.album.isEmpty()) {
            sb.append("Album: ").append(t.album).append('\n');
        }
        if (t.trackNumber > 0) {
            sb.append("Track: ").append(t.trackNumber).append('\n');
        }
        sb.append(getString(R.string.info_format)).append(": ").append(t.formatLabel())
                .append('\n');
        sb.append(getString(R.string.info_duration)).append(": ")
                .append(LibraryRow.formatTime(t.durationMs)).append('\n');
        if (t.size > 0) {
            sb.append(getString(R.string.info_size)).append(": ")
                    .append(String.format(Locale.US, "%.1f MB", t.size / 1048576.0)).append('\n');
        }
        if (t.isCue) {
            sb.append(getString(R.string.info_cue)).append("\n");
        }
        sb.append(getString(R.string.info_path)).append("\n")
                .append(t.isCue ? t.cueSourceFile : t.path);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.track_info_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.info_ok, null)
                .show();
    }

    // ------------------------------------------------------------------
    // USB-события
    // ------------------------------------------------------------------

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) {
                return;
            }
            switch (action) {
                case Intent.ACTION_MEDIA_MOUNTED:
                    vm.onUsbMounted();
                    break;
                case Intent.ACTION_MEDIA_UNMOUNTED:
                case Intent.ACTION_MEDIA_REMOVED:
                case Intent.ACTION_MEDIA_EJECT:
                    vm.onUsbUnmounted();
                    break;
                default:
                    break;
            }
        }
    };

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addDataScheme("file");
        // API 33+ требует явно указать экспорт бродкаста (2-арг registerReceiver
        // устарел). Наши события — системные (MEDIA_*), их принимает
        // RECEIVER_NOT_EXPORTED; на API 28 ContextCompat использует старый путь.
        ContextCompat.registerReceiver(this, usbReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        vm.savePlaybackStateNow();
    }

    @Override
    protected void onDestroy() {
        dismissScanDialog();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
