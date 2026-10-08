package com.tgraf.havalh9music;

import com.tgraf.havalh9music.util.CueSheetParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Юнит-тесты парсера CUE: многодисковые, CP1251, битый файл.
 */
public class CueSheetParserTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private void writeCue(File file, String content, String charset) throws Exception {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), Charset.forName(charset))) {
            w.write(content);
        }
    }

    @Test
    public void multiFileCue_parsesVirtualTracks() throws Exception {
        File cue = folder.newFile("album.cue");
        writeCue(cue,
                "REM GENRE \"Rock\"\n"
                        + "REM DATE \"1987\"\n"
                        + "PERFORMER \"Artist\"\n"
                        + "TITLE \"Album\"\n"
                        + "FILE \"cd1.flac\" WAVE\n"
                        + "  TRACK 01 AUDIO\n"
                        + "    TITLE \"Track One\"\n"
                        + "    INDEX 01 00:00:00\n"
                        + "  TRACK 02 AUDIO\n"
                        + "    TITLE \"Track Two\"\n"
                        + "    INDEX 01 04:15:33\n"
                        + "FILE \"cd2.flac\" WAVE\n"
                        + "  TRACK 01 AUDIO\n"
                        + "    TITLE \"Track Three\"\n"
                        + "    INDEX 01 00:00:00\n",
                "UTF-8");

        List<CueSheetParser.CueTrack> tracks = CueSheetParser.parse(cue);
        assertEquals(3, tracks.size());

        // Трек 1: cd1.flac, старт 0, конец = старт трека 2 (255440 мс)
        assertEquals("cd1.flac", new File(tracks.get(0).sourceFile).getName());
        assertEquals(0L, tracks.get(0).startMs);
        assertEquals(255440L, tracks.get(1).startMs);
        assertEquals(255440L, tracks.get(0).endMs);

        // Трек 2: диск 1, второй FILE-блок — диск 2
        assertEquals(1, tracks.get(0).diskIndex);
        assertEquals(2, tracks.get(2).diskIndex);

        // Трек 3 на cd2.flac, до конца файла
        assertEquals("cd2.flac", new File(tracks.get(2).sourceFile).getName());
        assertEquals(-1L, tracks.get(2).endMs);

        // Метаданные
        assertEquals("Track One", tracks.get(0).title);
        assertEquals("Artist", tracks.get(0).performer);
        assertEquals("Rock", tracks.get(0).genre);
        assertEquals("1987", tracks.get(0).date);

        // Привязка CUE к FLAC
        assertTrue(tracks.get(0).cuePath != null);
    }

    @Test
    public void cp1251_fallback_decodesTitles() throws Exception {
        File cue = folder.newFile("ru.cue");
        // «Песня» в CP1251
        String title = "\u041F\u0435\u0441\u043D\u044F";
        writeCue(cue,
                "PERFORMER \"\u0418\u0441\u043F\u043E\u043B\u043D\u0438\u0442\u0435\u043B\u044C\"\n"
                        + "FILE \"song.flac\" WAVE\n"
                        + "  TRACK 01 AUDIO\n"
                        + "    TITLE \"" + title + "\"\n"
                        + "    INDEX 01 00:00:00\n",
                "windows-1251");

        List<CueSheetParser.CueTrack> tracks = CueSheetParser.parse(cue);
        assertEquals(1, tracks.size());
        assertEquals(title, tracks.get(0).title);
    }

    @Test
    public void brokenCue_throws_parseException() throws Exception {
        File cue = folder.newFile("broken.cue");
        writeCue(cue, "THIS IS NOT A CUE FILE\nNOTHING USEFUL HERE\n", "UTF-8");

        try {
            CueSheetParser.parse(cue);
            fail("Ожидали CueParseException");
        } catch (CueSheetParser.CueParseException expected) {
            // ок
        }
    }

    @Test
    public void msf_conversion_isCorrect() throws Exception {
        File cue = folder.newFile("msf.cue");
        writeCue(cue,
                "FILE \"a.flac\" WAVE\n"
                        + "  TRACK 01 AUDIO\n"
                        + "    INDEX 01 01:02:03\n"
                        + "  TRACK 02 AUDIO\n"
                        + "    INDEX 01 02:00:00\n",
                "UTF-8");
        List<CueSheetParser.CueTrack> tracks = CueSheetParser.parse(cue);
        // 01:02:03 = (62*75+3) = 4653 frames → 62040 мс
        assertEquals(62040L, tracks.get(0).startMs);
        assertEquals(120000L, tracks.get(1).startMs);
        assertEquals(120000L, tracks.get(0).endMs);
    }

    @Test
    public void index02_03_and_pregap_doNotBreakParse() throws Exception {
        // Реальный EAC-рип: INDEX 02 у трека, INDEX 00 (pregap) у следующего.
        File cue = folder.newFile("hidden.cue");
        writeCue(cue,
                "FILE \"album.flac\" WAVE\n"
                        + "  TRACK 01 AUDIO\n"
                        + "    TITLE \"One\"\n"
                        + "    INDEX 01 00:00:00\n"
                        + "  TRACK 02 AUDIO\n"
                        + "    TITLE \"Two\"\n"
                        + "    INDEX 01 04:00:00\n"
                        + "    INDEX 02 04:30:00\n"
                        + "  TRACK 03 AUDIO\n"
                        + "    TITLE \"Three\"\n"
                        + "    INDEX 00 08:00:00\n"
                        + "    INDEX 01 08:01:00\n",
                "UTF-8");
        List<CueSheetParser.CueTrack> tracks = CueSheetParser.parse(cue);
        assertEquals(3, tracks.size());
        // INDEX 02 не роняет парсер; endMs перекрывается стартом следующего трека
        assertEquals(240000L, tracks.get(1).startMs);
        assertEquals(481000L, tracks.get(1).endMs);
        // INDEX 00 (pregap) игнорируется — стартом служит INDEX 01
        assertEquals(481000L, tracks.get(2).startMs);
    }
}