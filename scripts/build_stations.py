"""녹화 데이터 + 공식 역 순서로 src/main/resources/stations/{LINE}.json을 만든다.
- 역 ID는 노선 안에서 연속 번호다(수인분당선 5209 청량리 ~ 5271 인천 = 63역). 빈 번호는 녹화 중 열차가 없던 역.
- 이름은 녹화에 있으면 녹화 값을 쓰고, 없으면 아래 공식 순서 목록에서 채운다.
- 녹화 이름과 목록 이름이 다르면 멈춘다(목록이 틀렸다는 뜻).
사용: python3 scripts/build_stations.py recordings/2026-11-16 [recordings/...]"""
import json, sys, glob, os

LINES = {
    'LINE_2': {
        'subway': '1002',
        # updnLine 0 = 역 ID 증가(내선, 시청→을지로입구), 1 = 감소. 녹화 이동으로 확인.
        'headings': {'0': 'ASCENDING', '1': 'DESCENDING'},
        'paths': [
            ('본선', True, range(201, 244), ['시청','을지로입구','을지로3가','을지로4가','동대문역사문화공원','신당','상왕십리','왕십리','한양대','뚝섬','성수','건대입구','구의','강변','잠실나루','잠실','잠실새내','종합운동장','삼성','선릉','역삼','강남','교대','서초','방배','사당','낙성대','서울대입구','봉천','신림','신대방','구로디지털단지','대림','신도림','문래','영등포구청','당산','합정','홍대입구','신촌','이대','아현','충정로']),
            ('성수지선', False, [211] + list(range(2111, 2115)), ['성수','용답','신답','용두','신설동']),
            ('신정지선', False, [234] + list(range(2341, 2345)), ['신도림','도림천','양천구청','신정네거리','까치산']),
        ],
    },
    'SUIN_BUNDANG': {
        'subway': '1075',
        # 0 = 감소(청량리·왕십리 방면), 1 = 증가(인천 방면)
        'headings': {'0': 'DESCENDING', '1': 'ASCENDING'},
        'paths': [
            ('본선', False, range(5209, 5272), ['청량리','왕십리','서울숲','압구정로데오','강남구청','선정릉','선릉','한티','도곡','구룡','개포동','대모산입구','수서','복정','가천대','태평','모란','야탑','이매','서현','수내','정자','미금','오리','죽전','보정','구성','신갈','기흥','상갈','청명','영통','망포','매탄권선','수원시청','매교','수원','고색','오목천','어천','야목','사리','한대앞','중앙','고잔','초지','안산','신길온천','정왕','오이도','달월','월곶','소래포구','인천논현','호구포','남동인더스파크','원인재','연수','송도','인하대','숭의','신포','인천']),
        ],
    },
    'SINBUNDANG': {
        'subway': '1077',
        # 0 = 감소(신사 방면), 1 = 증가(광교 방면)
        'headings': {'0': 'DESCENDING', '1': 'ASCENDING'},
        'paths': [
            ('본선', False, list(range(684, 690)) + list(range(6810, 6820)), ['신사','논현','신논현','강남','양재','양재시민의숲','청계산입구','판교','정자','미금','동천','수지구청','성복','상현','광교중앙','광교']),
        ],
    },
}

observed = {}
for day in sys.argv[1:]:
    for path in glob.glob(os.path.join(day, '*.jsonl')):
        for raw in open(path, encoding='utf-8'):
            for t in json.loads(json.loads(raw)['body']).get('realtimePositionList') or []:
                observed[t['statnId']] = t['statnNm']

out_dir = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'stations')
os.makedirs(out_dir, exist_ok=True)
problems = []
for line, spec in LINES.items():
    paths, seen_count, total = [], 0, 0
    for name, loop, numbers, names in spec['paths']:
        numbers = list(numbers)
        assert len(numbers) == len(names), f'{line} {name}: 번호 {len(numbers)}개, 이름 {len(names)}개'
        stations = []
        for n, listed in zip(numbers, names):
            # 실제 ID 형식은 녹화에서 확인한다(앞자리 규칙이 노선마다 달라서)
            cands = [k for k in observed if k.startswith(spec['subway']) and int(k[-4:]) == n]
            sid = cands[0] if cands else None
            if sid is None:
                # 같은 노선의 다른 ID에서 앞 6자리를 빌려 만든다
                any_id = next(k for k in observed if k.startswith(spec['subway']))
                sid = any_id[:6] + str(n).rjust(4, '0')
            got = observed.get(sid)
            if got is not None and got.replace('종착', '') != listed:
                problems.append(f'{line} {sid}: 녹화 "{got}" ≠ 목록 "{listed}"')
            if got is not None: seen_count += 1
            total += 1
            stations.append({'id': sid, 'name': listed})
        paths.append({'name': name, 'loop': loop, 'stations': stations})
    with open(os.path.join(out_dir, f'{line}.json'), 'w', encoding='utf-8') as f:
        json.dump({'line': line, 'headings': spec['headings'], 'paths': paths}, f, ensure_ascii=False, indent=1)
    unseen = [s['name'] for p in paths for s in p['stations'] if s['id'] not in observed]
    print(f'{line}: 경로 {len(paths)}개, 역 {total}칸, 녹화로 확인 {seen_count}칸, 녹화에 없던 역: {sorted(set(unseen))}')
if problems:
    print('\n이름 불일치(목록을 고쳐야 함):'); print('\n'.join(problems)); sys.exit(1)
