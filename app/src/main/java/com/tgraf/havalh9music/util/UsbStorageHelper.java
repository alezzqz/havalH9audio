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

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.tgraf.havalh9music.model.UsbDevice;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Поиск корней USB-флешек.
 * Перебирает типовые точки монтирования, динамические /storage/* и тома StorageManager.
 */
public final class UsbStorageHelper {

    private UsbStorageHelper() {
    }

    /**
     * Типовые точки монтирования USB (по производителям ГУ).
     */
    @SuppressLint("SdCardPath")
    private static final String[] MOUNT_POINTS = {
            "/mnt/usb_storage",
            "/mnt/media_rw",
            "/storage/udisk",
            "/storage/usbhost",
            "/mnt/sdcard/usb",
    };

    /**
     * Возвращает ВСЕ доступные медиаустройства (для диалога выбора):
     * типовые USB-точки ГУ, съёмные тома StorageManager, внутренняя память
     * и динамические /storage/*. Один и тот же накопитель не дублируется:
     * сырое /mnt/media_rw/XXXX и публичное /storage/XXXX считаются одним
     * устройством (приоритет — публичный путь).
     */
    public static List<UsbDevice> findAllUsbRoots(Context context) {
        Map<String, UsbDevice> byKey = new LinkedHashMap<>();

        // 1) Типовые пути USB на ГУ
        for (String p : MOUNT_POINTS) {
            File f = new File(p);
            if (isReadableRoot(f)) {
                byKey.put(deviceKey(f), new UsbDevice(f, labelFor(f)));
            }
        }

        // 2) Реальные съёмные тома (USB-флешка/SD) через StorageManager
        if (context != null) {
            StorageManager sm = context.getSystemService(StorageManager.class);
            if (sm != null) {
                List<StorageVolume> volumes = sm.getStorageVolumes();
                for (StorageVolume v : volumes) {
                    if (v == null || !v.isRemovable() || v.isPrimary()) {
                        continue;
                    }
                    File dir = volumeDirectory(v);
                    if (dir == null) {
                        continue;
                    }
                    // «Физический» самовид emulated-хранилища (uuid 0000-0000): видна
                    // только собственная песочница приложения — настоящая флешка так не выглядит.
                    if ("0000-0000".equals(v.getUuid())
                            || "0000-0000".equals(dir.getName())) {
                        continue;
                    }
                    if (!isReadableRoot(dir)) {
                        continue;
                    }
                    byKey.put(deviceKey(dir), new UsbDevice(dir, labelForVolume(v, context)));
                }
            }
        }

        // 3) Основной общий накопитель — /storage/emulated/0 (НЕ самовид /storage/0000-0000)
        if (context != null) {
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null
                    && !"/storage/0000-0000".equals(primary.getAbsolutePath())
                    && isReadableRoot(primary)) {
                byKey.put(deviceKey(primary), new UsbDevice(primary, "Внутренняя память"));
            }
        }

        // 4) Резерв: динамические /storage/*, исключая служебные «самовиды» primary
        File storage = new File("/storage");
        File[] children = storage.listFiles();
        if (children != null) {
            for (File f : children) {
                String name = f.getName();
                if ("emulated".equals(name) || "self".equals(name)
                        || "0000-0000".equals(name)) {
                    continue;
                }
                if (!isReadableRoot(f)) {
                    continue;
                }
                byKey.put(deviceKey(f), new UsbDevice(f, labelFor(f)));
            }
        }

        return new ArrayList<>(byKey.values());
    }

    /**
     * Путь тома: StorageVolume.getDirectory() существует только с API 30.
     * На Android 9 (ГУ) его нет, а getPathFile()/getPath() удалены из android.jar
     * SDK 34 (не компилируются), хотя физически присутствуют в системе —
     * достаём их рефлексией для API 28–29.
     */
    private static File volumeDirectory(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return v.getDirectory();
        }
        try {
            //noinspection JavaReflectionMemberAccess
            Method m = StorageVolume.class.getMethod("getPathFile");
            return (File) m.invoke(v);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Ключ устройства для дедупликации: /mnt/media_rw/XXXX и /storage/XXXX —
     * один и тот же накопитель (поздний кандидат с тем же ключом заменяет ранний).
     */
    private static String deviceKey(File f) {
        String abs = f.getAbsolutePath();
        if (abs.startsWith("/mnt/media_rw/")) {
            return abs.substring("/mnt/media_rw/".length());
        }
        return abs;
    }

    /**
     * Понятная подпись для каталога-корня (не-тома).
     */
    private static String labelFor(File f) {
        String abs = f.getAbsolutePath();
        if ("/storage/emulated/0".equals(abs) ||
                abs.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            return "Внутренняя память";
        }
        return "USB-накопитель (" + abs + ")";
    }

    /**
     * Понятная подпись для съёмного тома StorageManager (описание/метка).
     */
    private static String labelForVolume(StorageVolume v, Context context) {
        if (v == null) {
            return "USB-накопитель";
        }
        try {
            String d = v.getDescription(context);
            if (d != null && !d.isEmpty()) {
                return d;
            }
        } catch (Exception ignored) {
        }
        if (v.getUuid() != null && !v.getUuid().isEmpty()) {
            return "USB-накопитель (" + v.getUuid() + ")";
        }
        return "USB-накопитель (" + volumeDirectory(v) + ")";
    }

    /**
     * Корень читаемый: существует, каталог, доступен листинг.
     */
    private static boolean isReadableRoot(File f) {
        if (f == null || !f.exists() || !f.isDirectory()) {
            return false;
        }
        if (!f.canRead()) {
            return false;
        }
        File[] listing = f.listFiles();
        return listing != null; // listFiles()==null → нет прав на чтение
    }

    /**
     * Стабильный идентификатор тома для валидации кэша.
     * Для /storage/XXXX-XXXX используем сам сегмент, иначе — хэш пути.
     */
    public static String getVolumeUuid(File root) {
        if (root == null) {
            return "";
        }
        String abs = root.getAbsolutePath();
        if (abs.startsWith("/storage/")) {
            String seg = root.getName();
            if (seg.length() <= 11 && seg.contains("-")) {
                return seg;
            }
        }
        return "usb_" + Math.abs(abs.hashCode());
    }

    /**
     * Для пользовательских профилей нужен UUID самого тома, а не хэш точки
     * монтирования. Старый ключ кэша не меняем: иначе потеряем уже сохранённую
     * библиотеку. При отсутствии UUID резервный ключ честно привязан к пути.
     */
    public static String getSoundVolumeId(Context context, File root) {
        if (root == null) {
            return "";
        }
        try {
            StorageManager manager = context.getSystemService(StorageManager.class);
            StorageVolume volume = manager == null ? null : manager.getStorageVolume(root);
            if (volume != null && volume.getUuid() != null && !volume.getUuid().isEmpty()) {
                return "uuid:" + volume.getUuid().toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            // Прошивки ГУ могут не отдавать том даже при читаемом /storage/UUID.
        }
        String name = root.getName();
        String abs = root.getAbsolutePath();
        if ((abs.startsWith("/storage/") || abs.startsWith("/mnt/media_rw/"))
                && name.matches("(?i)[0-9a-f]{4}-[0-9a-f]{4}")) {
            return "uuid:" + name.toLowerCase(Locale.ROOT);
        }
        return "path:" + root.toPath().toAbsolutePath().normalize();
    }

}
