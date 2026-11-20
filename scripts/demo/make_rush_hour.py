#!/usr/bin/env python3
"""
퇴근 시간 합성 데이터(실제 녹화 아님). 녹화는 2026-11-16 아침뿐이라, "들어오는 열차가 여러 대"인 퇴근 시간 화면을 보려고 만든다.
서울시 실시간 위치 API와 같은 모양(realtimePositionList)으로 15초마다 응답을 만든다. 열차 번호는 9로 시작(실제 번호와 섞이지 않게).

  - 2호선 양방향(강남 ↔ 선릉): 2분 30초 간격, 정거장 사이 2분, 정차 30초, 출발 상태(2)도 준다
  - 수인분당선 양방향(선릉 ↔ 태평): 4분 간격, 정거장 사이 2분 10초, 정차 30초, 출발 상태는 주지 않는다(실제와 같음)
  - 열차 번호: 9로 시작(강남 → 태평 방향), 8로 시작(태평 → 강남 방향)

쓰는 법(NowStation-Server 폴더): python3 scripts/demo/make_rush_hour.py
→ src/test/resources/scenarios/synthetic-2026-11-20-evening/{LINE_2,SUIN_BUNDANG}.jsonl (18:00 ~ 18:50 KST)
"""
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/test/resources/scenarios/synthetic-2026-11-20-evening"
KST = timezone(timedelta(hours=9))
START = datetime(2026, 11, 20, 18, 0, tzinfo=KST)
END = START + timedelta(minutes=50)
STEP = timedelta(seconds=15)
DWELL = 30
ENTER = 20  # 도착 전 '진입'으로 보이는 시간


def stations(line: str, path_name: str) -> list[dict]:
    data = json.loads((ROOT / f"src/main/resources/stations/{line}.json").read_text())
    return next(p for p in data["paths"] if p["name"] == path_name)["stations"]


LINES = [
    # (노선, subwayId, updnLine, 역 순서(진행 방향), 종착역, 정거장 사이 초, 배차 초, 출발 상태를 주나, 열차 번호 앞자리)
    ("LINE_2", "1002", "1", list(reversed(stations("LINE_2", "본선"))), "성수", 120, 150, True, "9"),  # 외선: 강남 → 선릉
    ("LINE_2", "1002", "0", stations("LINE_2", "본선"), "성수", 120, 150, True, "8"),  # 내선: 선릉 → 강남
    ("SUIN_BUNDANG", "1075", "1", stations("SUIN_BUNDANG", "본선"), "인천", 130, 240, False, "9"),  # 인천 방면: 선릉 → 태평
    ("SUIN_BUNDANG", "1075", "0", list(reversed(stations("SUIN_BUNDANG", "본선"))), "왕십리", 130, 240, False, "8"),  # 왕십리 방면: 태평 → 선릉
]


def position(order: list[dict], run: int, t: float, explicit_departure: bool):
    """출발 뒤 t초가 지난 열차의 (역, 상태). 노선 끝을 지나면 None."""
    leg = run + DWELL
    i = int(t // leg)
    if t < 0 or i >= len(order) - 1:
        return None
    within = t - i * leg
    if within < DWELL:
        return order[i], "1"  # 도착(정차 중)
    if within > leg - ENTER:
        return order[i + 1], "0"  # 다음 역 진입
    return (order[i], "2") if explicit_departure else (order[i], "1")


def trains_at(t: datetime, order, subway_id, updn, terminal, run, headway, explicit, prefix) -> list[dict]:
    term = next((s for s in order if s["name"] == terminal), order[-1])
    out = []
    # 시작 시각보다 이른 출발까지 넣어 처음부터 노선 위에 열차가 깔려 있게
    for k in range(-40, 40):
        depart = START + timedelta(seconds=k * headway)
        pos = position(order, run, (t - depart).total_seconds(), explicit)
        if not pos:
            continue
        st, status = pos
        out.append({
            "subwayId": subway_id, "statnId": st["id"], "statnNm": st["name"], "trainNo": f"{prefix}{k + 40:03d}",
            "trainSttus": status, "updnLine": updn, "statnTid": term["id"], "statnTnm": f"{term['name']}종착",
            "recptnDt": t.strftime("%Y-%m-%d %H:%M:%S"), "directAt": "0", "lstcarAt": "0",
        })
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for line in sorted({l[0] for l in LINES}):
        specs = [l[1:] for l in LINES if l[0] == line]
        lines = []
        t = START
        while t <= END:
            trains = []
            for subway_id, updn, order, terminal, run, headway, explicit, prefix in specs:
                trains += trains_at(t, order, subway_id, updn, terminal, run, headway, explicit, prefix)
            body = {"errorMessage": {"code": "INFO-000"}, "realtimePositionList": trains}
            lines.append(json.dumps({"fetchedAt": t.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"), "line": line,
                                     "body": json.dumps(body, ensure_ascii=False)}, ensure_ascii=False))
            t += STEP
        (OUT / f"{line}.jsonl").write_text("\n".join(lines) + "\n")
        print(f"{line}: {len(lines)}개 응답 → {OUT / (line + '.jsonl')}")


if __name__ == "__main__":
    main()
