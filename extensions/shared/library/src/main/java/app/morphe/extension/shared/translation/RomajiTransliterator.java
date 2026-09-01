/*
 * Copyright 2026 Morphe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.morphe.extension.shared.translation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.Logger;

/**
 * Fast, offline Japanese to Romaji transliterator for lyrics and subtitles.
 * Supports Kanji (Joyo & common compounds), Hiragana, and Katakana using the Hepburn romanization system.
 */
public final class RomajiTransliterator {

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized = false;

    private static final Map<String, String> COMPOUNDS = new HashMap<>(28000);
    private static final Map<Character, String[]> KANJI_TABLE = new HashMap<>(3500);

    private static final Map<String, String> KANA_PAIRS = new HashMap<>(128);
    private static final Map<Character, String> KANA_SINGLE = new HashMap<>(256);

    private static final String[] PARTICLES = new String[] {
            "から", "まで", "より", "など", "は", "が", "を", "に", "へ", "で", "と", "の", "な", "ね", "よ", "か"
    };

    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile(" +");

    static {
        initKanaTables();
    }

    private RomajiTransliterator() {
    }

    /**
     * Checks if the character is within the Unicode CJK unified ideograph ranges.
     */
    public static boolean isKanji(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /**
     * Checks if the character is a Hiragana character.
     */
    public static boolean isHiragana(char c) {
        return c >= 0x3040 && c <= 0x309F;
    }

    /**
     * Checks if the character is a Katakana character (including half-width).
     */
    public static boolean isKatakana(char c) {
        return (c >= 0x30A0 && c <= 0x30FF) || (c >= 0xFF65 && c <= 0xFF9F);
    }

    /**
     * Checks if the character is Japanese (Kanji, Hiragana, Katakana, or long vowel mark).
     */
    public static boolean isJapanese(char c) {
        return isKanji(c) || isHiragana(c) || isKatakana(c) || c == 'ー' || c == '〜' || c == '～';
    }

    /**
     * Checks if the provided text contains any Japanese characters.
     */
    public static boolean containsJapanese(@Nullable CharSequence text) {
        if (text == null) {
            return false;
        }
        for (int i = 0, len = text.length(); i < len; i++) {
            if (isJapanese(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if any line in the given list contains Japanese characters.
     */
    public static boolean hasJapaneseLines(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (String line : lines) {
            if (containsJapanese(line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensures that the offline dictionary data is loaded into memory.
     */
    public static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            try {
                long startTime = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder(150000);
                for (String chunk : RomajiDictionaryData.DATA_CHUNKS) {
                    sb.append(chunk);
                }
                byte[] decoded = java.util.Base64.getDecoder().decode(sb.toString());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(new ByteArrayInputStream(decoded)), StandardCharsets.UTF_8))) {
                    String line;
                    boolean inKanji = false;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("---")) {
                            inKanji = true;
                            continue;
                        }
                        int eqIdx = line.indexOf('=');
                        if (eqIdx <= 0) {
                            continue;
                        }
                        String key = line.substring(0, eqIdx);
                        String val = line.substring(eqIdx + 1);
                        if (!inKanji) {
                            COMPOUNDS.put(key, val);
                        } else {
                            String[] parts = val.split(",", -1);
                            KANJI_TABLE.put(key.charAt(0), parts);
                        }
                    }
                }
                initialized = true;
                Logger.printDebug(() -> "RomajiTransliterator initialized with "
                        + COMPOUNDS.size() + " compounds and " + KANJI_TABLE.size()
                        + " kanji in " + (System.currentTimeMillis() - startTime) + "ms");
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to initialize RomajiTransliterator dictionary", ex);
                initialized = true; // Avoid re-attempting indefinitely on failure
            }
        }
    }

    /**
     * Translates a single line of Japanese lyrics (Kanji, Hiragana, Katakana) to Romaji.
     */
    @NonNull
    public static String toRomaji(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        if (!containsJapanese(text)) {
            return text;
        }

        ensureInitialized();

        final int len = text.length();
        StringBuilder kanaBuilder = new StringBuilder(len * 2);
        int i = 0;

        while (i < len) {
            char c = text.charAt(i);

            // 1. Try matching multi-character compound word from dictionary (longest match first)
            boolean matched = false;
            int maxLookahead = Math.min(10, len - i);
            for (int l = maxLookahead; l >= 1; l--) {
                String sub = text.substring(i, i + l);
                String reading = COMPOUNDS.get(sub);
                if (reading != null) {
                    kanaBuilder.append(' ').append(reading).append(' ');
                    i += l;
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }

            // 2. Try single Kanji character
            if (isKanji(c)) {
                String[] info = KANJI_TABLE.get(c);
                if (info != null) {
                    String on = info.length > 0 ? info[0] : "";
                    String kun = info.length > 1 ? info[1] : "";
                    String stem = info.length > 2 ? info[2] : "";

                    boolean isFollowedByKana = (i + 1 < len && (isHiragana(text.charAt(i + 1)) || isKatakana(text.charAt(i + 1))));
                    boolean isFollowedByKanji = (i + 1 < len && isKanji(text.charAt(i + 1)));
                    boolean isPrecededByKanji = (i > 0 && isKanji(text.charAt(i - 1)));

                    String val;
                    if (isFollowedByKana) {
                        val = !stem.isEmpty() ? stem : (!kun.isEmpty() ? kun : on);
                        kanaBuilder.append(' ').append(val);
                    } else if (isFollowedByKanji || isPrecededByKanji) {
                        val = !on.isEmpty() ? on : (!kun.isEmpty() ? kun : stem);
                        kanaBuilder.append(val);
                    } else {
                        val = !kun.isEmpty() ? kun : (!on.isEmpty() ? on : stem);
                        kanaBuilder.append(' ').append(val).append(' ');
                    }
                } else {
                    kanaBuilder.append(c);
                }
                i++;
                continue;
            }

            // 3. Normal Kana / punctuation / ASCII
            kanaBuilder.append(c);
            i++;
        }

        String intermediate = kanaBuilder.toString();

        // Ensure natural spacing around Japanese grammatical particles
        for (String p : PARTICLES) {
            intermediate = intermediate.replace(p + " ", " " + p + " ");
        }

        // Convert Kana and punctuation to Romaji
        String romaji = convertKanaToRomaji(intermediate);

        // Normalize spaces
        romaji = MULTI_SPACE_PATTERN.matcher(romaji).replaceAll(" ").trim();

        // Capitalize first character of the line
        if (!romaji.isEmpty()) {
            char first = romaji.charAt(0);
            if (Character.isLowerCase(first)) {
                romaji = Character.toUpperCase(first) + romaji.substring(1);
            }
        }

        return romaji;
    }

    /**
     * Translates a list of lyrics lines to Romaji.
     * Empty lines or instrumental breaks are preserved.
     */
    @NonNull
    public static List<String> transliterateLines(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                result.add("");
            } else {
                result.add(toRomaji(line));
            }
        }
        return result;
    }

    @NonNull
    private static String convertKanaToRomaji(String text) {
        final int len = text.length();
        StringBuilder res = new StringBuilder(len * 2);
        int i = 0;

        while (i < len) {
            char c = text.charAt(i);

            // Sokuon (っ / ッ)
            if (c == 'っ' || c == 'ッ') {
                if (i + 1 < len) {
                    String nextRomaji = convertKanaToRomaji(text.substring(i + 1));
                    if (!nextRomaji.isEmpty()) {
                        char lead = Character.toLowerCase(nextRomaji.charAt(0));
                        if (lead >= 'a' && lead <= 'z' && "aeiou".indexOf(lead) == -1) {
                            res.append(lead == 'c' ? 't' : lead);
                        }
                    }
                }
                i++;
                continue;
            }

            // Chōonpu (ー)
            if (c == 'ー') {
                if (res.length() > 0) {
                    char prev = res.charAt(res.length() - 1);
                    if ("aeiouAEIOU".indexOf(prev) != -1) {
                        res.append(Character.toLowerCase(prev));
                    }
                }
                i++;
                continue;
            }

            // Two-character combinations (digraphs)
            if (i + 1 < len) {
                String pair = text.substring(i, i + 2);
                String romajiPair = KANA_PAIRS.get(pair);
                if (romajiPair != null) {
                    res.append(romajiPair);
                    i += 2;
                    continue;
                }
            }

            // Single Kana
            String single = KANA_SINGLE.get(c);
            if (single != null) {
                res.append(single);
                i++;
                continue;
            }

            // Other character (ASCII, punctuation, numbers, spaces)
            res.append(c);
            i++;
        }

        return res.toString();
    }

    private static void initKanaTables() {
        // Hiragana digraphs
        KANA_PAIRS.put("きゃ", "kya"); KANA_PAIRS.put("きゅ", "kyu"); KANA_PAIRS.put("きょ", "kyo");
        KANA_PAIRS.put("しゃ", "sha"); KANA_PAIRS.put("しゅ", "shu"); KANA_PAIRS.put("しょ", "sho"); KANA_PAIRS.put("しぇ", "she");
        KANA_PAIRS.put("ちゃ", "cha"); KANA_PAIRS.put("ちゅ", "chu"); KANA_PAIRS.put("ちょ", "cho"); KANA_PAIRS.put("ちぇ", "che");
        KANA_PAIRS.put("にゃ", "nya"); KANA_PAIRS.put("にゅ", "nyu"); KANA_PAIRS.put("にょ", "nyo");
        KANA_PAIRS.put("ひゃ", "hya"); KANA_PAIRS.put("ひゅ", "hyu"); KANA_PAIRS.put("ひょ", "hyo");
        KANA_PAIRS.put("みゃ", "mya"); KANA_PAIRS.put("みゅ", "myu"); KANA_PAIRS.put("みょ", "myo");
        KANA_PAIRS.put("りゃ", "rya"); KANA_PAIRS.put("りゅ", "ryu"); KANA_PAIRS.put("りょ", "ryo");
        KANA_PAIRS.put("ぎゃ", "gya"); KANA_PAIRS.put("ぎゅ", "gyu"); KANA_PAIRS.put("ぎょ", "gyo");
        KANA_PAIRS.put("じゃ", "ja");  KANA_PAIRS.put("じゅ", "ju");  KANA_PAIRS.put("じょ", "jo");  KANA_PAIRS.put("じぇ", "je");
        KANA_PAIRS.put("びゃ", "bya"); KANA_PAIRS.put("びゅ", "byu"); KANA_PAIRS.put("びょ", "byo");
        KANA_PAIRS.put("ぴゃ", "pya"); KANA_PAIRS.put("ぴゅ", "pyu"); KANA_PAIRS.put("ぴょ", "pyo");

        // Katakana digraphs & extended loanword combinations
        KANA_PAIRS.put("キャ", "kya"); KANA_PAIRS.put("キュ", "kyu"); KANA_PAIRS.put("キョ", "kyo");
        KANA_PAIRS.put("シャ", "sha"); KANA_PAIRS.put("シュ", "shu"); KANA_PAIRS.put("ショ", "sho"); KANA_PAIRS.put("シェ", "she");
        KANA_PAIRS.put("チャ", "cha"); KANA_PAIRS.put("チュ", "chu"); KANA_PAIRS.put("チョ", "cho"); KANA_PAIRS.put("チェ", "che");
        KANA_PAIRS.put("ニャ", "nya"); KANA_PAIRS.put("ニュ", "nyu"); KANA_PAIRS.put("ニョ", "nyo");
        KANA_PAIRS.put("ヒャ", "hya"); KANA_PAIRS.put("ヒュ", "hyu"); KANA_PAIRS.put("ヒョ", "hyo");
        KANA_PAIRS.put("ミャ", "mya"); KANA_PAIRS.put("ミュ", "myu"); KANA_PAIRS.put("ミョ", "myo");
        KANA_PAIRS.put("リャ", "rya"); KANA_PAIRS.put("リュ", "ryu"); KANA_PAIRS.put("リョ", "ryo");
        KANA_PAIRS.put("ギャ", "gya"); KANA_PAIRS.put("ギュ", "gyu"); KANA_PAIRS.put("ギョ", "gyo");
        KANA_PAIRS.put("ジャ", "ja");  KANA_PAIRS.put("ジュ", "ju");  KANA_PAIRS.put("ジョ", "jo");  KANA_PAIRS.put("ジェ", "je");
        KANA_PAIRS.put("ビャ", "bya"); KANA_PAIRS.put("ビュ", "byu"); KANA_PAIRS.put("ビョ", "byo");
        KANA_PAIRS.put("ピャ", "pya"); KANA_PAIRS.put("ピュ", "pyu"); KANA_PAIRS.put("ピョ", "pyo");
        KANA_PAIRS.put("ティ", "ti");  KANA_PAIRS.put("ディ", "di");  KANA_PAIRS.put("トゥ", "tu");  KANA_PAIRS.put("ドゥ", "du");
        KANA_PAIRS.put("ファ", "fa");  KANA_PAIRS.put("フィ", "fi");  KANA_PAIRS.put("フェ", "fe");  KANA_PAIRS.put("フォ", "fo");  KANA_PAIRS.put("フュ", "fyu");
        KANA_PAIRS.put("ウィ", "wi");  KANA_PAIRS.put("ウェ", "we");  KANA_PAIRS.put("ウォ", "wo");
        KANA_PAIRS.put("ヴァ", "va");  KANA_PAIRS.put("ヴィ", "vi");  KANA_PAIRS.put("ヴェ", "ve");  KANA_PAIRS.put("ヴォ", "vo");
        KANA_PAIRS.put("ツァ", "tsa"); KANA_PAIRS.put("ツィ", "tsi"); KANA_PAIRS.put("ツェ", "tse"); KANA_PAIRS.put("ツォ", "tso");
        KANA_PAIRS.put("クァ", "kwa"); KANA_PAIRS.put("クィ", "kwi"); KANA_PAIRS.put("クェ", "kwe"); KANA_PAIRS.put("クォ", "kwo");
        KANA_PAIRS.put("グァ", "gwa");

        // Hiragana single
        KANA_SINGLE.put('あ', "a"); KANA_SINGLE.put('い', "i"); KANA_SINGLE.put('う', "u"); KANA_SINGLE.put('え', "e"); KANA_SINGLE.put('お', "o");
        KANA_SINGLE.put('か', "ka"); KANA_SINGLE.put('き', "ki"); KANA_SINGLE.put('く', "ku"); KANA_SINGLE.put('け', "ke"); KANA_SINGLE.put('こ', "ko");
        KANA_SINGLE.put('さ', "sa"); KANA_SINGLE.put('し', "shi"); KANA_SINGLE.put('す', "su"); KANA_SINGLE.put('せ', "se"); KANA_SINGLE.put('そ', "so");
        KANA_SINGLE.put('た', "ta"); KANA_SINGLE.put('ち', "chi"); KANA_SINGLE.put('つ', "tsu"); KANA_SINGLE.put('て', "te"); KANA_SINGLE.put('と', "to");
        KANA_SINGLE.put('な', "na"); KANA_SINGLE.put('に', "ni"); KANA_SINGLE.put('ぬ', "nu"); KANA_SINGLE.put('ね', "ne"); KANA_SINGLE.put('の', "no");
        KANA_SINGLE.put('は', "ha"); KANA_SINGLE.put('ひ', "hi"); KANA_SINGLE.put('ふ', "fu"); KANA_SINGLE.put('へ', "he"); KANA_SINGLE.put('ほ', "ho");
        KANA_SINGLE.put('ま', "ma"); KANA_SINGLE.put('み', "mi"); KANA_SINGLE.put('む', "mu"); KANA_SINGLE.put('め', "me"); KANA_SINGLE.put('も', "mo");
        KANA_SINGLE.put('や', "ya"); KANA_SINGLE.put('ゆ', "yu"); KANA_SINGLE.put('よ', "yo");
        KANA_SINGLE.put('ら', "ra"); KANA_SINGLE.put('り', "ri"); KANA_SINGLE.put('る', "ru"); KANA_SINGLE.put('れ', "re"); KANA_SINGLE.put('ろ', "ro");
        KANA_SINGLE.put('わ', "wa"); KANA_SINGLE.put('ゐ', "wi"); KANA_SINGLE.put('ゑ', "we"); KANA_SINGLE.put('を', "o");  KANA_SINGLE.put('ん', "n");
        KANA_SINGLE.put('が', "ga"); KANA_SINGLE.put('ぎ', "gi"); KANA_SINGLE.put('ぐ', "gu"); KANA_SINGLE.put('げ', "ge"); KANA_SINGLE.put('ご', "go");
        KANA_SINGLE.put('ざ', "za"); KANA_SINGLE.put('じ', "ji"); KANA_SINGLE.put('ず', "zu"); KANA_SINGLE.put('ぜ', "ze"); KANA_SINGLE.put('ぞ', "zo");
        KANA_SINGLE.put('だ', "da"); KANA_SINGLE.put('ぢ', "ji"); KANA_SINGLE.put('づ', "zu"); KANA_SINGLE.put('で', "de"); KANA_SINGLE.put('ど', "do");
        KANA_SINGLE.put('ば', "ba"); KANA_SINGLE.put('び', "bi"); KANA_SINGLE.put('ぶ', "bu"); KANA_SINGLE.put('べ', "be"); KANA_SINGLE.put('ぼ', "bo");
        KANA_SINGLE.put('ぱ', "pa"); KANA_SINGLE.put('ぴ', "pi"); KANA_SINGLE.put('ぷ', "pu"); KANA_SINGLE.put('ぺ', "pe"); KANA_SINGLE.put('ぽ', "po");
        KANA_SINGLE.put('ぁ', "a"); KANA_SINGLE.put('ぃ', "i"); KANA_SINGLE.put('ぅ', "u"); KANA_SINGLE.put('ぇ', "e"); KANA_SINGLE.put('ぉ', "o");
        KANA_SINGLE.put('ゃ', "ya"); KANA_SINGLE.put('ゅ', "yu"); KANA_SINGLE.put('ょ', "yo"); KANA_SINGLE.put('ゎ', "wa");

        // Katakana single
        KANA_SINGLE.put('ア', "a"); KANA_SINGLE.put('イ', "i"); KANA_SINGLE.put('ウ', "u"); KANA_SINGLE.put('エ', "e"); KANA_SINGLE.put('オ', "o");
        KANA_SINGLE.put('カ', "ka"); KANA_SINGLE.put('キ', "ki"); KANA_SINGLE.put('ク', "ku"); KANA_SINGLE.put('ケ', "ke"); KANA_SINGLE.put('コ', "ko");
        KANA_SINGLE.put('サ', "sa"); KANA_SINGLE.put('シ', "shi"); KANA_SINGLE.put('ス', "su"); KANA_SINGLE.put('セ', "se"); KANA_SINGLE.put('ソ', "so");
        KANA_SINGLE.put('タ', "ta"); KANA_SINGLE.put('チ', "chi"); KANA_SINGLE.put('ツ', "tsu"); KANA_SINGLE.put('テ', "te"); KANA_SINGLE.put('ト', "to");
        KANA_SINGLE.put('ナ', "na"); KANA_SINGLE.put('ニ', "ni"); KANA_SINGLE.put('ヌ', "nu"); KANA_SINGLE.put('ネ', "ne"); KANA_SINGLE.put('ノ', "no");
        KANA_SINGLE.put('ハ', "ha"); KANA_SINGLE.put('ヒ', "hi"); KANA_SINGLE.put('フ', "fu"); KANA_SINGLE.put('ヘ', "he"); KANA_SINGLE.put('ホ', "ho");
        KANA_SINGLE.put('マ', "ma"); KANA_SINGLE.put('ミ', "mi"); KANA_SINGLE.put('ム', "mu"); KANA_SINGLE.put('メ', "me"); KANA_SINGLE.put('モ', "mo");
        KANA_SINGLE.put('ヤ', "ya"); KANA_SINGLE.put('ユ', "yu"); KANA_SINGLE.put('ヨ', "yo");
        KANA_SINGLE.put('ラ', "ra"); KANA_SINGLE.put('リ', "ri"); KANA_SINGLE.put('ル', "ru"); KANA_SINGLE.put('レ', "re"); KANA_SINGLE.put('ロ', "ro");
        KANA_SINGLE.put('ワ', "wa"); KANA_SINGLE.put('ヰ', "wi"); KANA_SINGLE.put('ヱ', "we"); KANA_SINGLE.put('ヲ', "o");  KANA_SINGLE.put('ン', "n");
        KANA_SINGLE.put('ガ', "ga"); KANA_SINGLE.put('ギ', "gi"); KANA_SINGLE.put('グ', "gu"); KANA_SINGLE.put('ゲ', "ge"); KANA_SINGLE.put('ゴ', "go");
        KANA_SINGLE.put('ザ', "za"); KANA_SINGLE.put('ジ', "ji"); KANA_SINGLE.put('ズ', "zu"); KANA_SINGLE.put('ゼ', "ze"); KANA_SINGLE.put('ゾ', "zo");
        KANA_SINGLE.put('ダ', "da"); KANA_SINGLE.put('ヂ', "ji"); KANA_SINGLE.put('ヅ', "zu"); KANA_SINGLE.put('デ', "de"); KANA_SINGLE.put('ド', "do");
        KANA_SINGLE.put('バ', "ba"); KANA_SINGLE.put('ビ', "bi"); KANA_SINGLE.put('ブ', "bu"); KANA_SINGLE.put('ベ', "be"); KANA_SINGLE.put('ボ', "bo");
        KANA_SINGLE.put('パ', "pa"); KANA_SINGLE.put('ピ', "pi"); KANA_SINGLE.put('プ', "pu"); KANA_SINGLE.put('ペ', "pe"); KANA_SINGLE.put('ポ', "po");
        KANA_SINGLE.put('ヴ', "vu");
        KANA_SINGLE.put('ァ', "a"); KANA_SINGLE.put('ィ', "i"); KANA_SINGLE.put('ゥ', "u"); KANA_SINGLE.put('ェ', "e"); KANA_SINGLE.put('ォ', "o");
        KANA_SINGLE.put('ャ', "ya"); KANA_SINGLE.put('ュ', "yu"); KANA_SINGLE.put('ョ', "yo"); KANA_SINGLE.put('ヮ', "wa");

        // Punctuation and fullwidth spaces
        KANA_SINGLE.put('、', ", ");
        KANA_SINGLE.put('。', ". ");
        KANA_SINGLE.put('・', " ");
        KANA_SINGLE.put('「', """);
        KANA_SINGLE.put('」', """);
        KANA_SINGLE.put('『', """);
        KANA_SINGLE.put('』', """);
        KANA_SINGLE.put('〜', "~");
        KANA_SINGLE.put('～', "~");
        KANA_SINGLE.put('　', " ");
    }
}
