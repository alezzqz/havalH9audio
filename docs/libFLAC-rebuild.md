# libFLAC: бинарники и повторная сборка

Обычная сборка приложения использует готовые файлы. NDK, CMake, Ninja,
исходники libFLAC и исходники модуля Media3 для неё не нужны.

В репозитории должны находиться:

- `app/libs/media3-flac-1.2.1.jar` — Java-мост Media3 к нативному декодеру;
- `app/src/main/jniLibs/arm64-v8a/libflacJNI.so` — ГУ ARM64;
- `app/src/main/jniLibs/armeabi-v7a/libflacJNI.so` — ГУ ARMv7;
- `app/src/main/jniLibs/x86/libflacJNI.so` — 32-битный эмулятор;
- `app/src/main/jniLibs/x86_64/libflacJNI.so` — 64-битный эмулятор;
- лицензии в `app/src/main/assets/licenses` и эта инструкция.

В release входят ARM64 и ARMv7, в debug — все четыре архитектуры.
Минимальный Android API — 28. Имя библиотеки должно оставаться `libflacJNI.so`.

## Версии и происхождение

| Компонент | Используемая версия |
|---|---|
| AndroidX Media3: Java-мост и JNI | 1.2.1 |
| libFLAC | 1.5.0 |
| Android NDK | r26b |
| Ninja | 1.12.1 |
| CMake | 3.21 или новее |
| Java для APK и Java-моста | JBR из Android Studio, target Java 11 |
| Нативный C++ runtime | `c++_static` |

Официальные исходники:

- [Модуль Media3 decoder_flac, тег 1.2.1](https://github.com/androidx/media/tree/1.2.1/libraries/decoder_flac).
- [Архив Media3 1.2.1](https://codeload.github.com/androidx/media/zip/refs/tags/1.2.1).
- [Архив libFLAC 1.5.0](https://ftp.osuosl.org/pub/xiph/releases/flac/flac-1.5.0.tar.xz).
- [NDK r26b для Windows](https://dl.google.com/android/repository/android-ndk-r26b-windows.zip).
- [Ninja 1.12.1 для Windows](https://github.com/ninja-build/ninja/releases/download/v1.12.1/ninja-win.zip).

Проверенные SHA256 исходных архивов:

```text
Media3 1.2.1 ZIP:
153954e704c07eef1d944b4cada9fff1d48fa06804bf7198f2a1590dbd2ec5a3

libFLAC 1.5.0 tar.xz:
f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920
```

Версия Java-моста должна совпадать с Media3 в `gradle/libs.versions.toml`.
При обновлении Media3 потребуется проверить и пересобрать Java-мост и JNI
совместно. Переименование пакета `androidx.media3.decoder.flac` недопустимо:
JNI обращается к Java-классам и методам по именам.

## 1. Подготовка на Windows

Команды ниже выполняются в PowerShell из корня проекта. Все временные файлы
размещаются в `.flac-rebuild`, которая исключена из Git.

```powershell
$flacProject = (Get-Location).Path
$flacWork = Join-Path $flacProject '.flac-rebuild'
New-Item -ItemType Directory -Force -Path $flacWork | Out-Null
$env:JAVA_HOME = "$env:ProgramFiles/Android/Android Studio/jbr"

Invoke-WebRequest 'https://codeload.github.com/androidx/media/zip/refs/tags/1.2.1' `
    -OutFile "$flacWork/media-1.2.1.zip"
Invoke-WebRequest 'https://ftp.osuosl.org/pub/xiph/releases/flac/flac-1.5.0.tar.xz' `
    -OutFile "$flacWork/flac-1.5.0.tar.xz"
Invoke-WebRequest 'https://dl.google.com/android/repository/android-ndk-r26b-windows.zip' `
    -OutFile "$flacWork/android-ndk-r26b-windows.zip"
Invoke-WebRequest 'https://github.com/ninja-build/ninja/releases/download/v1.12.1/ninja-win.zip' `
    -OutFile "$flacWork/ninja-win.zip"

$flacExpected = @{
    'media-1.2.1.zip' = '153954e704c07eef1d944b4cada9fff1d48fa06804bf7198f2a1590dbd2ec5a3'
    'flac-1.5.0.tar.xz' = 'f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920'
}
foreach ($flacArchive in $flacExpected.Keys) {
    $flacActual = (Get-FileHash "$flacWork/$flacArchive" -Algorithm SHA256).Hash
    if ($flacActual -ne $flacExpected[$flacArchive]) {
        throw "Контрольная сумма не совпала: $flacArchive"
    }
}

Expand-Archive "$flacWork/media-1.2.1.zip" -DestinationPath $flacWork
Expand-Archive "$flacWork/android-ndk-r26b-windows.zip" -DestinationPath $flacWork
Expand-Archive "$flacWork/ninja-win.zip" -DestinationPath "$flacWork/ninja"
tar -xf "$flacWork/flac-1.5.0.tar.xz" -C $flacWork
if ($LASTEXITCODE -ne 0) { throw 'Не удалось распаковать libFLAC' }

$flacNdk = "$flacWork/android-ndk-r26b"
$flacSource = "$flacWork/flac-1.5.0"
$flacMedia3 = "$flacWork/media-1.2.1/libraries/decoder_flac/src/main"
$flacNinja = "$flacWork/ninja/ninja.exe"
```

Если системный `tar` не поддерживает XZ, распакуйте архив через 7-Zip в два
шага: сначала `.tar.xz` → `.tar`, затем `.tar` → каталог `flac-1.5.0`.
Повторное выполнение `Expand-Archive` требует пустой папки назначения
либо явного `-Force`.

Установите CMake через Android Studio → SDK Manager → SDK Tools → CMake.
Укажите фактический путь к `cmake.exe`; например, для установленной версии 3.22.1:

```powershell
$flacCmake = "$env:LOCALAPPDATA/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
& $flacCmake --version
& $flacNinja --version
```

Допустим и отдельно установленный CMake 3.21+. Проверьте, что путь соответствует
вашей установке SDK: он может отличаться от примера.

## 2. Подготовка JNI

Используются четыре неизменённых файла официального модуля Media3:

```text
src/main/jni/flac_jni.cc
src/main/jni/flac_parser.cc
src/main/jni/include/data_source.h
src/main/jni/include/flac_parser.h
```

Штатные `Android.mk`, `Application.mk` и `flac_sources.mk` не нужны: сборку
выполняет собственный CMake-файл из следующего раздела.

```powershell
$flacJni = "$flacWork/jni"
New-Item -ItemType Directory -Force -Path "$flacJni/include" | Out-Null
Copy-Item "$flacMedia3/jni/flac_jni.cc" "$flacJni/flac_jni.cc"
Copy-Item "$flacMedia3/jni/flac_parser.cc" "$flacJni/flac_parser.cc"
Copy-Item "$flacMedia3/jni/include/data_source.h" "$flacJni/include/data_source.h"
Copy-Item "$flacMedia3/jni/include/flac_parser.h" "$flacJni/include/flac_parser.h"
```

Создайте `.flac-rebuild/jni/CMakeLists.txt` с точным содержимым:

```cmake
cmake_minimum_required(VERSION 3.21)
project(haval_flac_jni LANGUAGES C CXX)
# Legacy toolchain NDK сообщает CMAKE_SYSTEM_VERSION=1 вместо API 28.
# Явное значение и HAVE_FSEEKO предотвращают ошибку конфигурации ARMv7.
set(CMAKE_SYSTEM_VERSION 28)
set(HAVE_FSEEKO 1 CACHE INTERNAL "API 28 provides fseeko" FORCE)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(INSTALL_PKGCONFIG_MODULES OFF CACHE BOOL "" FORCE)
add_subdirectory("${FLAC_SOURCE}" flac)
add_library(flacJNI SHARED flac_jni.cc flac_parser.cc)
target_include_directories(flacJNI PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}" "${FLAC_SOURCE}/include")
target_compile_options(flacJNI PRIVATE -O2 -ffunction-sections -fdata-sections)
target_link_options(flacJNI PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(flacJNI PRIVATE FLAC log z m)
```

## 3. Сборка нативных библиотек

```powershell
$flacNdk = $flacNdk.Replace('\', '/')
$flacSource = $flacSource.Replace('\', '/')
$flacNinja = $flacNinja.Replace('\', '/')
foreach ($flacAbi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
    $flacBuild = "$flacWork/build-$flacAbi"
    & $flacCmake -S $flacJni -B $flacBuild -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$flacNinja" `
        "-DCMAKE_TOOLCHAIN_FILE=$flacNdk/build/cmake/android.toolchain.cmake" `
        "-DANDROID_ABI=$flacAbi" -DANDROID_PLATFORM=android-28 `
        -DCMAKE_SYSTEM_VERSION=28 -DANDROID_STL=c++_static `
        -DCMAKE_BUILD_TYPE=Release "-DFLAC_SOURCE=$flacSource"
    if ($LASTEXITCODE -ne 0) { throw "Ошибка конфигурации $flacAbi" }
    & $flacCmake --build $flacBuild --target flacJNI -j 6
    if ($LASTEXITCODE -ne 0) { throw "Ошибка сборки $flacAbi" }

    $flacTarget = "$flacProject/app/src/main/jniLibs/$flacAbi"
    New-Item -ItemType Directory -Force -Path $flacTarget | Out-Null
    Copy-Item "$flacBuild/libflacJNI.so" "$flacTarget/libflacJNI.so"
    & "$flacNdk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" `
        --strip-unneeded "$flacTarget/libflacJNI.so"
    if ($LASTEXITCODE -ne 0) { throw "Ошибка strip $flacAbi" }
    Get-FileHash "$flacTarget/libflacJNI.so" -Algorithm SHA256
}
```

libFLAC статически включена в `libflacJNI.so`: отдельных `libFLAC.so` и
`libc++_shared.so` в APK не требуется. Параметр `max-page-size=16384`
задаёт выравнивание ELF-сегментов. Он сам по себе не подтверждает совместимость
всего приложения с устройствами с размером страницы 16 КБ.

## 4. Пересборка Java-моста

Этот раздел нужен при изменении Java-моста или версии Media3. Если пересобирается
только libFLAC 1.5.0 с прежним JNI и Media3 1.2.1, существующий JAR можно сохранить.

Используются **только пять** официальных Java-файлов:

```text
FlacDecoder.java
FlacDecoderException.java
FlacDecoderJni.java
FlacLibrary.java
LibflacAudioRenderer.java
```

Не включайте `FlacExtractor` и `FlacBinarySearchSeeker`: штатный Java-extractor
Media3 уже разбирает контейнер, а нативный extractor превращает FLAC в PCM до
выбора рендерера и мешает переключению между системным декодером и libFLAC.

```powershell
$flacJavaRoot = "$flacWork/media3-flac-java"
$flacJavaPackage = "$flacJavaRoot/androidx/media3/decoder/flac"
New-Item -ItemType Directory -Force -Path $flacJavaPackage | Out-Null
foreach ($flacJavaFile in @('FlacDecoder.java', 'FlacDecoderException.java',
        'FlacDecoderJni.java', 'FlacLibrary.java', 'LibflacAudioRenderer.java')) {
    Copy-Item "$flacMedia3/java/androidx/media3/decoder/flac/$flacJavaFile" `
        "$flacJavaPackage/$flacJavaFile"
}
$flacRenderer = "$flacJavaPackage/LibflacAudioRenderer.java"
$flacJavaText = [IO.File]::ReadAllText($flacRenderer)
if (-not $flacJavaText.Contains('public final class LibflacAudioRenderer')) {
    throw 'Исходный класс отличается от ожидаемой версии Media3 1.2.1'
}
$flacJavaText = $flacJavaText.Replace('public final class LibflacAudioRenderer',
    'public class LibflacAudioRenderer')
[IO.File]::WriteAllText($flacRenderer, $flacJavaText, [Text.UTF8Encoding]::new($false))
```

Снятие `final` обязательно: класс приложения `SelectableFlacAudioRenderer`
наследуется от `LibflacAudioRenderer`, чтобы учитывать выбор кодека в настройках.
Других изменений официальных Java-файлов в текущих бинарниках нет.

**Временно** измените `app/build.gradle`:

1. Закомментируйте `implementation files('libs/media3-flac-1.2.1.jar')`.
2. Добавьте внутрь блока `android { ... }`:

```groovy
sourceSets.main.java.srcDir rootProject.file('.flac-rebuild/media3-flac-java')
```

Не оставляйте одновременно JAR и эти исходники: они определяют одинаковые классы.
Соберите debug, чтобы Java-мост компилировался с теми же зависимостями и Java 11,
что и приложение:

```powershell
./gradlew.bat :app:clean :app:assembleDebug --offline
if ($LASTEXITCODE -ne 0) { throw 'Не удалось скомпилировать Java-мост' }
$flacClasses = "$flacProject/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
$flacClassPackage = "$flacClasses/androidx/media3/decoder/flac"
if (-not (Test-Path "$flacClassPackage/FlacDecoderJni.class")) {
    throw 'Классы не найдены: проверьте путь intermediates для вашей версии AGP'
}
$flacJarMeta = "$flacWork/jar-meta"
New-Item -ItemType Directory -Force -Path "$flacJarMeta/META-INF/licenses" | Out-Null
Copy-Item "$flacProject/app/src/main/assets/licenses/LICENSE-Media3.txt" `
    "$flacJarMeta/META-INF/licenses/"
Copy-Item "$flacProject/app/src/main/assets/licenses/LICENSE-libFLAC.txt" `
    "$flacJarMeta/META-INF/licenses/"
& "$env:JAVA_HOME/bin/jar.exe" --create `
    --file "$flacProject/app/libs/media3-flac-1.2.1.jar" `
    -C $flacClasses androidx/media3/decoder/flac -C $flacJarMeta META-INF
if ($LASTEXITCODE -ne 0) { throw 'Не удалось упаковать JAR' }
& "$env:JAVA_HOME/bin/jar.exe" --list --file "$flacProject/app/libs/media3-flac-1.2.1.jar"
```

В текущем JAR семь `.class`: пять основных классов, вложенный
`FlacDecoderJni$FlacFrameDecodeException` и анонимный `FlacLibrary$1`.
Они должны попасть в JAR вместе. При изменении версии набор вложенных классов
может измениться: упаковывайте весь каталог пакета, а не только пять `.class`.

После упаковки **верните `app/build.gradle` в обычное состояние**: удалите
временный `sourceSets` и восстановите `implementation files(...)`.
Исходники из `.flac-rebuild` в репозиторий не добавляйте.

## 5. Проверка и публикация бинарников

```powershell
./gradlew.bat :app:clean :app:assembleDebug :app:assembleRelease --offline
```

Для `--offline` зависимости должны быть уже загружены Gradle. Если это новая
рабочая машина, первую сборку выполните без `--offline`. Release требует
локальные `app/keystore.properties` и ключ подписи, которые не коммитятся.

Без установленного NDK Gradle может вывести `Unable to strip ... libflacJNI.so`
и упаковать библиотеку без изменений. Поставляемые `.so` уже обработаны
`llvm-strip` при нативной сборке; повторная обработка при сборке APK не нужна.
Чистая сборка проекта без локального NDK для libFLAC проверена.

Не удаляйте существующие правила `app/proguard-rules.pro` для:

```text
androidx.media3.decoder.flac.FlacDecoderJni
androidx.media3.decoder.flac.FlacLibrary
androidx.media3.extractor.FlacStreamMetadata
androidx.media3.extractor.metadata.flac.PictureFrame
```

JNI вызывает их по именам; ошибка может проявиться только в release с R8.

Проверьте APK как ZIP: в debug должны находиться четыре
`lib/<ABI>/libflacJNI.so`, в release — две ARM-библиотеки. Java-мост преобразуется
в DEX вместе с приложением: отдельный JAR внутри APK искать не требуется.

Ручная проверка:

- эмулятор Haval H9, Android 9: libFLAC присутствует в настройках;
- FLAC 24 бит / 96 кГц и 192 кГц: звук есть, позиция движется;
- MP3: воспроизводится системным декодером;
- переключение libFLAC ↔ системный FLAC-кодек: выбор применяется и сохраняется;
- ARM-ГУ: проверка debug и release, включая эквалайзер и переход между треками.

В репозиторий добавляйте обновлённый JAR, четыре `.so`, лицензии и актуальные
контрольные суммы. После проверки временный каталог `.flac-rebuild` можно удалить.
Перед рекурсивным удалением проверьте его абсолютный путь: это должна быть именно
временная папка внутри проекта, а не общий Android SDK или установленный NDK.

## Контрольные суммы поставляемых бинарников

SHA256 на момент перехода на готовые бинарники:

| Файл | SHA256 |
|---|---|
| `app/libs/media3-flac-1.2.1.jar` | `ffeb1e9a0b88742c1b16c63a19574ed18c559e3715b0675c926bd31bf7ec2187` |
| `arm64-v8a/libflacJNI.so` | `cd8967d7bf4955d72ba4fbc5658eb6bca8309433cca80e561bebb6c85d9a2990` |
| `armeabi-v7a/libflacJNI.so` | `9a7c8592bf7be2b32bbc842f5cacdc04679141ef0543682f5dc514c828ff0a77` |
| `x86/libflacJNI.so` | `fa30a15488fcf7486b96bf81e59661b6ec5602a288b1d04ee82c3e08ea2b7993` |
| `x86_64/libflacJNI.so` | `ed3c9de84adbdb3fb78c9eb5b4ceff545b71da6e5efc456cc6ab249489c373a0` |

Пути ABI в таблице относятся к `app/src/main/jniLibs`.
Повторная сборка может дать другие SHA256 из-за версий инструментов и метаданных
JAR; совпадение версий и функциональная проверка важнее побайтового совпадения.
При замене бинарников обновите таблицу.
