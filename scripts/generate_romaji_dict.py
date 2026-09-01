#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generates RomajiDictionaryData.java containing compressed dictionary data
for Japanese Kanji, Hiragana, and Katakana to Romaji transliteration.
"""

import urllib.request
import json
import re
import tarfile
import io
import gzip
import base64
import os

def main():
    print("Fetching JMdict common vocabulary...")
    url_jmdict = 'https://github.com/scriptin/jmdict-simplified/releases/download/3.6.2%2B20260831182826/jmdict-eng-common-3.6.2%2B20260831182826.json.tgz'
    req = urllib.request.Request(url_jmdict, headers={'User-Agent': 'Mozilla/5.0'})
    compounds = {}
    with urllib.request.urlopen(req) as resp:
        data = resp.read()
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as tar:
            f = tar.extractfile(tar.getmembers()[0])
            parsed = json.load(f)
            for w in parsed.get('words', []):
                kanjis = [k['text'] for k in w.get('kanji', []) if 'text' in k]
                kanas = [k['text'] for k in w.get('kana', []) if 'text' in k]
                if kanjis and kanas:
                    primary_kana = kanas[0]
                    for k in kanjis:
                        # Only keep words with at least one Kanji
                        if any(0x4E00 <= ord(c) <= 0x9FFF for c in k):
                            if k not in compounds:
                                compounds[k] = primary_kana

    # Curate high-priority lyrics words & common compounds
    lyrics_vocab = {
        '残酷': 'ざんこく',
        '天使': 'てんし',
        '窓辺': 'まどべ',
        '飛び立つ': 'とびたつ',
        '思い出': 'おもいで',
        '想い': 'おもい',
        '好き': 'すき',
        '大好き': 'だいすき',
        '今日': 'きょう',
        '明日': 'あした',
        '昨日': 'きのう',
        '大人': 'おとな',
        '一緒': 'いっしょ',
        '誰か': 'だれか',
        '何処': 'どこ',
        '何時': 'いつ',
        '世界': 'せかい',
        '運命': 'うんめい',
        '未来': 'みらい',
        '約束': 'やくそく',
        '笑顔': 'えがお',
        '夜空': 'よぞら',
        '星空': 'ほしぞら',
        '青空': 'あおぞら',
        '東京': 'とうきょう',
        '雨上がり': 'あめあがり',
        '見上げて': 'みあげて',
        '歩き出す': 'あるきだす',
        '輝いて': 'かがやいて',
        '交わした': 'かわした',
        '拭いて': 'ふいて',
        '見せて': 'みせて',
        '夜に駆ける': 'よるにかける',
        '沈む': 'しずむ',
        '溶けて': 'とけて',
        '広がる': 'ひろがる',
        '始まる': 'はじまる',
        '終わる': 'おわる',
        '消える': 'きえる',
        '届く': 'とどく',
        '響く': 'ひびく',
        '信じて': 'しんじて',
        '愛してる': 'あいしてる',
        '会いたい': 'あいたい',
        '言えない': 'いえない',
        '忘れない': 'わすれない',
        '離さない': 'はなさない',
        '抱きしめて': 'だきしめて',
        '瞳': 'ひとみ',
        '心': 'こころ',
        '涙': 'なみだ',
        '声': 'こえ',
        '夢': 'ゆめ',
        '光': 'ひかり',
        '闇': 'やみ',
        '風': 'かぜ',
        '雨': 'あめ',
        '星': 'ほし',
        '月': 'つき',
        '太陽': 'たいよう',
        '花': 'はな',
        '桜': 'さくら',
        '海': 'うみ',
        '空': 'そら',
        '夜': 'よる',
        '朝': 'あさ',
        '今': 'いま',
        '時': 'とき',
        '僕': 'ぼく',
        '私': 'わたし',
        '君': 'きみ',
        'あなた': 'あなた',
        '彼': 'かれ',
        '彼女': 'かのじょ',
        '誰': 'だれ',
        '何': 'なに',
        '日': 'ひ',
        '空': 'そら',
        '夜': 'よる',
        '朝': 'あさ',
        '星': 'ほし',
        '月': 'つき',
        '花': 'はな',
        '声': 'こえ',
        '夢': 'ゆめ',
        '道': 'みち',
        '神': 'かみ',
        '人': 'ひと',
        '手': 'て',
        '目': 'め',
        '背中': 'せなか',
        '一言': 'ひとこと',
        '全て': 'すべて',
        '初めて': 'はじめて',
        '会った': 'あった',
        '奪った': 'うばった',
        '抱いて': 'だいて',
        '分かった': 'わかった',
        '沈み出した': 'しずみだした',
        '重なって': 'かさなって',
        '裏切る': 'うらぎる',
        '熱い': 'あつい',
        '大事': 'だいじ',
        '温もり': 'ぬくもり',
        '痛み': 'いたみ',
        '間に合う': 'まにあう',
        '歩いて': 'あるいて',
        '伝えたくて': 'つたえたくて',
        '去りゆく': 'さりゆく',
        '神話': 'しんわ',
        '少年': 'しょうねん',
        '宇宙': 'うちゅう',
        '二人': 'ふたり',
        '姿': 'すがた',
        '悲しみ': 'かなしみ',
        '限り': 'かぎり',
    }
    compounds.update(lyrics_vocab)
    print(f"Total compound vocabulary words: {len(compounds)}")

    print("Fetching Kanji character data...")
    url_k = 'https://raw.githubusercontent.com/davidluzgouveia/kanji-data/master/kanji.json'
    req_k = urllib.request.Request(url_k, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req_k) as resp:
        k_data = json.loads(resp.read().decode('utf-8'))

    kanji_table = {}
    for k, info in k_data.items():
        grade = info.get('grade')
        freq = info.get('freq') or 99999
        if grade or info.get('jlpt_new') or freq < 3500:
            ons = info.get('readings_on', [])
            kuns = info.get('readings_kun', [])
            clean_kuns = [re.sub(r'^[!-]', '', x) for x in kuns]
            kun_full = clean_kuns[0].replace('.', '').replace('-', '') if clean_kuns else ''
            kun_stem = clean_kuns[0].split('.')[0].replace('-', '') if clean_kuns else ''
            on_reading = ons[0] if ons else ''
            kanji_table[k] = {
                'on': on_reading or kun_full,
                'kun': kun_full or on_reading,
                'stem': kun_stem or kun_full or on_reading
            }

    print(f"Total Kanji entries: {len(kanji_table)}")

    # Format dictionary:
    # Compound lines: WORD=READING
    # ---
    # Kanji lines: KANJI=ON,KUN,STEM
    comp_lines = [f"{k}={v}" for k, v in compounds.items()]
    kanji_lines = [f"{k}={v['on']},{v['kun']},{v['stem']}" for k, v in kanji_table.items()]

    raw_text = "\n".join(comp_lines) + "\n---\n" + "\n".join(kanji_lines)
    gzipped = gzip.compress(raw_text.encode('utf-8'))
    b64_str = base64.b64encode(gzipped).decode('ascii')

    chunk_size = 30000
    chunks = [b64_str[i:i+chunk_size] for i in range(0, len(b64_str), chunk_size)]

    output_dir = "/home/albert/Documents/Projects/morphe-patches/extensions/shared/library/src/main/java/app/morphe/extension/shared/translation"
    os.makedirs(output_dir, exist_ok=True)
    out_file = os.path.join(output_dir, "RomajiDictionaryData.java")

    print(f"Writing {out_file} ({len(chunks)} chunks, {len(gzipped)/1024:.1f} KB gzipped)...")
    with open(out_file, "w", encoding="utf-8") as out:
        out.write("""/*
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

/**
 * Embedded compressed Japanese-to-Romaji dictionary data.
 * Contains common compound words and Kanji readings (On/Kun/Stem).
 */
public final class RomajiDictionaryData {

    private RomajiDictionaryData() {
    }

    static final String[] DATA_CHUNKS = new String[] {
""")
        for c in chunks:
            out.write(f'        "{c}",\n')
        out.write("""    };
}
""")
    print("Done!")

if __name__ == "__main__":
    main()
