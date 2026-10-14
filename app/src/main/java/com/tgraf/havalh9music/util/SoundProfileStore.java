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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.tgraf.havalh9music.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tgraf.havalh9music.model.SavedVolume;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Профили — пользовательские данные, поэтому не живут в пересоздаваемом кэше Room.
 * Карты используются только главным потоком. Диск читает и пишет один отдельный
 * поток: тикер получает готовый снимок, а поздняя запись не обгоняет удаление.
 */
public final class SoundProfileStore {
    private static final String TAG = "SoundProfiles";
    private static final String GENERAL = "general";
    private static final String PROFILE = "profile:";
    private static final String VOLUME = "volume:";
    private static final String CACHE_OWNER = "cacheOwner:";

    public interface Completion {
        void onComplete(boolean success);
    }

    public static final class Profile {
        private final float[] bands;
        public final float gainDb;

        public Profile(float[] bands, float gainDb) {
            this.bands = bands.clone();
            this.gainDb = gainDb;
        }

        public float[] bands() {
            return bands.clone();
        }

        String encode() {
            try {
                JSONArray values = new JSONArray();
                for (float band : bands) {
                    values.put((double) band);
                }
                return new JSONObject().put("version", 1).put("bands", values)
                        .put("gain", (double) gainDb).toString();
            } catch (JSONException e) {
                throw new IllegalArgumentException("Некорректные значения звука", e);
            }
        }

        static Profile decode(String raw) {
            try {
                JSONObject json = new JSONObject(raw);
                JSONArray values = json.getJSONArray("bands");
                if (json.getInt("version") != 1
                        || values.length() != EqualizerManager.BAND_COUNT) {
                    return null;
                }
                float[] bands = new float[EqualizerManager.BAND_COUNT];
                for (int i = 0; i < bands.length; i++) {
                    double value = values.getDouble(i);
                    if (!Double.isFinite(value) || value < EqualizerManager.MIN_DB
                            || value > EqualizerManager.MAX_DB) {
                        return null;
                    }
                    bands[i] = (float) value;
                }
                double gain = json.getDouble("gain");
                if (!Double.isFinite(gain) || gain < LoudnessManager.MIN_GAIN_DB
                        || gain > LoudnessManager.MAX_GAIN_DB) {
                    return null;
                }
                return new Profile(bands, (float) gain);
            } catch (JSONException | IllegalArgumentException e) {
                return null;
            }
        }
    }

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> profiles = new LinkedHashMap<>();
    private final Map<String, SavedVolume> volumes = new LinkedHashMap<>();
    private final Map<String, String> cacheOwners = new LinkedHashMap<>();
    private final Runnable writeError;
    private SharedPreferences prefs;
    private Profile general;
    private boolean closed;

    public SoundProfileStore(Context context, Profile legacy, Runnable writeError) {
        app = context.getApplicationContext();
        general = legacy;
        this.writeError = writeError;
    }

    public void load(Runnable ready) {
        io.execute(() -> {
            prefs = app.getSharedPreferences("folder_sound", Context.MODE_PRIVATE);
            Map<String, ?> saved = prefs.getAll();
            main.post(() -> {
                if (closed) {
                    return;
                }
                for (Map.Entry<String, ?> entry : saved.entrySet()) {
                    if (!(entry.getValue() instanceof String)) {
                        continue;
                    }
                    String key = entry.getKey();
                    String value = (String) entry.getValue();
                    if (GENERAL.equals(key)) {
                        Profile decoded = Profile.decode(value);
                        if (decoded != null) {
                            general = decoded;
                        }
                    } else if (key.startsWith(CACHE_OWNER)) {
                        cacheOwners.put(Uri.decode(key.substring(CACHE_OWNER.length())), value);
                    } else if (key.startsWith(PROFILE)) {
                        // Даже повреждённую запись сохраняем в реестре для удаления.
                        profiles.put(key, value);
                    } else if (key.startsWith(VOLUME)) {
                        SavedVolume volume = decodeVolume(Uri.decode(key.substring(VOLUME.length())),
                                value);
                        if (volume != null) {
                            volumes.put(volume.id, volume);
                        }
                    }
                }
                // Если запись реестра повреждена, профили всё равно доступны
                // для полной очистки: UUID извлекается из однозначного ключа.
                for (String key : profiles.keySet()) {
                    int delimiter = key.indexOf(':', PROFILE.length());
                    if (delimiter < 0) {
                        continue;
                    }
                    String id = Uri.decode(key.substring(PROFILE.length(), delimiter));
                    if (!volumes.containsKey(id)) {
                        List<String> paths = new ArrayList<>();
                        if (id.startsWith("path:")) {
                            paths.add(id.substring("path:".length()));
                        }
                        volumes.put(id, new SavedVolume(id, id, paths, new ArrayList<>(), false));
                    }
                }
                // При обновлении старые EQ/Loudness становятся общими ровно один раз.
                if (!saved.containsKey(GENERAL)) {
                    put(GENERAL, general.encode());
                }
                ready.run();
            });
        });
    }

    public Profile general() {
        return general;
    }

    private static String profilePrefix(String volumeId) {
        return PROFILE + Uri.encode(volumeId) + ":";
    }

    private static String profileKey(String volumeId, String folder) {
        return profilePrefix(volumeId) + Uri.encode(folder);
    }

    public Profile profile(String volumeId, String folder) {
        String raw = profiles.get(profileKey(volumeId, folder));
        return raw == null ? null : Profile.decode(raw);
    }

    public void saveGeneral(Profile profile) {
        general = profile;
        put(GENERAL, profile.encode());
    }

    public void saveProfile(String volumeId, String folder, Profile profile) {
        String key = profileKey(volumeId, folder);
        String raw = profile.encode();
        profiles.put(key, raw);
        put(key, raw);
    }

    public void removeProfile(String volumeId, String folder) {
        String key = profileKey(volumeId, folder);
        profiles.remove(key);
        io.execute(() -> report(prefs.edit().remove(key).commit()));
    }

    public void clearProfiles(String volumeId, Completion completion) {
        List<String> keys = new ArrayList<>();
        for (String key : profiles.keySet()) {
            if (volumeId == null || key.startsWith(profilePrefix(volumeId))) {
                keys.add(key);
            }
        }
        io.execute(() -> {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : keys) {
                editor.remove(key);
            }
            boolean success = editor.commit();
            main.post(() -> {
                if (success) {
                    for (String key : keys) {
                        profiles.remove(key);
                    }
                }
                completion.onComplete(success);
            });
        });
    }

    public List<SavedVolume> volumes() {
        return new ArrayList<>(volumes.values());
    }

    public SavedVolume volume(String id) {
        return volumes.get(id);
    }

    public String idForCache(String cacheUuid, String rootPath) {
        String owner = cacheOwners.get(cacheUuid);
        if (owner != null) {
            return owner;
        }
        for (SavedVolume volume : volumes.values()) {
            if (volume.cacheUuids.contains(cacheUuid)) {
                return volume.id;
            }
        }
        return UsbStorageHelper.getSoundVolumeId(app, new File(rootPath));
    }

    public String cacheOwner(String cacheUuid) {
        return cacheOwners.get(cacheUuid);
    }

    /**
     * Старый кэш на ГУ может быть привязан к общему /mnt/usb_storage. UUID
     * профилей различает флешки, а владелец кэша не позволяет забыванию старой
     * флешки удалить библиотеку новой, занявшей ту же точку монтирования.
     */
    public void claimCache(String cacheUuid, String id) {
        if (!id.equals(cacheOwners.get(cacheUuid))) {
            cacheOwners.put(cacheUuid, id);
            put(CACHE_OWNER + Uri.encode(cacheUuid), id);
        }
    }

    public void remember(String id, String label, String rootPath, String cacheUuid) {
        SavedVolume old = volumes.get(id);
        List<String> paths = old == null ? new ArrayList<>() : new ArrayList<>(old.rootPaths);
        List<String> uuids = old == null ? new ArrayList<>() : new ArrayList<>(old.cacheUuids);
        if (old != null && old.label.equals(label) && paths.contains(rootPath)
                && uuids.contains(cacheUuid)) {
            return;
        }
        if (!paths.contains(rootPath)) {
            paths.add(rootPath);
        }
        if (!uuids.contains(cacheUuid)) {
            uuids.add(cacheUuid);
        }
        SavedVolume volume = new SavedVolume(id, label, paths, uuids, false);
        volumes.put(id, volume);
        put(VOLUME + Uri.encode(id), encodeVolume(volume));
    }

    /**
     * Сначала удаляем зависимое состояние, затем реестр. При сбое запись тома
     * остаётся видна и позволяет повторить очистку, даже если Room уже пуст.
     */
    public void forget(String id, boolean clearRoot, boolean clearPlayback,
                       Completion completion) {
        List<String> keys = new ArrayList<>();
        for (String key : profiles.keySet()) {
            if (key.startsWith(profilePrefix(id))) {
                keys.add(key);
            }
        }
        List<String> ownedCaches = new ArrayList<>();
        for (Map.Entry<String, String> entry : cacheOwners.entrySet()) {
            if (id.equals(entry.getValue())) {
                ownedCaches.add(entry.getKey());
            }
        }
        io.execute(() -> {
            boolean success = true;
            if (clearRoot) {
                success = app.getSharedPreferences("usb_root", Context.MODE_PRIVATE)
                        .edit().clear().commit();
            }
            if (clearPlayback) {
                success &= app.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
                        .edit().clear().commit();
            }
            if (success) {
                SharedPreferences.Editor editor = prefs.edit().remove(VOLUME + Uri.encode(id));
                for (String key : keys) {
                    editor.remove(key);
                }
                for (String uuid : ownedCaches) {
                    editor.remove(CACHE_OWNER + Uri.encode(uuid));
                }
                success = editor.commit();
            }
            final boolean done = success;
            main.post(() -> {
                if (done) {
                    volumes.remove(id);
                    for (String key : keys) {
                        profiles.remove(key);
                    }
                    for (String uuid : ownedCaches) {
                        cacheOwners.remove(uuid);
                    }
                }
                completion.onComplete(done);
            });
        });
    }

    private void put(String key, String value) {
        io.execute(() -> report(prefs.edit().putString(key, value).commit()));
    }

    private void report(boolean success) {
        if (!success) {
            main.post(writeError);
        }
    }

    private static String encodeVolume(SavedVolume volume) {
        try {
            return new JSONObject().put("version", 1).put("label", volume.label)
                    .put("paths", new JSONArray(volume.rootPaths))
                    .put("cacheUuids", new JSONArray(volume.cacheUuids)).toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static SavedVolume decodeVolume(String id, String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            if (json.getInt("version") != 1) {
                return null;
            }
            return new SavedVolume(id, json.getString("label"),
                    strings(json.getJSONArray("paths")),
                    strings(json.getJSONArray("cacheUuids")), false);
        } catch (JSONException e) {
            Log.w(TAG, "Некорректная запись накопителя", e);
            return null;
        }
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            result.add(array.getString(i));
        }
        return result;
    }

    public static String relativeFolder(File root, String folder) {
        if (root == null || folder == null) {
            return null;
        }
        java.nio.file.Path base = root.toPath().toAbsolutePath().normalize();
        java.nio.file.Path path = new File(folder).toPath().toAbsolutePath().normalize();
        return path.startsWith(base) ? base.relativize(path).toString() : null;
    }

    public void close() {
        closed = true;
        io.shutdown();
    }
}
