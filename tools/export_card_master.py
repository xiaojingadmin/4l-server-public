#!/usr/bin/env python3
"""One-off exporter for the Java server's static card master.

Python's ``admin/catalog.py`` reads ``master_cards`` / ``master_rarities`` /
``master_characters`` / ``master_skill_caps`` / ``master_limit_break_caps`` /
``master_card_series`` from MariaDB. The public repository ships the same rows as
SQLite + the packaged gacha master instead, so the Java server can mint and grow
cards without that extraction step.

The ``growth`` section carries the card-growth cost tables reduced to the shape
``admin/catalog.py`` returns (``[[item_id, num], ...]``), and ``card_levels`` is
the style-level experience table (``build/client_defaults/card_levels.json`` in
the reference tree).

Example:
  python tools/export_card_master.py \
      --cards data/cards.db \
      --master data/master_5.1.0.db \
      --series src/main/resources/client_defaults/gacha_master_5.1.0-R2604100.json \
      --out src/main/resources/client_defaults/card_master_5.1.0.json
"""
import argparse
import json
import sqlite3
from pathlib import Path

CARD_FIELDS = (
    'Id CardSeriesId Name CharactersId Rarity EvolveTimes CardLevelLimitAddition '
    'InitialSmile InitialPure InitialCool InitialMental BeatPoint '
    'SpecialAppealSeriesId SkillSeriesId AttributeId '
    'CenterSkillSeriesId CenterAttributeSeriesId RhythmGameSkillSeriesId '
    'ExperienceType CenterSkillLvUpItemId RhythmGameSkillLvUpItemId IsParallelCard'
).split()

RARITY_FIELDS = (
    'Id RarityName Evolution0_MaxLevel Evolution1_MaxLevel Evolution2_MaxLevel '
    'Evolution3_MaxLevel Evolution4_MaxLevel'
).split()

CHARACTER_FIELDS = 'Id NameLast NameFirst GenerationsId SeriesType'.split()

SERIES_FAN_FIELDS = (
    'ObtainFanLvPt Evolution1FanLvPt Evolution2FanLvPt Evolution3FanLvPt Evolution4FanLvPt '
    'LimitBreak1FanLvPt LimitBreak2FanLvPt LimitBreak3FanLvPt LimitBreak4FanLvPt '
    'Evolution3RequiredFanLv Evolution4RequiredFanLv'
).split()


def _growth(path):
    """Growth cost tables, already reduced to ``admin/catalog.py``'s return shape.

    Every entry is ``[(item_id, num)]`` — the same list Python's
    ``evolution_cost`` / ``limit_break_cost`` / ``skill_cost`` /
    ``rhythm_skill_cost`` return — so the Java side only has to look up a key.
    """
    connection = sqlite3.connect('file:%s?mode=ro' % Path(path).as_posix(), uri=True)
    connection.row_factory = sqlite3.Row
    try:
        evolution = {}
        for row in connection.execute('SELECT * FROM evolution_materials ORDER BY Id'):
            costs = [[row['CostItemsId%d' % i], row['CostNum%d' % i]] for i in (1, 2, 3)
                     if row['CostItemsId%d' % i] and row['CostNum%d' % i]]
            evolution[str(row['Id'])] = costs

        limit_break = {}
        for row in connection.execute(
                'SELECT CardSeriesId, LimitBreakTimes, CostItemsId, CostNum '
                'FROM limit_break_materials ORDER BY CardSeriesId, LimitBreakTimes'):
            limit_break['%d|%d' % (row['CardSeriesId'], row['LimitBreakTimes'])] = (
                [[row['CostItemsId'], row['CostNum']]]
                if row['CostItemsId'] and row['CostNum'] else [])

        skill = {}
        for row in connection.execute(
                'SELECT * FROM skill_level_up_materials '
                'ORDER BY CardSeriesId, SkillType, SkillLevel'):
            if row['CostItemsIds']:
                ids = [int(v) for v in row['CostItemsIds'].split(',') if v.strip()]
                nums = [int(v) for v in row['CostNums'].split(',') if v.strip()]
                costs = [[i, n] for i, n in zip(ids, nums) if i and n]
            else:
                costs = [[row['CostItemsId%d' % i], row['CostNum%d' % i]] for i in (1, 2, 3)
                         if row['CostItemsId%d' % i] and row['CostNum%d' % i]]
            skill['%d|%d|%d' % (row['CardSeriesId'], row['SkillType'],
                                row['SkillLevel'])] = costs

        # One (series, level) has several rows: each is a separate cost item.
        rhythm = {}
        for row in connection.execute(
                'SELECT ItemSeriesId, SkillLevel, CostItemsId, CostNum '
                'FROM rhythm_skill_costs ORDER BY ItemSeriesId, SkillLevel'):
            if not (row['CostItemsId'] and row['CostNum']):
                continue
            rhythm.setdefault('%d|%d' % (row['ItemSeriesId'], row['SkillLevel']), []).append(
                [row['CostItemsId'], row['CostNum']])

        rhythm_caps = {
            str(row['SkillSeriesId']): row['MaxSkillLevel']
            for row in connection.execute(
                'SELECT SkillSeriesId, MaxSkillLevel FROM rhythm_skill_caps')}
    finally:
        connection.close()
    return {'evolution_materials': evolution, 'limit_break_materials': limit_break,
            'skill_level_up_materials': skill, 'rhythm_skill_costs': rhythm,
            'rhythm_skill_caps': rhythm_caps}


def _rows(path, table, fields, order_by='Id'):
    connection = sqlite3.connect('file:%s?mode=ro' % Path(path).as_posix(), uri=True)
    connection.row_factory = sqlite3.Row
    try:
        return [dict(row) for row in connection.execute(
            'SELECT %s FROM %s ORDER BY %s' % (', '.join(fields), table, order_by))]
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cards', required=True, type=Path)
    parser.add_argument('--master', required=True, type=Path)
    parser.add_argument('--series', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()

    master = json.loads(args.series.read_text(encoding='utf-8'))['tables']
    series = {
        str(row['Id']): {key: row.get(key, 0) for key in SERIES_FAN_FIELDS}
        for row in master['cardseries.tsv']['rows']
    }
    payload = {
        'schema': 'local-card-master/1',
        'cards': {str(row['Id']): row for row in _rows(args.cards, 'cards', CARD_FIELDS)},
        'rarities': {str(row['Id']): row for row in _rows(args.master, 'rarities', RARITY_FIELDS)},
        'characters': {str(row['Id']): row
                       for row in _rows(args.master, 'characters', CHARACTER_FIELDS)},
        'skill_caps': {str(row['CardSkillSeriesId']): row['MaxSkillLevel']
                       for row in _rows(args.master, 'skill_caps',
                                        ['CardSkillSeriesId', 'MaxSkillLevel'],
                                        'CardSkillSeriesId')},
        'limit_break_caps': {str(row['CardSeriesId']): row['MaxLimitBreakTimes']
                             for row in _rows(args.master, 'limit_break_caps',
                                              ['CardSeriesId', 'MaxLimitBreakTimes'],
                                              'CardSeriesId')},
        'series': series,
        'growth': _growth(args.master),
        'card_levels': json.loads(
            (Path(__file__).resolve().parent.parent
             / 'src' / 'main' / 'resources' / 'client_defaults' / 'card_levels.json'
             ).read_text(encoding='utf-8')),
    }
    args.out.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')) + '\n',
                        encoding='utf-8')
    print('%s: %d cards, %d rarities, %d characters, %d series' % (
        args.out, len(payload['cards']), len(payload['rarities']),
        len(payload['characters']), len(payload['series'])))


if __name__ == '__main__':
    main()
