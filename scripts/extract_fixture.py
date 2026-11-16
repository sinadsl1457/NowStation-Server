"""녹화 일부를 테스트 자료로 뽑는다: 시간 구간 + 관련 역을 지난 열차만 남기고(반대 방향·다른 노선 잡음은 유지),
응답 모양은 그대로 두고 쓰지 않는 칸만 지운다.
사용: python3 scripts/extract_fixture.py recordings/2026-11-16 09:37 10:00 src/test/resources/scenarios/2026-11-16-morning 태평,가천대,..."""
import json, sys, os, glob
from datetime import datetime, timedelta

day, start, end, out, names = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], set(sys.argv[5].split(','))
KEEP = ['subwayId', 'statnId', 'statnNm', 'trainNo', 'trainSttus', 'updnLine', 'statnTid', 'statnTnm', 'recptnDt', 'directAt', 'lstcarAt']
os.makedirs(out, exist_ok=True)
kst = lambda iso: (datetime.fromisoformat(iso.replace('Z', '+00:00')) + timedelta(hours=9)).strftime('%H:%M')
for path in glob.glob(os.path.join(day, '*.jsonl')):
    recs = [json.loads(l) for l in open(path, encoding='utf-8')]
    recs = [r for r in recs if start <= kst(r['fetchedAt']) < end]
    relevant = {t['trainNo'] for r in recs for t in json.loads(r['body']).get('realtimePositionList') or [] if t['statnNm'] in names}
    if not relevant:
        continue
    with open(os.path.join(out, os.path.basename(path)), 'w', encoding='utf-8') as f:
        for r in recs:
            body = json.loads(r['body'])
            lst = [{k: t.get(k) for k in KEEP} for t in body.get('realtimePositionList') or [] if t['trainNo'] in relevant]
            body = {'errorMessage': {'code': body.get('errorMessage', {}).get('code', body.get('code'))}, 'realtimePositionList': lst}
            f.write(json.dumps({'fetchedAt': r['fetchedAt'], 'line': r['line'], 'body': json.dumps(body, ensure_ascii=False)}, ensure_ascii=False) + '\n')
    print(os.path.basename(path), len(recs), '응답,', len(relevant), '열차')
