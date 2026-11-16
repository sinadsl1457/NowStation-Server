"""녹화에서 시나리오 테스트에 쓸 실제 장면을 찾는다.
- 태평역(수인분당) 왕십리 방면 열차가 들어오고 나간 관측 시각(탑승·열차 놓침)
- 중간역에 60초 넘게 서 있던 열차(임시 정차)
- 수인분당 왕십리 방면이 선릉에 온 뒤 2호선 열차가 선릉을 떠난 경우(환승)
사용: python3 scripts/find_scenarios.py recordings/2026-11-16"""
import json, sys, glob, os, collections
from datetime import datetime

def kst(iso):
    from datetime import timedelta
    return (datetime.fromisoformat(iso.replace('Z', '+00:00')) + timedelta(hours=9)).strftime('%H:%M:%S')

def events(path):
    last = {}
    for raw in open(path, encoding='utf-8'):
        rec = json.loads(raw); t = kst(rec['fetchedAt'])
        for tr in json.loads(rec['body']).get('realtimePositionList') or []:
            key = tr['trainNo']; cur = (tr['statnNm'], tr['trainSttus'], tr['updnLine'])
            if last.get(key, (None,))[0] != cur[0]:
                yield t, key, 'at', cur
            elif last[key][1] != cur[1]:
                yield t, key, 'status', cur
            last[key] = cur

day = sys.argv[1]
f = lambda n: os.path.join(day, f'{n}.jsonl')
print('== 태평(왕십리 방면, updn 0) 열차 흐름')
seen = collections.defaultdict(list)
for t, k, kind, (st, s, u) in events(f('SUIN_BUNDANG')):
    if u == '0' and st in ('모란', '태평', '가천대'): seen[k].append(f'{t} {st}{"진입도착출발"[int(s)*2:int(s)*2+2] if s in "012" else s}')
for k, v in seen.items():
    if any('태평' in x for x in v): print(f'  {k}: ' + ' → '.join(v))

print('== 60초 넘게 같은 역 도착 상태(종착역 제외)')
for name in ('SUIN_BUNDANG', 'LINE_2'):
    # 같은 역·상태로 연속 관측된 최대 길이
    runs = collections.defaultdict(lambda: [None, None, 0])
    best = []
    prev = {}
    for raw in open(f(name), encoding='utf-8'):
        rec = json.loads(raw); now = datetime.fromisoformat(rec['fetchedAt'].replace('Z', '+00:00'))
        for tr in json.loads(rec['body']).get('realtimePositionList') or []:
            k = tr['trainNo']; cur = (tr['statnId'], tr['trainSttus'])
            if prev.get(k, (None, None, None))[0] != cur:
                if k in prev and prev[k][2]:
                    best.append(((prev[k][2] - prev[k][1]).total_seconds(), name, k, prev[k][3]))
                prev[k] = [cur, now, now, tr]
            else:
                prev[k][2] = now
    for dur, n, k, tr in sorted(best, key=lambda x: x[0], reverse=True)[:8]:
        if tr['statnNm'] != tr['statnTnm'] and tr['trainSttus'] == '1':
            print(f'  {n} {k} {tr["statnNm"]} 도착 상태 {dur:.0f}초 (updn {tr["updnLine"]}, 종착 {tr["statnTnm"]})')

print('== 선릉 환승 후보: 수인분당 왕십리 방면 선릉 도착 → 2호선 선릉 출발')
arr = [(t, k) for t, k, kind, (st, s, u) in events(f('SUIN_BUNDANG')) if st == '선릉' and u == '0' and kind == 'at']
dep = [(t, k, u) for t, k, kind, (st, s, u) in events(f('LINE_2')) if st == '선릉' and s == '2']
for t, k in arr:
    after = [d for d in dep if t < d[0] <= t[:3] + str(int(t[3:5]) + 6).rjust(2, '0') + t[5:]]
    print(f'  수인분당 {k} 선릉 {t} → 2호선 출발: ' + ', '.join(f'{d[0]} {d[1]}(updn {d[2]})' for d in after[:4]))
