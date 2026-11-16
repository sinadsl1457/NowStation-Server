"""녹화 파일에서 (1) 노선별로 본 역 목록, (2) updnLine 값과 실제 이동 방향(역 ID 증가/감소)의 관계를 뽑는다.
사용: python3 scripts/analyze_recordings.py recordings/2026-11-16
역 데이터(src/main/resources/stations)를 만들고 방향 규칙을 확인하는 데 쓴다."""
import json, sys, glob, os, collections

def load(path):
    for line in open(path, encoding='utf-8'):
        rec = json.loads(line)
        body = json.loads(rec['body'])
        yield rec['fetchedAt'], body.get('realtimePositionList') or []

day = sys.argv[1]
for path in sorted(glob.glob(os.path.join(day, '*.jsonl'))):
    name = os.path.basename(path).removesuffix('.jsonl')
    stations, last, moves, polls = {}, {}, collections.Counter(), 0
    for _, trains in load(path):
        polls += 1
        for t in trains:
            stations[t['statnId']] = t['statnNm']
            prev = last.get(t['trainNo'])
            if prev and prev != t['statnId']:
                delta = int(t['statnId'][-4:]) - int(prev[-4:])
                # 순환(2호선 0243→0201)·지선 경계처럼 크게 뛰는 건 방향 판단에서 뺀다
                if abs(delta) <= 3:
                    moves[(t['updnLine'], '증가' if delta > 0 else '감소')] += 1
            last[t['trainNo']] = t['statnId']
    print(f'== {name}: 응답 {polls}개, 역 {len(stations)}개')
    print('   방향:', dict(moves))
    print('   역:', ' '.join(f"{k[-4:]}:{v}" for k, v in sorted(stations.items())))
