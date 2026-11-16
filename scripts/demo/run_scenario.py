#!/usr/bin/env python3
"""
실기기 없이 시나리오를 에뮬레이터 화면까지 돌린다.

  서버: 녹화 재생 모드(서버 시계 = 녹화한 날의 시각, 실제 속도)
  앱:   에뮬레이터에서 역을 골라 시작(adb로 누름)
  폰 움직임: 녹화 속 시각에 맞춰 가속도 센서 값을 넣음(adb emu sensor)
  지하 끊김: 에뮬레이터 Wi-Fi·데이터 끄기
  확인: 정해 둔 순간마다 화면 글자를 읽어 기대값과 비교, 스크린숏 저장(build/demo/<시나리오>/)

쓰는 법(NowStation-Server 폴더에서, 8080을 쓰는 다른 서버는 끄고):
  python3 scripts/demo/run_scenario.py transfer
  python3 scripts/demo/run_scenario.py missed
에뮬레이터에 NowStation 앱이 설치돼 있어야 한다.
"""
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
DAY = "2026-11-16"
ADB = str(Path.home() / "Library/Android/sdk/platform-tools/adb")
SERVER = "http://localhost:8080"
ROOT = Path(__file__).resolve().parents[2]
JAVA_HOME = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# 에뮬레이터를 세로로 둔 기본 자세의 중력(y≈9.8). 열차 진행 방향은 x로 넣는다.
GRAVITY = "9.77631:0.812349"
PUSH = 0.9  # 지하철 가속 0.5~1.3 m/s²
PUSH_SECONDS = 10
LATE_LIMIT = 5  # 이보다 늦게 실행된 단계가 있으면 시각이 어긋난 것


def kst(hms: str) -> datetime:
    return datetime.fromisoformat(f"{DAY}T{hms}+09:00")


# 폰 움직임 시각은 서버 RecordedScenarioTest와 같다(녹화 관측 시각에서 API 지연 30~50초를 뺀 값).
SCENARIOS = {
    "missed": {
        "title": "태평에서 6064는 놓치고 6518에 탄다",
        "start": "09:42:00",
        "origin": ("태평", "xovud"),
        "destination": None,
        "moves": ["09:53:05"],
        "stops": ["09:53:45"],
        "offline": [],
        "checks": [
            ("09:43:30", "6064가 태평에 도착(폰은 그대로)", ["기다리는 역", "태평"]),
            ("09:45:00", "6064가 떠남 → 놓침, 계속 기다림", ["기다리는 역", "태평"]),
            ("09:54:30", "6518 탑승 → 가천대 정차", ["이번 역", "가천대", "다음 역 복정"]),
        ],
    },
    "transfer": {
        "title": "한티에서 타고 선릉에서 2호선 환승, 강남 직전 하차 알림(중간에 지하 끊김)",
        "start": "09:45:00",
        "origin": ("한티", "gksxl"),
        "destination": ("강남", "rkdska"),
        "moves": ["09:47:15", "09:51:45", "09:54:05"],
        "stops": ["09:47:45", "09:52:40"],
        # 선릉 도착 정차를 끊긴 동안 감지 → 쌓였다가 연결되면 보냄
        "offline": [("09:47:30", "09:49:30")],
        "checks": [
            ("09:46:30", "한티에서 기다림", ["기다리는 역", "한티"]),
            ("09:49:00", "지하 끊김: 마지막 상태 그대로 + 끊김 표시", ["연결 끊김"]),
            ("09:51:20", "선릉에서 내림(6062가 떠날 때 폰은 멈춰 있음) → 환승 대기", ["기다리는 역", "선릉"]),
            ("09:53:30", "2호선 3154 탑승", ["이번 역", "역삼", "2호선"]),
            ("09:54:50", "강남 직전 하차 알림", ["강남", "내릴 준비하세요"]),
        ],
    },
}


def adb(*args: str, timeout: int = 20) -> str:
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout).stdout


def accel(x: float) -> None:
    adb("emu", "sensor", "set", "acceleration", f"{x}:{GRAVITY}")


def network(on: bool) -> None:
    state = "enable" if on else "disable"
    adb("shell", "svc", "wifi", state)
    adb("shell", "svc", "data", state)


def screen_nodes() -> list[tuple[str, tuple[int, int, int, int]]]:
    adb("shell", "uiautomator", "dump", "/sdcard/nowstation_ui.xml")
    xml = adb("shell", "cat", "/sdcard/nowstation_ui.xml")
    nodes = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        nodes.append((m.group(1), tuple(int(v) for v in m.groups()[1:])))
    return nodes


def edit_text_bounds() -> tuple[int, int, int, int]:
    xml = adb("shell", "cat", "/sdcard/nowstation_ui.xml")
    m = re.search(r'class="android.widget.EditText"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        raise SystemExit("홈 화면의 검색 칸을 못 찾았다")
    return tuple(int(v) for v in m.groups())


def tap(bounds) -> None:
    x1, y1, x2, y2 = bounds
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def pick_station(name: str, keys: str) -> None:
    """검색 칸에 두벌식 자판 키로 입력하고(에뮬레이터 키보드가 한글) 목록에서 그 역을 누른다."""
    screen_nodes()
    field = edit_text_bounds()
    tap(field)
    adb("shell", "input", "text", keys)
    time.sleep(1.5)  # 입력이 멈추면 검색(0.25초) + 서버 응답
    for text, b in screen_nodes():
        if text == name and b[1] > field[3]:
            tap(b)
            time.sleep(0.8)
            return
    raise SystemExit(f"검색 결과에 {name}이(가) 없다")


def start_ride(sc) -> None:
    adb("shell", "am", "force-stop", "com.nowstation.app")
    adb("shell", "am", "start", "-n", "com.nowstation.app/.MainActivity")
    time.sleep(3)
    pick_station(*sc["origin"])
    if sc["destination"]:
        pick_station(*sc["destination"])
    for text, b in screen_nodes():
        if text == "시작":
            tap(b)
            time.sleep(2)
            return
    raise SystemExit("시작 버튼을 못 찾았다")


def http_json(path: str):
    with urllib.request.urlopen(SERVER + path, timeout=3) as r:
        return json.load(r)


def start_server(start: datetime, log_path: Path) -> subprocess.Popen:
    try:
        http_json("/actuator/health")
        raise SystemExit("8080에 이미 서버가 떠 있다. 끄고 다시 실행(재생 모드 서버를 이 스크립트가 띄운다)")
    except OSError:
        pass
    args = f"--replay.enabled=true --replay.start={start.isoformat()} --ride.poll-interval=PT5S"
    env = {**os.environ, "JAVA_HOME": JAVA_HOME}
    proc = subprocess.Popen(
        ["./gradlew", "bootRun", "-q", f"--args={args}"],
        cwd=ROOT, env=env, stdout=log_path.open("w"), stderr=subprocess.STDOUT, start_new_session=True,
    )
    for _ in range(120):
        try:
            http_json("/api/replay")
            return proc
        except OSError:
            time.sleep(1)
    os.killpg(proc.pid, signal.SIGTERM)
    raise SystemExit(f"서버가 켜지지 않았다: {log_path}")


def main() -> None:
    name = sys.argv[1] if len(sys.argv) > 1 else ""
    if name not in SCENARIOS:
        raise SystemExit(f"시나리오: {', '.join(SCENARIOS)}")
    sc = SCENARIOS[name]
    out = ROOT / "build/demo" / name
    out.mkdir(parents=True, exist_ok=True)
    print(f"[{name}] {sc['title']}")
    # 맥이 잠자기에 들어가면 스크립트·에뮬레이터·서버가 같이 멈췄다가 시각이 어긋난다(실제로 한 번 실패). 끝날 때까지 깨워 둔다.
    subprocess.Popen(["caffeinate", "-is", "-w", str(os.getpid())])

    # 서버가 켜지고 앱을 누르는 동안(약 20초)도 녹화 시계가 흐르므로 그만큼 앞에서 시작한다
    server = start_server(kst(sc["start"]) - timedelta(seconds=20), out / "server.log")
    try:
        accel(0)
        network(True)
        start_ride(sc)

        replay_now = datetime.fromisoformat(http_json("/api/replay")["now"].replace("Z", "+00:00"))
        offset = replay_now.timestamp() - time.time()  # 녹화 속 시각 = 실제 시각 + offset
        print(f"  녹화 속 지금 {replay_now.astimezone(KST):%H:%M:%S}, 탑승 시작")

        events = []
        for t in sc["moves"]:  # 출발: 그 시각부터 가속
            events += [(kst(t), "가속", PUSH), (kst(t) + timedelta(seconds=PUSH_SECONDS), "가속 끝", 0.0)]
        for t in sc["stops"]:  # 도착: 그 시각에 감속이 끝나게
            events += [(kst(t) - timedelta(seconds=PUSH_SECONDS), "감속", -PUSH), (kst(t), "정차", 0.0)]
        for off, on in sc["offline"]:
            events += [(kst(off), "네트워크 끊김", False), (kst(on), "네트워크 복구", True)]
        for i, (t, desc, expected) in enumerate(sc["checks"], 1):
            events.append((kst(t), "확인", (i, desc, expected)))
        events.sort(key=lambda e: e[0])

        failures = 0
        for at, kind, value in events:
            wait = at.timestamp() - offset - time.time()
            if wait > 0:
                time.sleep(wait)
            elif wait < -LATE_LIMIT:
                raise SystemExit(f"{at:%H:%M:%S} 단계가 {-wait:.0f}초 늦었다(맥 잠자기 등). 결과를 믿을 수 없어 멈춘다")
            if kind in ("가속", "가속 끝", "감속", "정차"):
                accel(value)
                print(f"  {at:%H:%M:%S} 센서 {kind}")
            elif kind.startswith("네트워크"):
                network(value)
                print(f"  {at:%H:%M:%S} {kind}")
            else:
                i, desc, expected = value
                texts = [t for t, _ in screen_nodes() if t]
                missing = [e for e in expected if not any(e in t for t in texts)]
                adb_png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=20).stdout
                (out / f"{i:02d}.png").write_bytes(adb_png)
                ok = not missing
                failures += 0 if ok else 1
                shown = " | ".join(t for t in texts if t not in ("탑승 끝내기",))[:120]
                print(f"  {at:%H:%M:%S} {'통과' if ok else '실패'} {desc}\n           화면: {shown}"
                      + (f"\n           없음: {missing}" if missing else ""))

        # 상태 표시줄 칩·진행 중 알림
        adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(1.5)
        (out / "notification.png").write_bytes(
            subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=20).stdout)
        adb("shell", "cmd", "statusbar", "collapse")
        print(f"  결과: {len(sc['checks']) - failures}/{len(sc['checks'])} 통과, 스크린숏 {out}")
    finally:
        network(True)
        accel(0)
        for text, b in screen_nodes():
            if text == "탑승 끝내기":
                tap(b)
                break
        if "--keep-server" not in sys.argv:
            os.killpg(server.pid, signal.SIGTERM)


if __name__ == "__main__":
    main()
