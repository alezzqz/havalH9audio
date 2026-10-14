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
import android.os.Looper;
import android.util.Log;

import com.tgraf.havalh9music.data.AppDatabase;
import com.tgraf.havalh9music.model.LibraryRow;
import com.tgraf.havalh9music.model.RootEntity;
import com.tgraf.havalh9music.model.SavedVolume;
import com.tgraf.havalh9music.model.TrackEntity;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Кэширование иерархии флешки в Room + принудительное обновление.
 * <p>
 * Скорость: метаданные читаются собственным парсером {@link AudioTagParser}
 * (без MediaMetadataRetriever) и параллельно на нескольких потоках; обложка
 * каталога ищется 1 раз на каталог, а не на каждый файл.
 * <p>
 * Запись инкрементальная: каждый просканированный каталог сразу перезаписывает
 * свои строки — при аварийном завершении скан не теряет результат, а после
 * рестарта приложение показывает уже просканированную часть.
 * <p>
 * Валидация: запись о томе (по UUID) с флагом «скан завершён». Содержимое
 * флешки повторно не сверяем — если кэш есть, он считается валидным.
 * Запросы к Room и скан выполняются на РАЗНЫХ пулах, поэтому навигация по
 * спискам работает, даже пока в фоне идёт пересканирование.
 */
public class LibraryCacheManager {

    private static final String TAG = "LibraryCache";

    public interface ScanListener {
        void onProgress(int completed, int total);

        void onBrokenCue(String fileName);

        void onComplete(File root, int trackCount);

        void onError(String message);
    }

    /**
     * Колбэк с результатом запроса в Room (вызывается на главном потоке).
     */
    public interface DbCallback<T> {
        void onResult(T result);
    }

    /**
     * Результат проверки кэша.
     */
    public enum CacheCheck {
        /**
         * Кэш для этого тома есть и скан был завершён — сразу открываем списки.
         */
        VALID,
        /**
         * Кэша на этот том нет (или скан прерывался) — нужен полный скан.
         */
        MISS
    }

    private final Context app;
    private final AppDatabase db;
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * Разбор файлов — параллельно (на слабых ГУ больше 4 потоков не помогает).
     */
    private final ExecutorService parsePool =
            Executors.newFixedThreadPool(parseThreads());
    /**
     * Скан — отдельный поток-супервизор.
     */
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    /**
     * Запросы к Room — отдельный поток (не блокируются сканом).
     */
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private volatile String scanVolumeUuid;

    public LibraryCacheManager(Context context) {
        this.app = context.getApplicationContext();
        this.db = AppDatabase.get(app);
    }

    private static int parseThreads() {
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, n));
    }

    /**
     * Проверка кэша: VALID — открываем списки сразу, без скана; MISS — нужен
     * полный скан. Содержимое тома не сверяем: если запись есть и скан был
     * завершён, кэш считается валидным. Запрос — в фоне.
     */
    public void checkCacheAsync(File root, DbCallback<CacheCheck> cb) {
        dbExecutor.submit(() -> {
            CacheCheck cc;
            try {
                RootEntity r = db.rootDao().rootByUuid(UsbStorageHelper.getVolumeUuid(root));
                // complete = true выставляется только в самом конце скана;
                // прерванный скан (force-stop) остаётся с false — тогда пересканируем.
                cc = (r != null && r.complete) ? CacheCheck.VALID : CacheCheck.MISS;
            } catch (Exception e) {
                Log.e(TAG, "Проверка кэша упала", e);
                cc = CacheCheck.MISS;
            }
            final CacheCheck c = cc;
            main.post(() -> {
                if (cb != null) {
                    cb.onResult(c);
                }
            });
        });
    }

    /**
     * Треки и подпапки (с подсчётом треков в подпапках) из кэша. Запрос — в фоне.
     */
    public void loadRowsAsync(File dir, DbCallback<List<LibraryRow>> cb) {
        dbExecutor.submit(() -> {
            List<TrackEntity> tracks = db.trackDao().tracksIn(dir.getAbsolutePath());
            List<LibraryRow> result = new ArrayList<>();
            File[] kids = dir.listFiles();
            if (kids != null) {
                List<File> dirs = new ArrayList<>();
                for (File k : kids) {
                    if (k.isDirectory() && !k.getName().startsWith(".")) {
                        dirs.add(k);
                    }
                }
                dirs.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File d : dirs) {
                    int count = db.trackDao().countIn(d.getAbsolutePath());
                    result.add(LibraryRow.folder(d.getName(), d.getAbsolutePath(), count));
                }
            }
            for (TrackEntity t : tracks) {
                result.add(LibraryRow.track(t));
            }
            main.post(() -> {
                if (cb != null) {
                    cb.onResult(result);
                }
            });
        });
    }

    /**
     * Треки из кэша в указанном каталоге (сортировка по имени). Запрос — в фоне.
     */
    public void tracksInAsync(File dir, DbCallback<List<TrackEntity>> cb) {
        dbExecutor.submit(() -> {
            List<TrackEntity> list = db.trackDao().tracksIn(dir.getAbsolutePath());
            main.post(() -> {
                if (cb != null) {
                    cb.onResult(list);
                }
            });
        });
    }

    /**
     * Упорядоченный список каталогов, содержащих треки, для сквозного
     * воспроизведения: обход «в глубину», при этом подпапки идут раньше
     * собственных треков каталога — точно в том порядке, как они показаны
     * в левой панели. Запрос — в фоне.
     */
    public void loadOrderedTrackDirsAsync(File root, DbCallback<List<String>> cb) {
        dbExecutor.submit(() -> {
            List<String> result;
            try {
                List<String> parents = db.trackDao().allParentPaths();
                result = orderTrackDirs(root.getAbsolutePath(), parents);
            } catch (Exception e) {
                Log.e(TAG, "Не удалось построить порядок каталогов", e);
                result = new ArrayList<>();
            }
            final List<String> r = result;
            main.post(() -> {
                if (cb != null) {
                    cb.onResult(r);
                }
            });
        });
    }

    /**
     * Узел дерева каталогов для построения порядка обхода.
     */
    private static final class DirNode {
        final String absPath;
        final TreeMap<String, DirNode> children =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean hasTracks;

        DirNode(String absPath) {
            this.absPath = absPath;
        }
    }

    /**
     * Строит порядок каталогов с треками: в пределах каждого каталога сначала
     * обход подпапок (по имени), затем собственные треки. Каталоги без треков
     * (ни прямо, ни в подпапках) в список не попадают.
     */
    private static List<String> orderTrackDirs(String rootAbs, List<String> dirsWithTracks) {
        DirNode root = new DirNode(rootAbs);
        for (String d : dirsWithTracks) {
            if (d == null) {
                continue;
            }
            if (!d.equals(rootAbs) && !d.startsWith(rootAbs + File.separator)) {
                continue;
            }
            String rel = d.equals(rootAbs) ? "" : d.substring(rootAbs.length() + 1);
            DirNode node = root;
            if (!rel.isEmpty()) {
                StringBuilder acc = new StringBuilder(rootAbs);
                for (String seg : rel.split(java.util.regex.Pattern.quote(File.separator))) {
                    if (seg.isEmpty()) {
                        continue;
                    }
                    acc.append(File.separator).append(seg);
                    DirNode child = node.children.get(seg);
                    if (child == null) {
                        child = new DirNode(acc.toString());
                        node.children.put(seg, child);
                    }
                    node = child;
                }
            }
            node.hasTracks = true;
        }
        List<String> ordered = new ArrayList<>();
        collectTrackDirs(root, ordered);
        return ordered;
    }

    private static void collectTrackDirs(DirNode node, List<String> out) {
        for (DirNode child : node.children.values()) {
            collectTrackDirs(child, out);
        }
        if (node.hasTracks) {
            out.add(node.absPath);
        }
    }

    /**
     * Все треки библиотеки (для случайного режима). Запрос — в фоне.
     */
    public void loadAllTracksAsync(DbCallback<List<TrackEntity>> cb) {
        dbExecutor.submit(() -> {
            List<TrackEntity> list;
            try {
                list = db.trackDao().allTracks();
            } catch (Exception e) {
                Log.e(TAG, "Не удалось загрузить все треки", e);
                list = new ArrayList<>();
            }
            final List<TrackEntity> r = list;
            main.post(() -> {
                if (cb != null) {
                    cb.onResult(r);
                }
            });
        });
    }

    public void loadSavedRootsAsync(DbCallback<List<RootEntity>> cb) {
        dbExecutor.submit(() -> {
            try {
                List<RootEntity> roots = db.rootDao().allRoots();
                main.post(() -> cb.onResult(roots));
            } catch (Exception e) {
                Log.e(TAG, "Не удалось прочитать реестр накопителей", e);
                main.post(() -> cb.onResult(null));
            }
        });
    }

    /**
     * Барьер в потоке скана гарантирует, что его последние записи завершились
     * ДО удаления. Старые задания и колбэки не оживят забытый накопитель.
     */
    public void deleteVolumeAsync(SavedVolume volume, DbCallback<Boolean> cb) {
        if (volume.cacheUuids.contains(scanVolumeUuid)) {
            scanGeneration.incrementAndGet();
        }
        scanExecutor.submit(() -> {
            try {
                db.runInTransaction(() -> {
                    List<RootEntity> roots = db.rootDao().allRoots();
                    Set<String> paths = new HashSet<>(volume.rootPaths);
                    for (RootEntity root : roots) {
                        if (volume.cacheUuids.contains(root.volumeUuid)) {
                            paths.add(root.rootPath);
                        }
                    }
                    for (String path : paths) {
                        // Без UUID прошивка может повторно использовать /mnt/usb.
                        // Старый реестр не даёт права стирать кэш другого тома.
                        boolean otherOwner = false;
                        for (RootEntity root : roots) {
                            if (path.equals(root.rootPath)
                                    && !volume.cacheUuids.contains(root.volumeUuid)) {
                                otherOwner = true;
                                break;
                            }
                        }
                        if (!otherOwner) {
                            db.trackDao().deleteUnderRoot(path);
                        }
                    }
                    for (String uuid : volume.cacheUuids) {
                        db.rootDao().deleteByUuid(uuid);
                    }
                });
                // DELETE освобождает страницы только для повторного использования.
                // VACUUM возвращает место файловой системе, включая забытые флешки.
                db.getOpenHelper().getWritableDatabase().execSQL("VACUUM");
                try (android.database.Cursor ignored = db.query(
                        "PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                    ignored.moveToFirst();
                }
                main.post(() -> cb.onResult(true));
            } catch (Exception e) {
                Log.e(TAG, "Очистка накопителя не завершена", e);
                main.post(() -> cb.onResult(false));
            }
        });
    }

    public void cancelScan() {
        scanGeneration.incrementAndGet();
    }

    /**
     * Запускает полное сканирование флешки.
     */
    public void scan(File root, ScanListener listener) {
        int generation = scanGeneration.incrementAndGet();
        scanVolumeUuid = UsbStorageHelper.getVolumeUuid(root);
        scanExecutor.submit(() -> doScan(root, listener, generation));
    }

    // ---------------------------------------------------------------------

    private void doScan(File root, ScanListener listener, int generation) {
        if (generation != scanGeneration.get()) {
            return;
        }
        long t0 = System.currentTimeMillis();
        UsbSpeedSimulator.beginScan(app);
        try {
            // Метка «скан начат»: complete = false. Если процесс убьют (force-stop),
            // при следующем старте такая запись будет распознана как MISS —
            // библиотека пересканируется заново, а не покажет усечённый кэш.
            // В конце скана метка заменяется финальной (complete = true).
            RootEntity startMark = new RootEntity();
            startMark.volumeUuid = UsbStorageHelper.getVolumeUuid(root);
            startMark.rootPath = root.getAbsolutePath();
            startMark.scannedAt = System.currentTimeMillis();
            startMark.fileCount = 0;
            startMark.complete = false;
            db.rootDao().upsert(startMark);

            // 1) рекурсивно собираем все файлы
            List<File> all = new ArrayList<>();
            collectFiles(root, all, generation);
            if (generation != scanGeneration.get()) {
                return;
            }
            int total = all.size();
            Log.i(TAG, "doScan: root=" + root.getAbsolutePath()
                    + " всего файлов=" + all.size());

            // 2) разделяем на аудио и cue
            List<File> audio = new ArrayList<>();
            List<File> cues = new ArrayList<>();
            for (File f : all) {
                String ext = extension(f);
                if ("flac".equals(ext) || "mp3".equals(ext)) {
                    audio.add(f);
                } else if ("cue".equals(ext)) {
                    cues.add(f);
                }
            }
            Log.i(TAG, "doScan: аудио=" + audio.size() + " cue=" + cues.size()
                    + (audio.isEmpty() ? "" : " первый=" + audio.get(0).getAbsolutePath()));

            // 3) парсим CUE (в т.ч. многодисковые): FLAC → виртуальные треки
            Map<String, List<CueSheetParser.CueTrack>> bySource = new HashMap<>();
            for (File cue : cues) {
                if (generation != scanGeneration.get()) {
                    return;
                }
                try {
                    List<CueSheetParser.CueTrack> tracks = CueSheetParser.parse(cue);
                    for (CueSheetParser.CueTrack ct : tracks) {
                        bySource.computeIfAbsent(ct.sourceFile, k -> new ArrayList<>()).add(ct);
                    }
                } catch (CueSheetParser.CueParseException e) {
                    Log.w(TAG, "Битый CUE: " + cue.getAbsolutePath(), e);
                    final String name = cue.getName();
                    main.post(() -> {
                        if (listener != null && generation == scanGeneration.get()) {
                            listener.onBrokenCue(name);
                        }
                    });
                    // Откат: FLAC останется как единый трек
                }
            }

            // 4) группируем аудио по каталогам: обложка ищется 1 раз на каталог,
            //    каталог обрабатывается (и записывается в кэш) целиком
            Map<String, List<File>> byDir = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Set<String> liveParents = new HashSet<>();
            for (File f : audio) {
                File p = f.getParentFile();
                String key = (p == null ? "" : p.getAbsolutePath());
                byDir.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            }

            // Обложки по каталогам ("" — каталог проверен, обложки нет)
            Map<String, String> coverByDir = new HashMap<>();

            int done = 0;
            int entityCount = 0;
            for (Map.Entry<String, List<File>> e : byDir.entrySet()) {
                if (generation != scanGeneration.get()) {
                    return;
                }
                String dirAbs = e.getKey();
                File dir = new File(dirAbs);
                List<File> files = e.getValue();
                liveParents.add(dirAbs);

                String cover = coverByDir.get(dirAbs);
                if (cover == null && !dirAbs.isEmpty()) {
                    String found = CoverArtLoader.findCoverPath(dir);
                    coverByDir.put(dirAbs, found == null ? "" : found);
                    cover = coverByDir.get(dirAbs); // "" или путь
                }
                final String dirCover = cover;

                // 5) параллельный разбор файлов каталога (порядок не важен — сортировка в UI)
                List<Future<List<TrackEntity>>> futures = new ArrayList<>(files.size());
                for (final File f : files) {
                    futures.add(parsePool.submit(() -> {
                        String abs = f.getAbsolutePath();
                        List<CueSheetParser.CueTrack> vtracks = bySource.get(abs);
                        if (vtracks != null && !vtracks.isEmpty()) {
                            long endMs = readFileDurationMs(f);
                            return buildCueTracks(f, vtracks, endMs, dirCover);
                        }
                        List<TrackEntity> one = new ArrayList<>(1);
                        one.add(metaScan(f, dirCover));
                        return one;
                    }));
                }
                List<TrackEntity> entities = new ArrayList<>();
                for (Future<List<TrackEntity>> fut : futures) {
                    if (generation != scanGeneration.get()) {
                        for (Future<List<TrackEntity>> pending : futures) {
                            pending.cancel(true);
                        }
                        return;
                    }
                    try {
                        List<TrackEntity> got = fut.get();
                        if (got != null) {
                            entities.addAll(got);
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "Ошибка разбора файла", ex);
                    }
                }

                // 6) инкрементальная запись: перезаписываем только этот каталог
                if (generation != scanGeneration.get()) {
                    return;
                }
                db.trackDao().deleteInParent(dirAbs);
                for (int i = 0; i < entities.size(); i += 500) {
                    db.trackDao()
                            .insertAll(entities.subList(i, Math.min(i + 500, entities.size())));
                }
                entityCount += entities.size();

                done += files.size();
                if (done % 10 == 0 || done == total) {
                    final int d = done;
                    final int t = total;
                    main.post(() -> {
                        if (listener != null && generation == scanGeneration.get()) {
                            listener.onProgress(d, t);
                        }
                    });
                }
            }

            if (generation != scanGeneration.get()) {
                return;
            }

            // 7) удаляем каталоги, которых больше нет на накопителе
            List<String> stale = db.trackDao().allParentPaths();
            for (String p : stale) {
                if (generation != scanGeneration.get()) {
                    return;
                }
                if (SoundProfileStore.relativeFolder(root, p) != null
                        && !liveParents.contains(p)) {
                    db.trackDao().deleteInParent(p);
                }
            }

            // 8) финальная метка тома: скан завершён — кэш действителен
            RootEntity re = new RootEntity();
            re.volumeUuid = UsbStorageHelper.getVolumeUuid(root);
            re.rootPath = root.getAbsolutePath();
            re.scannedAt = System.currentTimeMillis();
            re.fileCount = audio.size();
            re.complete = true;
            db.rootDao().upsert(re);

            final File doneRoot = root;
            final int count = entityCount;
            Log.i(TAG, "doScan: ready_in_ms=" + (System.currentTimeMillis() - t0)
                    + " files=" + audio.size() + " rows=" + count
                    + " (готово за " + (System.currentTimeMillis() - t0) + " мс)");
            main.post(() -> {
                if (listener != null && generation == scanGeneration.get()) {
                    listener.onComplete(doneRoot, count);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Сканирование упало", e);
            main.post(() -> {
                if (listener != null && generation == scanGeneration.get()) {
                    listener.onError("Ошибка сканирования: " + e.getMessage());
                }
            });
        } finally {
            UsbSpeedSimulator.endScan();
        }
    }

    /**
     * Виртуальные треки из CUE для одного физического FLAC.
     */
    private List<TrackEntity> buildCueTracks(File flac,
                                             List<CueSheetParser.CueTrack> vtracks, long fileEndMs,
                                             String dirCover) {
        String abs = flac.getAbsolutePath();
        File dir = flac.getParentFile();
        String cover = (dirCover == null || dirCover.isEmpty()) ? null : dirCover;
        List<TrackEntity> result = new ArrayList<>();
        for (CueSheetParser.CueTrack ct : vtracks) {
            TrackEntity e = new TrackEntity();
            e.path = abs + "#" + ct.number;
            // getParentFile() может вернуть null (файл в корне тома)
            e.parentPath = dir == null ? "" : dir.getAbsolutePath();
            e.fileName = flac.getName();
            e.extension = "flac";
            e.size = flac.length();
            // endMs = -1 у последнего трека файла («до конца») — берём реальную
            // длину исходника, чтобы в списке показывалась верная длительность
            // (при воспроизведении ExoPlayer и так кладёт конец на конец файла).
            long end = ct.endMs > 0 ? ct.endMs : fileEndMs;
            e.durationMs = end > ct.startMs ? end - ct.startMs : -1;

            e.title = ct.title == null || ct.title.isEmpty() ? null : ct.title;
            e.artist = ct.performer == null || ct.performer.isEmpty() ? null : ct.performer;
            e.genre = ct.genre;
            e.date = ct.date;
            e.trackNumber = ct.number;

            e.name = fileBaseName(flac) + " #" + ct.number;
            e.isCue = true;
            e.cuePath = ct.cuePath;
            e.cueSourceFile = abs;
            e.cueStartMs = ct.startMs;
            e.cueEndMs = ct.endMs;
            e.diskIndex = ct.diskIndex;

            e.coverPath = cover;
            e.hasCover = cover != null;
            result.add(e);
        }
        return result;
    }

    /**
     * Метаданные обычного файла (парсер MP3/FLAC вместо MediaMetadataRetriever).
     */
    private TrackEntity metaScan(File f, String dirCover) {
        TrackEntity e = new TrackEntity();
        e.fileName = f.getName();
        String nm = f.getName();
        int dot = nm.lastIndexOf('.');
        e.name = dot > 0 ? nm.substring(0, dot) : nm;
        e.extension = extension(f);
        e.path = f.getAbsolutePath();
        File p = f.getParentFile();
        e.parentPath = p == null ? "" : p.getAbsolutePath();
        e.size = f.length();

        String cover = (dirCover == null || dirCover.isEmpty()) ? null : dirCover;
        e.coverPath = cover;
        e.hasCover = cover != null || EmbeddedArt.hasPicture(f);

        AudioTagParser.Tags tags = AudioTagParser.read(f);
        e.durationMs = tags.durationMs > 0 ? tags.durationMs : -1;
        e.title = tags.title;
        e.artist = tags.artist;
        e.album = tags.album;
        e.trackNumber = tags.trackNumber;
        return e;
    }

    // ---------------------------------------------------------------------

    /**
     * Длина аудиофайла в мс (для CUE — конец последнего трека = конец файла).
     */
    private long readFileDurationMs(File f) {
        AudioTagParser.Tags tags = AudioTagParser.read(f);
        return tags.durationMs > 0 ? tags.durationMs : -1;
    }

    private void collectFiles(File dir, List<File> out, int generation) {
        if (generation != scanGeneration.get()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return; // нет прав (напр. Android/data) — пропускаем каталог
        }
        UsbSpeedSimulator.opDir();
        for (File k : kids) {
            if (generation != scanGeneration.get()) {
                return;
            }
            if (k.isDirectory()) {
                String n = k.getName();
                if (n.startsWith(".")) {
                    continue; // скрытые каталоги пропускаем
                }
                collectFiles(k, out, generation);
            } else {
                out.add(k);
            }
        }
    }

    private static String extension(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String fileBaseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }
}
