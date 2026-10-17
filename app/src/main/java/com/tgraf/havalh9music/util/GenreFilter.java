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

import com.tgraf.havalh9music.model.TrackEntity;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GenreFilter {
    public static final String UNTAGGED = "";
    private static final String[] ID3_GENRES = ("Blues|Classic Rock|Country|Dance|Disco|Funk|"
            + "Grunge|Hip-Hop|Jazz|Metal|New Age|Oldies|Other|Pop|R&B|Rap|Reggae|Rock|Techno|"
            + "Industrial|Alternative|Ska|Death Metal|Pranks|Soundtrack|Euro-Techno|Ambient|"
            + "Trip-Hop|Vocal|Jazz+Funk|Fusion|Trance|Classical|Instrumental|Acid|House|Game|"
            + "Sound Clip|Gospel|Noise|AlternRock|Bass|Soul|Punk|Space|Meditative|Instrumental Pop|"
            + "Instrumental Rock|Ethnic|Gothic|Darkwave|Techno-Industrial|Electronic|Pop-Folk|"
            + "Eurodance|Dream|Southern Rock|Comedy|Cult|Gangsta|Top 40|Christian Rap|Pop/Funk|"
            + "Jungle|Native American|Cabaret|New Wave|Psychadelic|Rave|Showtunes|Trailer|Lo-Fi|"
            + "Tribal|Acid Punk|Acid Jazz|Polka|Retro|Musical|Rock & Roll|Hard Rock|Folk|Folk-Rock|"
            + "National Folk|Swing|Fast Fusion|Bebob|Latin|Revival|Celtic|Bluegrass|Avantgarde|"
            + "Gothic Rock|Progressive Rock|Psychedelic Rock|Symphonic Rock|Slow Rock|Big Band|"
            + "Chorus|Easy Listening|Acoustic|Humour|Speech|Chanson|Opera|Chamber Music|Sonata|"
            + "Symphony|Booty Bass|Primus|Porn Groove|Satire|Slow Jam|Club|Tango|Samba|Folklore|"
            + "Ballad|Power Ballad|Rhythmic Soul|Freestyle|Duet|Punk Rock|Drum Solo|A capella|"
            + "Euro-House|Dance Hall|Goa|Drum & Bass|Club-House|Hardcore|Terror|Indie|BritPop|"
            + "Negerpunk|Polsk Punk|Beat|Christian Gangsta Rap|Heavy Metal|Black Metal|Crossover|"
            + "Contemporary Christian|Christian Rock|Merengue|Salsa|Thrash Metal|Anime|JPop|"
            + "SynthPop").split("\\|");

    private GenreFilter() {
    }

    public static String id3Name(int number) {
        return number >= 0 && number < ID3_GENRES.length ? ID3_GENRES[number] : null;
    }

    private static String numericName(String value) {
        return value.matches("[0-9]{1,3}") ? id3Name(Integer.parseInt(value)) : null;
    }

    public static String fromId3(String raw) {
        List<String> names = new ArrayList<>();
        for (String part : raw.split("[\\u0000;]")) {
            String value = part.trim();
            while (value.startsWith("(") && !value.startsWith("((")) {
                int end = value.indexOf(')');
                if (end < 0) {
                    break;
                }
                String code = value.substring(1, end);
                String name = numericName(code);
                if ("RX".equals(code)) {
                    name = "Remix";
                } else if ("CR".equals(code)) {
                    name = "Cover";
                }
                if (name == null) {
                    break;
                }
                names.add(name);
                value = value.substring(end + 1).trim();
            }
            if (value.startsWith("((")) {
                value = value.substring(1);
            }
            if (!value.isEmpty()) {
                String name = numericName(value);
                names.add(name == null ? value : name);
            }
        }
        return canonical(String.join(";", names));
    }

    public static Map<String, String> values(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw != null) {
            for (String part : raw.split("[\\u0000;]")) {
                String name = part.replaceAll("\\s+", " ").trim();
                if (!name.isEmpty()) {
                    result.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        return result;
    }

    public static String canonical(String raw) {
        return String.join(";", values(raw).values());
    }

    public static String merge(String first, String second) {
        return canonical((first == null ? "" : first) + ";" + (second == null ? "" : second));
    }

    public static boolean matches(String genre, Set<String> selected) {
        if (selected == null) {
            return true;
        }
        return matchesKeys(values(genre).keySet(), selected);
    }

    private static boolean matchesKeys(Set<String> keys, Set<String> selected) {
        if (selected == null) {
            return true;
        }
        if (keys.isEmpty()) {
            return selected.contains(UNTAGGED);
        }
        for (String key : keys) {
            if (selected.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public static final class Option {
        public final String key;
        public final String name;
        public final int count;

        private Option(String key, String name, int count) {
            this.key = key;
            this.name = name;
            this.count = count;
        }
    }

    public static final class Catalog {
        public final List<Option> options;
        public final int total;
        private final List<TrackEntity> tracks;
        private final List<Set<String>> trackGenres = new ArrayList<>();

        public Catalog(List<TrackEntity> source) {
            tracks = new ArrayList<>(source);
            total = tracks.size();
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (TrackEntity track : tracks) {
                Map<String, String> genres = values(track.genre);
                trackGenres.add(new LinkedHashSet<>(genres.keySet()));
                if (genres.isEmpty()) {
                    genres.put(UNTAGGED, UNTAGGED);
                }
                for (Map.Entry<String, String> genre : genres.entrySet()) {
                    names.putIfAbsent(genre.getKey(), genre.getValue());
                    counts.put(genre.getKey(), countFor(counts, genre.getKey()) + 1);
                }
            }
            List<Option> result = new ArrayList<>();
            for (String key : names.keySet()) {
                if (!UNTAGGED.equals(key)) {
                    result.add(new Option(key, names.get(key), countFor(counts, key)));
                }
            }
            Collator collator = Collator.getInstance(Locale.getDefault());
            result.sort((first, second) -> collator.compare(first.name, second.name));
            result.add(new Option(UNTAGGED, UNTAGGED, countFor(counts, UNTAGGED)));
            options = Collections.unmodifiableList(result);
        }

        private static int countFor(Map<String, Integer> counts, String key) {
            Integer value = counts.get(key);
            return value == null ? 0 : value;
        }

        public List<TrackEntity> matching(Set<String> selected) {
            List<TrackEntity> result = new ArrayList<>();
            for (int index = 0; index < tracks.size(); index++) {
                if (matchesKeys(trackGenres.get(index), selected)) {
                    result.add(tracks.get(index));
                }
            }
            return result;
        }

        public int count(Set<String> selected) {
            int result = 0;
            for (Set<String> keys : trackGenres) {
                if (matchesKeys(keys, selected)) {
                    result++;
                }
            }
            return result;
        }

        public Set<String> keys() {
            Set<String> result = new LinkedHashSet<>();
            for (Option option : options) {
                result.add(option.key);
            }
            return result;
        }
    }
}
