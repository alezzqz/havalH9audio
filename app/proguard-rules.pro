# Правила потребительских библиотек (Media3, Room, Material) поставляются в AAR автоматически.
# Дополнительные keep-правила для нашего кода и Reflection:

# Release: вырезаем логи приложения (v/d/i/w) — R8 удаляет вызовы
# из DEX, аргументы не вычисляются. Ошибки (e) оставляем — они редки и полезны.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# ПРИМЕЧАНИЕ: ранее здесь были -keep для MainActivity/PlayerViewModel —
# ошибочно принятые за лекарство от «залипшей» заставки. Настоящая причина
# была в гонке navGen в PlayerViewModel.loadRows (заставка скрывается теперь
# прямо там), поэтому принудительный keep не нужен — R8 оптимизирует код.

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Room: аннотированные DAO и сущности
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class * extends androidx.room.RoomDatabase { *; }

# Media3 / ExoPlayer держит ссылки на SessionToken через рефлексию
-keep class androidx.media3.session.** { *; }

-dontwarn javax.annotation.**

# JNI вызывает эти классы/методы по именам; debug без R8 эту ошибку не обнаружит.
-keep class androidx.media3.decoder.flac.FlacDecoderJni { *; }
-keep class androidx.media3.decoder.flac.FlacLibrary { *; }
-keep class androidx.media3.extractor.FlacStreamMetadata { *; }
-keep class androidx.media3.extractor.metadata.flac.PictureFrame { *; }
