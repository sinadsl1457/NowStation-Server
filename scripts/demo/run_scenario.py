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
  python3 scripts/demo/run_scenario.py yatap --mode=background   # 시작 뒤 YouTube를 앞에 띄움
  python3 scripts/demo/run_scenario.py yatap --mode=locked       # 시작 뒤 PIN 잠금 + 화면 끔
--mode=app(기본)은 앱 화면 글자를, background·locked는 시스템에 올라간 알림 글자를 확인한다.
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
    "yatap": {
        "title": "태평에서 6073을 타고 모란을 지나 야탑에서 내릴 준비",
        # 6073: 태평 09:46:55 도착 관측 → 모란 09:47:55 진입·09:48:36 도착 → 야탑 09:50:57 진입·09:51:37 도착
        "start": "09:45:30",
        "origin": ("태평", "xovud"),
        "destination": ("야탑", "dixkq"),
        "moves": ["09:47:05", "09:48:20"],
        "stops": ["09:47:45", "09:50:45"],
        "offline": [],
        "checks": [
            ("09:46:30", "태평에서 기다림(6073 도착 중)", ["기다리는 역", "태평"], ["태평역에서 기다리는 중"]),
            ("09:49:10", "6073 탑승 → 모란", ["수인분당선", "이번 역", "모란"], ["이번 역 모란", "다음 역 야탑", "수인분당선"]),
            ("09:51:30", "야탑 직전 하차 알림", ["야탑", "내릴 준비하세요"], ["곧 야탑, 내릴 준비하세요", "다음 역 이매"]),
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


PIN = "1234"
YOUTUBE = "com.google.android.youtube"


def nowstation_notifications() -> dict[int, str]:
    """시스템에 올라가 있는 NowStation 알림들의 글자(알림 번호 → 제목 | 내용 | 노선). 앱 화면이 안 보일 때 확인용."""
    found = {}
    for record in adb("shell", "dumpsys", "notification", "--noredact").split("NotificationRecord(")[1:]:
        m = re.match(r"0x[0-9a-f]+: pkg=com\.nowstation\.app user=\S+ id=(\d+) ", record)
        if not m:
            continue
        fields = [f.group(2) for f in re.finditer(r"android\.(title|text|subText)=\w+ \((.*)\)", record)]
        if fields:
            found.setdefault(int(m.group(1)), " | ".join(dict.fromkeys(fields)))
    return found


def ride_notification() -> str:
    """진행 중 알림(1번)."""
    return nowstation_notifications().get(1, "")


def screenshot(path: Path) -> None:
    path.write_bytes(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=20).stdout)


def nowstation_vibrations() -> int:
    return adb("shell", "dumpsys", "vibrator_manager").count("com.nowstation.app")


def unlock() -> None:
    adb("shell", "locksettings", "clear", "--old", PIN)
    adb("shell", "input", "keyevent", "224")
    time.sleep(1)
    adb("shell", "input", "swipe", "540", "1800", "540", "600", "200")
    time.sleep(1)


def alert_posted() -> bool:
    """내릴 역 팝업 알림(2번)."""
    return 2 in nowstation_notifications()


def wait_watching_alert(seconds: float, seen: bool, out: Path, offset: float) -> bool:
    """기다리는 동안 1초마다 내릴 역 팝업 알림이 올라왔는지 보고, 올라온 순간 화면을 찍는다(팝업은 몇 초만 보인다)."""
    end = time.time() + seconds
    while time.time() < end:
        if not seen and alert_posted():
            seen = True
            time.sleep(1.5)  # 팝업이 내려오는 동안
            screenshot(out / "alert.png")
            awake = "mWakefulness=Awake" in adb("shell", "dumpsys", "power")
            replay_at = datetime.fromtimestamp(time.time() + offset, KST)
            print(f"  {replay_at:%H:%M:%S} 내릴 역 팝업 알림 올라옴(화면 {'켜짐' if awake else '꺼짐'}) → alert.png")
        time.sleep(min(1.0, max(0.0, end - time.time())))
    return seen


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
    mode = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--mode=")), "app")
    if mode not in ("app", "background", "locked"):
        raise SystemExit("--mode=app|background|locked")
    if mode != "app" and any(len(c) < 4 for c in sc["checks"]):
        raise SystemExit(f"{name}에는 알림 기대값이 없다(yatap만 background·locked 지원)")
    out = ROOT / "build/demo" / (name if mode == "app" else f"{name}-{mode}")
    out.mkdir(parents=True, exist_ok=True)
    print(f"[{name}] {sc['title']} / 모드: {mode}")
    # 맥이 잠자기에 들어가면 스크립트·에뮬레이터·서버가 같이 멈췄다가 시각이 어긋난다(실제로 한 번 실패). 끝날 때까지 깨워 둔다.
    subprocess.Popen(["caffeinate", "-is", "-w", str(os.getpid())])

    # 서버가 켜지고 앱을 누르는 동안(약 20초)도 녹화 시계가 흐르므로 그만큼 앞에서 시작한다
    server = start_server(kst(sc["start"]) - timedelta(seconds=20), out / "server.log")
    try:
        accel(0)
        network(True)
        start_ride(sc)
        vibrations_before = nowstation_vibrations()
        if mode == "background":
            adb("shell", "monkey", "-p", YOUTUBE, "-c", "android.intent.category.LAUNCHER", "1")
            time.sleep(3)
            print("  YouTube를 앞에 띄움(NowStation은 뒤로)")
        elif mode == "locked":
            adb("shell", "locksettings", "set-pin", PIN)
            adb("shell", "input", "keyevent", "223")
            print("  PIN 잠금 + 화면 끔")

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
        for i, (t, desc, expected, *noti) in enumerate(sc["checks"], 1):
            events.append((kst(t), "확인", (i, desc, noti[0] if mode != "app" else expected)))
        events.sort(key=lambda e: e[0])

        failures = 0
        alert_seen = False
        for at, kind, value in events:
            wait = at.timestamp() - offset - time.time()
            if wait > 0:
                alert_seen = wait_watching_alert(wait, alert_seen, out, offset)
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
                if mode == "app":
                    texts = [t for t, _ in screen_nodes() if t]
                    shown = " | ".join(t for t in texts if t not in ("탑승 끝내기",))[:120]
                    where = "화면"
                else:
                    shown = ride_notification()
                    texts = [shown]
                    where = "알림"
                missing = [e for e in expected if not any(e in t for t in texts)]
                if mode == "locked":  # 사용자가 잠금 버튼을 눌러 화면을 켠 순간을 찍고 다시 끈다
                    adb("shell", "input", "keyevent", "224")
                    time.sleep(1.5)
                    screenshot(out / f"{i:02d}.png")
                    adb("shell", "input", "keyevent", "223")
                else:
                    screenshot(out / f"{i:02d}.png")
                ok = not missing
                failures += 0 if ok else 1
                print(f"  {at:%H:%M:%S} {'통과' if ok else '실패'} {desc}\n           {where}: {shown}"
                      + (f"\n           없음: {missing}" if missing else ""))

        print(f"  내릴 역 팝업 알림: {'올라옴' if alert_seen else '없음'}")
        vibrated = nowstation_vibrations() - vibrations_before
        print(f"  진동 기록: {vibrated}건")
        # 상태 표시줄 칩·진행 중 알림
        if mode != "locked":
            adb("shell", "cmd", "statusbar", "expand-notifications")
            time.sleep(1.5)
            screenshot(out / "notification.png")
            adb("shell", "cmd", "statusbar", "collapse")
        print(f"  결과: {len(sc['checks']) - failures}/{len(sc['checks'])} 통과, 스크린숏 {out}")
    finally:
        network(True)
        accel(0)
        if mode == "locked":
            unlock()
        if mode != "app":
            adb("shell", "am", "start", "-n", "com.nowstation.app/.MainActivity")
            time.sleep(2)
        for text, b in screen_nodes():
            if text == "탑승 끝내기":
                tap(b)
                break
        if "--keep-server" not in sys.argv:
            os.killpg(server.pid, signal.SIGTERM)


if __name__ == "__main__":
    main()
