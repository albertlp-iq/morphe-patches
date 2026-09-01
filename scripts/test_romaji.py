#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Interactive & automated test tool for Morphe Japanese Lyrics Romaji patch.
Uses the embedded dictionary from RomajiDictionaryData.java and the Hepburn
transliteration engine.
"""

import sys
import os
import re
import gzip
import base64
import argparse
import urllib.request
import urllib.parse
import json

# Locate RomajiDictionaryData.java
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DICT_JAVA_PATH = os.path.join(
    SCRIPT_DIR,
    "..",
    "extensions",
    "shared",
    "library",
    "src",
    "main",
    "java",
    "app",
    "morphe",
    "extension",
    "shared",
    "translation",
    "RomajiDictionaryData.java"
)

def load_dictionary():
    if not os.path.exists(DICT_JAVA_PATH):
        raise FileNotFoundError(f"Cannot find RomajiDictionaryData.java at {DICT_JAVA_PATH}")
    
    with open(DICT_JAVA_PATH, "r", encoding="utf-8") as f:
        code = f.read()

    block = code.split("DATA_CHUNKS = new String[] {")[1].split("};")[0]
    chunks = re.findall(r'"([A-Za-z0-9+/=]+)"', block)
    b64_data = "".join(chunks)
    raw = gzip.decompress(base64.b64decode(b64_data)).decode("utf-8")

    compounds = {}
    kanji_table = {}
    in_kanji = False

    for line in raw.splitlines():
        if line == "---":
            in_kanji = True
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        if not in_kanji:
            compounds[k] = v
        else:
            parts = v.split(",", -1)
            on = parts[0] if len(parts) > 0 else ""
            kun = parts[1] if len(parts) > 1 else ""
            stem = parts[2] if len(parts) > 2 else ""
            kanji_table[k] = (on, kun, stem)

    return compounds, kanji_table

KANA_PAIRS = {
    "きゃ": "kya", "きゅ": "kyu", "きょ": "kyo",
    "しゃ": "sha", "しゅ": "shu", "しょ": "sho", "しぇ": "she",
    "ちゃ": "cha", "ちゅ": "chu", "ちょ": "cho", "ちぇ": "che",
    "にゃ": "nya", "にゅ": "nyu", "にょ": "nyo",
    "ひゃ": "hya", "ひゅ": "hyu", "ひょ": "hyo",
    "みゃ": "mya", "みゅ": "myu", "みょ": "myo",
    "りゃ": "rya", "りゅ": "ryu", "りょ": "ryo",
    "ぎゃ": "gya", "ぎゅ": "gyu", "ぎょ": "gyo",
    "じゃ": "ja",  "じゅ": "ju",  "じょ": "jo",  "じぇ": "je",
    "びゃ": "bya", "びゅ": "byu", "びょ": "byo",
    "ぴゃ": "pya", "ぴゅ": "pyu", "ぴょ": "pyo",
    "キャ": "kya", "キュ": "kyu", "キョ": "kyo",
    "シャ": "sha", "シュ": "shu", "ショ": "sho", "シェ": "she",
    "チャ": "cha", "チュ": "chu", "チョ": "cho", "チェ": "che",
    "ニャ": "nya", "ニュ": "nyu", "ニョ": "nyo",
    "ヒャ": "hya", "ヒュ": "hyu", "ヒョ": "hyo",
    "ミャ": "mya", "ミュ": "myu", "ミョ": "myo",
    "リャ": "rya", "リュ": "ryu", "リョ": "ryo",
    "ギャ": "gya", "ギュ": "gyu", "ギョ": "gyo",
    "ジャ": "ja",  "ジュ": "ju",  "ジョ": "jo",  "ジェ": "je",
    "ビャ": "bya", "ビュ": "byu", "ビョ": "byo",
    "ピャ": "pya", "ピュ": "pyu", "ピョ": "pyo",
    "ティ": "ti",  "ディ": "di",  "トゥ": "tu",  "ドゥ": "du",
    "ファ": "fa",  "フィ": "fi",  "フェ": "fe",  "フォ": "fo",  "フュ": "fyu",
    "ウィ": "wi",  "ウェ": "we",  "ウォ": "wo",
    "ヴァ": "va",  "ヴィ": "vi",  "ヴェ": "ve",  "ヴォ": "vo",
    "ツァ": "tsa", "ツィ": "tsi", "ツェ": "tse", "ツォ": "tso",
    "クァ": "kwa", "クィ": "kwi", "クェ": "kwe", "クォ": "kwo",
    "グァ": "gwa",
}

KANA_SINGLE = {
    'あ': "a", 'い': "i", 'う': "u", 'え': "e", 'お': "o",
    'か': "ka", 'き': "ki", 'く': "ku", 'け': "ke", 'こ': "ko",
    'さ': "sa", 'し': "shi", 'す': "su", 'せ': "se", 'そ': "so",
    'た': "ta", 'ち': "chi", 'つ': "tsu", 'て': "te", 'と': "to",
    'な': "na", 'に': "ni", 'ぬ': "nu", 'ね': "ne", 'の': "no",
    'は': "ha", 'ひ': "hi", 'ふ': "fu", 'へ': "he", 'ほ': "ho",
    'ま': "ma", 'み': "mi", 'む': "mu", 'め': "me", 'も': "mo",
    'や': "ya", 'ゆ': "yu", 'よ': "yo",
    'ら': "ra", 'り': "ri", 'る': "ru", 'れ': "re", 'ろ': "ro",
    'わ': "wa", 'ゐ': "wi", 'ゑ': "we", 'を': "o",  'ん': "n",
    'が': "ga", 'ぎ': "gi", 'ぐ': "gu", 'げ': "ge", 'ご': "go",
    'ざ': "za", 'じ': "ji", 'ず': "zu", 'ぜ': "ze", 'ぞ': "zo",
    'だ': "da", 'ぢ': "ji", 'づ': "zu", 'で': "de", 'ど': "do",
    'ば': "ba", 'び': "bi", 'ぶ': "bu", 'べ': "be", 'ぼ': "bo",
    'ぱ': "pa", 'ぴ': "pi", 'ぷ': "pu", 'ぺ': "pe", 'ぽ': "po",
    'ぁ': "a", 'ぃ': "i", 'ぅ': "u", 'ぇ': "e", 'ぉ': "o",
    'ゃ': "ya", 'ゅ': "yu", 'ょ': "yo", 'ゎ': "wa",
    'ア': "a", 'イ': "i", 'ウ': "u", 'エ': "e", 'オ': "o",
    'カ': "ka", 'キ': "ki", 'ク': "ku", 'ケ': "ke", 'コ': "ko",
    'サ': "sa", 'シ': "shi", 'ス': "su", 'セ': "se", 'ソ': "so",
    'タ': "ta", 'チ': "chi", 'ツ': "tsu", 'テ': "te", 'ト': "to",
    'ナ': "na", 'ニ': "ni", 'ヌ': "nu", 'ネ': "ne", 'ノ': "no",
    'ハ': "ha", 'ヒ': "hi", 'フ': "fu", 'ヘ': "he", 'ホ': "ho",
    'マ': "ma", 'ミ': "mi", 'ム': "mu", 'メ': "me", 'モ': "mo",
    'ヤ': "ya", 'ユ': "yu", 'ヨ': "yo",
    'ラ': "ra", 'リ': "ri", 'ル': "ru", 'レ': "re", 'ロ': "ro",
    'ワ': "wa", 'ヰ': "wi", 'ヱ': "we", 'ヲ': "o",  'ン': "n",
    'ガ': "ga", 'ギ': "gi", 'グ': "gu", 'ゲ': "ge", 'ゴ': "go",
    'ザ': "za", 'ジ': "ji", 'ず': "zu", 'ゼ': "ze", 'ゾ': "zo",
    'ダ': "da", 'ヂ': "ji", 'ヅ': "zu", 'デ': "de", 'ド': "do",
    'バ': "ba", 'ビ': "bi", 'ブ': "bu", 'ベ': "be", 'ボ': "bo",
    'パ': "pa", 'ピ': "pi", 'プ': "pu", 'ペ': "pe", 'ポ': "po",
    'ヴ': "vu",
    'ァ': "a", 'ィ': "i", 'ゥ': "u", 'ェ': "e", 'ォ': "o",
    'ャ': "ya", 'ュ': "yu", 'ョ': "yo", 'ヮ': "wa",
    '、': ", ", '。': ". ", '・': " ", '「': '"', '」': '"',
    '『': '"', '』': '"', '〜': "~", '～': "~", '　': " ",
}

PARTICLES = ["から", "まで", "より", "など", "は", "が", "を", "に", "へ", "で", "と", "の", "な", "ね", "よ", "か"]

def is_kanji(c):
    return (0x4E00 <= ord(c) <= 0x9FFF) or (0x3400 <= ord(c) <= 0x4DBF)

def is_hiragana(c):
    return 0x3040 <= ord(c) <= 0x309F

def is_katakana(c):
    return (0x30A0 <= ord(c) <= 0x30FF) or (0xFF65 <= ord(c) <= 0xFF9F)

def is_japanese(c):
    return is_kanji(c) or is_hiragana(c) or is_katakana(c) or c in ('ー', '〜', '～')

def contains_japanese(text):
    return any(is_japanese(c) for c in text)

def convert_kana_to_romaji(text):
    res = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in ('っ', 'ッ'):
            if i + 1 < n:
                nxt = convert_kana_to_romaji(text[i+1:])
                if nxt:
                    lead = nxt[0].lower()
                    if lead in "bcdfghjklmnpqrstvwxyz":
                        res.append('t' if lead == 'c' else lead)
            i += 1
            continue
        if c == 'ー':
            if res and res[-1] and res[-1][-1] in "aeiouAEIOU":
                res.append(res[-1][-1].lower())
            i += 1
            continue
        if i + 1 < n and text[i:i+2] in KANA_PAIRS:
            res.append(KANA_PAIRS[text[i:i+2]])
            i += 2
            continue
        if c in KANA_SINGLE:
            res.append(KANA_SINGLE[c])
            i += 1
            continue
        res.append(c)
        i += 1
    return "".join(res)

def transliterate_to_romaji(text, compounds, kanji_table):
    if not text or not contains_japanese(text):
        return text or ""

    n = len(text)
    kana_parts = []
    i = 0

    while i < n:
        c = text[i]
        matched = False
        for l in range(min(10, n - i), 0, -1):
            sub = text[i:i+l]
            if sub in compounds:
                kana_parts.append(" " + compounds[sub] + " ")
                i += l
                matched = True
                break
        if matched:
            continue

        if is_kanji(c):
            info = kanji_table.get(c)
            if info:
                on, kun, stem = info
                is_followed_by_kana = (i + 1 < n and (is_hiragana(text[i+1]) or is_katakana(text[i+1])))
                is_followed_by_kanji = (i + 1 < n and is_kanji(text[i+1]))
                is_preceded_by_kanji = (i > 0 and is_kanji(text[i-1]))
                if is_followed_by_kana:
                    val = stem if stem else (kun if kun else on)
                    kana_parts.append(" " + val)
                elif is_followed_by_kanji or is_preceded_by_kanji:
                    val = on if on else (kun if kun else stem)
                    kana_parts.append(val)
                else:
                    val = kun if kun else (on if on else stem)
                    kana_parts.append(" " + val + " ")
            else:
                kana_parts.append(c)
            i += 1
            continue

        kana_parts.append(c)
        i += 1

    intermediate = "".join(kana_parts)
    for p in PARTICLES:
        intermediate = intermediate.replace(p + " ", " " + p + " ")

    romaji = convert_kana_to_romaji(intermediate)
    romaji = re.sub(r" +", " ", romaji).strip()
    if romaji and romaji[0].islower():
        romaji = romaji[0].upper() + romaji[1:]
    return romaji

def test_default(compounds, kanji_table):
    print("=" * 60)
    print("  Morphe Patches: Japanese Lyrics Romaji Test Suite")
    print("=" * 60)
    samples = [
        ("Cruel Angel's Thesis", [
            "残酷な天使のテーゼ",
            "窓辺からやがて飛び立つ",
            "ほとばしる熱いパトスで",
            "思い出を裏切るなら",
            "この宇宙を抱いて輝く",
            "少年よ 神話になれ"
        ]),
        ("YOASOBI - 夜に駆ける (Racing Into The Night)", [
            "沈むように溶けてゆくように",
            "二人だけの空が広がる夜に",
            "「さよなら」だけだった",
            "その一言で全てが分かった",
            "日が沈み出した空と君の姿",
            "初めて会った日から",
            "僕の心の全てを奪った"
        ]),
        ("LiSA - 炎 (Homura)", [
            "さよなら ありがとう 声の限り",
            "悲しみよりもっと大事なこと",
            "去りゆく背中に伝えたくて",
            "温もりと痛みに間に合うように",
            "このまま二人で歩いてゆこう"
        ]),
        ("Katakana / Loanwords", [
            "東京タワーとチョコレート",
            "メロディーとハーモニー",
            "コンピューターとスマートフォン"
        ])
    ]

    for category, lines in samples:
        print(f"\n--- {category} ---")
        for l in lines:
            rom = transliterate_to_romaji(l, compounds, kanji_table)
            print(f"  [Original] {l}")
            print(f"  [Romaji]   {rom}\n")

def test_song(artist, track, compounds, kanji_table):
    print(f"\nFetching lyrics from LRCLIB for: {artist} - {track}...")
    params = urllib.parse.urlencode({"artist_name": artist, "track_name": track})
    url = f"https://lrclib.net/api/get?{params}"
    req = urllib.request.Request(url, headers={"User-Agent": "MorpheTest/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            lines_raw = (data.get("syncedLyrics") or data.get("plainLyrics") or "").splitlines()
            if not lines_raw:
                print("No lyrics found on LRCLIB.")
                return
            print(f"Found {len(lines_raw)} lines for '{data.get('trackName')}' by '{data.get('artistName')}'.\n")
            print("=" * 60)
            for line in lines_raw[:15]:
                clean_line = re.sub(r"^\[\d+:\d+\.\d+\]\s*", "", line).strip()
                if not clean_line:
                    continue
                rom = transliterate_to_romaji(clean_line, compounds, kanji_table)
                print(f"  {clean_line}")
                print(f"  -> {rom}\n")
    except Exception as ex:
        print(f"Could not fetch from LRCLIB: {ex}")

def test_youtube(video_id_or_url, compounds, kanji_table):
    import re
    import json
    m = re.search(r'([a-zA-Z0-9_-]{11})', video_id_or_url)
    video_id = m.group(1) if m else video_id_or_url
    print(f"\nFetching lyrics from YouTube Music for videoId: {video_id}...")

    next_url = "https://music.youtube.com/youtubei/v1/next"
    payload = {
        "videoId": video_id,
        "context": {"client": {"clientName": "ANDROID_MUSIC", "clientVersion": "6.43.52", "hl": "ja"}}
    }
    headers = {"Content-Type": "application/json", "User-Agent": "com.google.android.apps.youtube.music/6.43.52"}
    try:
        req = urllib.request.Request(next_url, data=json.dumps(payload).encode("utf-8"), headers=headers)
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))

        tabs = data.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabbedRenderer", {}).get("watchNextTabbedResultsRenderer", {}).get("tabs", [])
        browse_id = None
        for t in tabs:
            tr = t.get("tabRenderer", {})
            bid = tr.get("endpoint", {}).get("browseEndpoint", {}).get("browseId")
            if bid and bid.startswith("MPLY"):
                browse_id = bid
                break

        if not browse_id:
            print("No lyrics browseId found in YouTube Music next endpoint.")
            return

        b_url = "https://music.youtube.com/youtubei/v1/browse"
        b_payload = {"browseId": browse_id, "context": {"client": {"clientName": "ANDROID_MUSIC", "clientVersion": "6.43.52", "hl": "ja"}}}
        req2 = urllib.request.Request(b_url, data=json.dumps(b_payload).encode("utf-8"), headers=headers)
        with urllib.request.urlopen(req2) as resp2:
            bdata = json.loads(resp2.read().decode("utf-8"))

        def find_key(obj, key):
            if isinstance(obj, dict):
                for k, v in obj.items():
                    if k == key: return v
                    res = find_key(v, key)
                    if res is not None: return res
            elif isinstance(obj, list):
                for item in obj:
                    res = find_key(item, key)
                    if res is not None: return res
            return None

        timed = find_key(bdata, "timedLyricsData")
        lines = []
        if timed and isinstance(timed, list):
            print(f"Found YouTube Music Timed Lyrics ({len(timed)} lines)!\n" + "=" * 60)
            for item in timed:
                txt = item.get("lyricLine", "").strip()
                if txt and txt != "♪":
                    lines.append(txt)
        else:
            shelf = find_key(bdata, "musicDescriptionShelfRenderer")
            if shelf:
                runs = shelf.get("description", {}).get("runs", [])
                full_text = "".join([r.get("text", "") for r in runs])
                footer = shelf.get("footer", {}).get("runs", [{}])[0].get("text", "LyricFind")
                print(f"Found YouTube Static Lyrics ({footer})!\n" + "=" * 60)
                lines = [l.strip() for l in full_text.split("\n") if l.strip()]

        if not lines:
            print("No lyrics lines found in response.")
            return

        for line in lines[:15]:
            rom = transliterate_to_romaji(line, compounds, kanji_table)
            print(f"  {line}")
            print(f"  -> {rom}\n")

    except Exception as ex:
        print(f"Could not fetch from YouTube Music: {ex}")

def test_interactive(compounds, kanji_table):
    print("\n" + "=" * 60)
    print("  Interactive Romaji Transliteration REPL")
    print("  Type any Japanese lyric line, Kanji, or Hiragana/Katakana.")
    print("  (Type 'exit' or press Ctrl+C to quit)")
    print("=" * 60 + "\n")

    while True:
        try:
            line = input("lyrics> ").strip()
            if not line:
                continue
            if line.lower() in ("exit", "quit"):
                break
            rom = transliterate_to_romaji(line, compounds, kanji_table)
            print(f"romaji: {rom}\n")
        except (KeyboardInterrupt, EOFError):
            print("\nGoodbye!")
            break

def main():
    parser = argparse.ArgumentParser(description="Test Morphe Japanese Lyrics Romaji transliteration.")
    parser.add_argument("text", nargs="?", help="Direct text/lyric to transliterate")
    parser.add_argument("-i", "--interactive", action="store_true", help="Start interactive REPL")
    parser.add_argument("--song", nargs=2, metavar=("ARTIST", "TITLE"), help="Fetch and test song from LRCLIB")
    parser.add_argument("--yt", metavar="VIDEO_ID_OR_URL", help="Fetch and test lyrics directly from YouTube Music / LyricFind")

    args = parser.parse_args()

    print("Loading RomajiDictionaryData into memory...", end="", flush=True)
    compounds, kanji_table = load_dictionary()
    print(f" done! ({len(compounds)} compounds, {len(kanji_table)} kanji loaded)\n")

    if args.yt:
        test_youtube(args.yt, compounds, kanji_table)
    elif args.song:
        test_song(args.song[0], args.song[1], compounds, kanji_table)
    elif args.interactive:
        test_interactive(compounds, kanji_table)
    elif args.text:
        print(f"Original: {args.text}")
        print(f"Romaji:   {transliterate_to_romaji(args.text, compounds, kanji_table)}")
    else:
        test_default(compounds, kanji_table)

if __name__ == "__main__":
    main()
