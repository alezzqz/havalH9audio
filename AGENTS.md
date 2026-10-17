# AGENTS.md — HavalH9Music

Оффлайн-аудиоплеер для штатного головного устройства Haval: читает USB/SD-накопитель
напрямую, кэширует иерархию папок в Room, играет через Media3 ExoPlayer в
`MediaSessionService`. Один экран, ландшафт, тёмная тема, управление с руля.

## Стек — жёсткие рамки

| | |
|---|---|
| Язык | **только Java**. Kotlin-плагина в проекте нет, Room подключён через `annotationProcessor` (не kapt) |
| Сборка | AGP 9.4.1, Gradle 9.6.0, `compileSdk 34`, `minSdk 28`, `targetSdk 33`, Java 11 |
| Воспроизведение | Media3 1.2.1 (`exoplayer` + `session`) |
| Хранилище | Room 2.5.2 (`AppDatabase` v3, `exportSchema = false`) |
| UI | ViewBinding + LiveData. **Нет** DataBinding, Compose, Flow, корутин, Retrofit |
| FLAC | локальный `app/libs/media3-flac-1.2.1.jar` (Java-мост) + `jniLibs/<ABI>/libflacJNI.so` (libFLAC 1.5.0, JNI); пересборка — `docs/libFLAC-rebuild.md` |
| Размер APK | ~4.5 МБ (release: ARM libFLAC). Новые зависимости — только с явного согласования |

Целевое устройство — ГУ на **Android 9 (API 28)**, 1920×1080. Всё, что работает на
эмуляторе, обязано работать там: часть фич Media3/system на API 28 деградирует
(см. «Инварианты»).

## Дополнительно
- после сборки релиза копируй его в dist
- Для тестирования используй эмулятор Haval H9
- После финального варианта предлагай проверить линтером на ворнинги

## Команды

```bash
# Windows
gradlew.bat assembleDebug            # сборка (configuration cache включён, ~10 c)
gradlew.bat test                     # юнит-тесты (CueSheetParserTest)
gradlew.bat assembleRelease          # нужен app/keystore.properties
gradlew.bat installDebug             # на подключённое устройство/эмулятор

# Linux/macOS: ./gradlew ...
```

When you need to search docs, use `context7` tools.

**`JAVA_HOME` в системе не задан**, и `java` не в `PATH` — Gradle из терминала
падает с `JAVA_HOME is not set`. Перед любой командой:

```powershell
$env:JAVA_HOME="$env:ProgramFiles\Android\Android Studio\jbr"
```

(В Android Studio всё работает само — там используется тот же JBR.)

Артефакт релиза руками кладётся в `dist/` (там же скриншоты для дистрибутива).
Имя выходного APK — `HavalH9Music-v<version>-<buildType>.apk` — собирается сразу
(`base.archivesName` в `app/build.gradle`; переименование не нужно).
Файловая диагностика удалена после исправления FLAC; **ошибки
воспроизведения показываются диалогом**, а не `logcat`.

## Карта кода

Все файлы — `app/src/main/java/com/tgraf/havalh9music/`. Номера строк ≈ актуальные,
используй как якоря, чтобы не читать файл целиком.

```
MainActivity.java         1342  UI: панели, разрешения, жесты, руль, EQ, настройки, сохранённые накопители
MusicService.java          163  MediaSessionService + ExoPlayer; статика playerRef/audioSessionId
ui/PlayerViewModel.java   2352  Навигация, очередь, shuffle, профили звука, восстановление, очистка тома
ui/TrackAdapter.java       195  Строки списка (папки + треки + CUE)
ui/BreadcrumbAdapter.java   75  Хлебные крошки (>6 уровней сворачиваются в VM)
ui/ListDividerDecoration.java 88  Разделители, выровненные по активной строке
util/LibraryCacheManager   704  Скан + запросы Room (3 пула), отмена по поколению, очистка тома
util/AudioTagParser        680  Свой парсер ID3/STREAMINFO/Vorbis (без MediaMetadataRetriever)
util/CueSheetParser        389  CUE, в т.ч. многодисковые; CP1251/UTF-16
util/EqualizerManager      317  6-полосный EQ; текущий тракт и именованные пресеты
util/SoundProfileStore     416  Общий звук, профили папок, реестр томов и владельцы кэша; отдельный IO
util/CoverArtLoader        178  Обложки: cover.jpg → cover.png → folder.jpg → встроенная
util/UsbStorageHelper      256  Поиск корней; старый UUID кэша и отдельный идентификатор профилей
util/SafeAudioRenderers…   124  Безопасный аудиовыход (16 бит + штатная частота) + выбор FLAC-декодера
util/SelectableFlacAudioRenderer 50  FLAC-рендерер: работает только при выборе libFLAC
util/FlacDecoderSettings   101  Выбор FLAC-декодера (libFLAC/системный), MediaCodecSelector
util/AppearanceSettings     54  Настройки оформления (порядок панелей, палитра)
util/EmbeddedArt           222  Проверка наличия встроенной обложки без getEmbeddedPicture()
util/LoudnessManager       144  LoudnessEnhancer на audio session; текущий тракт
util/UsbFailureSimulator   141  DEBUG: имитация потери флешки
util/UsbSpeedSimulator     200  DEBUG: имитация медленной флешки
model/{TrackEntity,RootEntity,LibraryRow,BreadcrumbLevel,UsbDevice}.java
model/SavedVolume.java      43  Снимок накопителя: идентификатор, название, старые пути/UUID кэша
data/{AppDatabase,TrackDao,RootDao}.java
```

Якоря в `PlayerViewModel` (самый важный файл):

```
L224  ticker      — раз в 500 мс: состояние/трек/профиль звука + привязка эффектов
L361  connect()   — MediaController к MusicService
L406  живой ExoPlayer — MusicService.getPlayerRef() (fallback: controller)
L564  startLibraryInit()  — ждёт загрузки профилей; 3 попытки нумерации томов, 700 мс
L682  onUsbMounted() / L697 onUsbUnmounted() / L718 unmountRecheck (3 × 500 мс)
L1117 loadRows()  — поколения navGen; здесь же скрывается заставка (L1138)
L1197 playRow() → L1234 buildAndPlay → L1275 playDir → L1294 startQueue
L1361 sequentialNext / L1383 sequentialPrev / L1409 playRandom
L1707 savePlaybackStateNow / L1752 restorePlaybackState
L1941 selectSoundForTrack — профиль по папке играющего трека, без записи общих настроек
L2052 clearAllFolderSounds / L2056 clearFolderSounds — очистка EQ всех/одного тома
L2083 loadSavedVolumes / L2131 forgetVolume — реестр и полная очистка данных тома
```

Другие якоря: `MainActivity.showSettingsDialog` — L159, `showSavedVolumesDialog` — L247,
`showEqualizerDialog` — L980; `LibraryCacheManager.deleteVolumeAsync` — L322.

## Инварианты — нарушение = регресс

1. **Команды и чтения идут в живой ExoPlayer, а не в MediaController.**
   На ГУ (API 28) `MediaController` теряет `play()` и отдаёт устаревшие значения
   (позиция 0, `isPlaying=false`). Поэтому тикер (L224) и чтения (L406) идут через
   `MusicService.getPlayerRef()`; контроллер — запасной путь.
   Не «упрощай» это обратно на `controller`.

2. **Repeat/shuffle у ExoPlayer всегда выключены** (`REPEAT_MODE_OFF`,
   `setShuffleModeEnabled(false)` в `connect()`). Свой shuffle ведёт ViewModel
   (`allTracks` + `shuffleHistory`). Иначе не придёт `STATE_ENDED` и автопереход
   встанет.

3. **EQ/loudness привязываются только из тикера (главный поток), не из
   `Player.Listener`.** Исключение на потоке воспроизведения роняет плеер
   (наблюдалось зависание в `state=2`). В тикере всё в `try/catch (Throwable)`.

4. **Никаких вызовов Room с главного потока.** `LibraryCacheManager` держит три
   разных пула: `parsePool` (разбор файлов), `scanExecutor` (скан),
   `dbExecutor` (запросы БД). Скан не блокирует навигацию по спискам.

5. **Асинхронные ответы отбрасываются по поколению** (`navGen` в `loadRows`,
   `dirLoadGen` для очереди). Новый вызов инкрементирует счётчик — колбэк сверяется
   и выходит, если устарел. Любой новый асинхронный запрос — по этому же принципу.

6. **`MediaMetadataRetriever` не используется для сканирования и чтения тегов**: на ГУ он тратит
   0,5–1,5 с на файл (скан 1800 файлов ≈ 20 мин вместо секунд) и спамит
   системный logcat ошибкой `getEmbeddedPicture`. Отсюда собственные парсеры:
   `AudioTagParser`, `EmbeddedArt`. Для загрузки подтверждённой встроенной обложки
   `CoverArtLoader` использует `MediaMetadataRetriever` вне главного потока.

7. **Кэш валиден, пока есть запись тома по `volumeUuid` с `complete = true`.**
   Содержимое флешки повторно не сверяется; перечитывание — только по кнопке
   «обновить». Запись инкрементальная: каждый каталог пишется сразу, поэтому
   прерванный скан не теряется. Схема Room v3, миграции destructive — версия
   в разработке, пересоздание кэша допустимо.
   Если старый ключ кэша зависит от общего пути монтирования, дополнительно
   сверяется его владелец в `SoundProfileStore`: разные UUID томов на одном
   `/mnt/usb_storage` не должны использовать чужую библиотеку.

8. **В release логи `Log.v/d/i/w` вырезаются R8** (`proguard-rules.pro`,
   `-assumenosideeffects`); `Log.e` остаются. Поэтому «молчание» в release —
   норма, а не баг. Логи Media3 глушатся в `MusicService.onCreate` вне DEBUG.
   Ошибки воспроизведения показываются диалогом; файловая диагностика удалена.

9. **Классы-симуляторы только для debug.** Они читают `Settings.Global`
   и компилируются под `BuildConfig.DEBUG`; R8 выкидывает их вместе с вызовами.

10. **Заставка скрывается в `loadRows` (L1138), а не отдельным колбэком** — из-за
    инвалидирования `navGen` при стартовых двух `openDir`. Не возвращать
    `-keep` для `MainActivity`/`PlayerViewModel` — такой keep уже был и был
    ошибочным (см. комментарий в `proguard-rules.pro`).

11. **FLAC-декодер выбирается только через `FlacDecoderSettings`.**
    `SafeAudioRenderersFactory` ставит `FlacDecoderSettings.selector(...)` и добавляет
    `SelectableFlacAudioRenderer` (наследник `LibflacAudioRenderer`; отдаёт
    `FORMAT_UNSUPPORTED_TYPE`, когда выбран системный кодек), а штатные расширения
    выключаются (`EXTENSION_RENDERER_MODE_OFF`) — иначе Media3 добавит второй
    FLAC-рендерер. В release R8 нужны `-keep` для `FlacDecoderJni`/`FlacLibrary`:
    JNI зовёт их по именам, и ошибка проявится только под R8.

12. **Общие настройки звука и применённый профиль — разные состояния.**
    `EqualizerManager`/`LoudnessManager` меняют текущий тракт; пользовательские
    изменения проходят через методы `PlayerViewModel` и сохраняются в нужную
    область `SoundProfileStore`. Не возвращать сохранение общих значений в
    `setBand`, `setGains`, `applyPreset`, `reset` или `setGainDb`: автоприменение
    альбома иначе перезапишет общий звук. Именованные пресеты EQ хранятся отдельно.
    Профиль выбирается по папке играющего трека, не по просматриваемой папке;
    без привязки восстанавливается общий звук. Наследования подпапками нет.

13. **Профили и реестр накопителей не входят в кэш Room.** SharedPreferences
    `folder_sound` хранят версионированные копии шести полос и усиления, реестр
    томов и владельцев кэша. Перечитывание, пересоздание Room и отключение
    флешки их не удаляют. Ключ профиля: `getSoundVolumeId(context, root)` +
    относительный нормализованный путь папки; компоненты кодируются отдельно.
    Не подменять его ID строки Room или абсолютным путём трека. Резервный
    `path:` без UUID зависит от точки монтирования — это ограничение показано
    пользователю. Старый `getVolumeUuid(root)` остаётся ключом кэша.
    Чтение/запись профилей идут через отдельный последовательный IO-поток;
    тикер пользуется памятью и ничего не сохраняет.

14. **Скан и полная очистка ограничены выбранным накопителем.** Не удалять
    устаревшие каталоги других томов при скане; shuffle получает только треки
    активного корня. Перед полной очисткой инвалидируются скан и загрузки,
    затем барьер `scanExecutor` дожидается последних записей. Room очищается
    транзакцией вне главного потока, после неё выполняются VACUUM и очистка WAL.
    Профили/реестр удаляются после зависимого состояния: при частичном сбое том
    остаётся доступен для повторной очистки. Учитывать владельца кэша и старые
    пути: забывание старой флешки не должно стереть новую на том же пути.
    Очистка активного источника останавливает плеер и возвращает общий звук;
    музыкальные файлы и данные других томов остаются. Уже подтверждённая
    очистка должна завершиться и при закрытии Activity между этапами.

## Отладка на ГУ

FLAC по умолчанию воспроизводится встроенной libFLAC. Выбор системного декодера,
обновление библиотеки, расположение панелей и палитра доступны в настройках.
В репозитории хранятся только JAR и JNI-бинарники; инструкция пересборки —
`docs/libFLAC-rebuild.md`.

В release в APK попадают только ARM-библиотеки libFLAC (`arm64-v8a`, `armeabi-v7a`),
в debug — все четыре ABI. Поэтому FLAC через libFLAC проверяй на ГУ или debug-сборкой:
release на x86-эмуляторе библиотеку не содержит.

Для ручных проверок использовать **`Haval_H9`** (Android 9 / API 28, 1920×1080),
а не `Medium_Phone`. При несовпадении подписей установленной release-версии и
debug APK сохранять данные эмулятора; не удалять установленное приложение
ради обновления без явного указания пользователя.

Правила профилей, виды очистки и проверенные сценарии описаны в
`docs/folder-sound-profiles.md`. «Сброс» обнуляет полосы текущей области и не
удаляет привязку; выключение переключателя «Сохранять настройки для папки»
удаляет профиль одной папки. В группе «Сохраненные настройки эквалайзера» кнопки
«Удалить на накопителе» и «Удалить все» удаляют только профили звука;
кэш, очередь, позиция и именованные пресеты сохраняются. Полная очистка
выбранного тома и обновление библиотеки находятся в отдельной группе
«Библиотека». Не объединять эти операции под одной кнопкой.

Имиляция проблем (только debug-сборка, пересборка не нужна):

```bash
adb shell settings put global haval_sim_usb 1        # ложный unmount (один раз)
adb shell settings put global haval_sim_usb 2        # пустая нумерация томов
adb shell settings put global haval_sim_usb 3        # устойчивая недоступность
adb shell settings put global haval_sim_usb 0        # выключить
adb shell settings put global haval_usb_sim_read_ms 2 # цена чтения/seek, мс
adb shell settings put global haval_usb_sim_dir_ms  1 # цена листинга каталога, мс
adb shell settings put global haval_usb_sim_bps 25000000
adb shell settings put global haval_usb_sim_open_ms 0 # всё в 0 — выключить
```

`UsbSpeedSimulator` сериализует задержки общим замком — суммарное время равно сумме
латентностей, а не делится на число потоков. Не меняй это на «параллельные» задержки:
так перестанет воспроизводиться реальное поведение флешки.

## Соглашения

- **Комментарии, Javadoc и тексты UI — по-русски.** Комментарии объясняют *почему*
  (со ссылкой на симптом на ГУ), а не пересказывают код. Именно поэтому в файлах
  столько длинных Javadoc — не сокращай их до «что делает».
- Пользовательские строки — в `res/values/strings.xml` (`getString(R.string.…)`).
- Локаль и регистр: `Locale` для пользовательского текста, `toLowerCase(Locale.…)`
  для сравнения имён файлов (кириллица).
- Размеры — только через `dimens.xml` (экран 1920×1080 @ ~151 dpi). Тёмная тема
  всегда, `android:supportsRtl="false"`, `windowFullscreen=true`.
- Отступы в Java — 4 пробела, ширина строки ~100, порядок полей:
  LiveData → пулы/зависимости → состояние → колбэки.
- Новый класс в `util/` — `final` + приватный конструктор, если это утилита.
- Каждый Java-исходник начинается с шапки LGPLv3 (`Copyright (C) 2026 TermoGRAF`);
  полный текст — `LICENSE-LGPLv3.txt` в корне. Шапку не удалять.
- Не должно быть однострочных циклов и блоков if. Если if не с блоком, то после него должна быть
  пустая строка.

## Экономия токенов

- Не читай `PlayerViewModel.java` целиком (2352 строки) — используй якоря выше
  и `grep` по имени метода.
- Не открывай `activity_main.xml` (большой) ради id: они доступны как
  `ActivityMainBinding` (`b.<id>`), список полей — из `viewBinding`.
- Только два теста: `CueSheetParserTest` — единственное место, где юнит-тесты
  оправданы. Для остального проверка = `assembleDebug` + ручной прогон.
- Файлы `build/`, `.gradle/`, `dist/` — не индексировать и не редактировать.
  `local.properties`, `app/keystore.properties` — локальные, в репозиторий не коммитятся.
- Комментарии в коде уже фиксируют «почему так» — прежде чем задавать вопрос или
  предлагать рефакторинг, проверь, не описана ли проблема в Javadoc рядом.
- Используй индексы codebase-memory-mcp
