# NowStation Server (지금역)

지하철에서 **지금 무슨 역인지**를 잠금화면에 보여 주는 앱 NowStation의 서버입니다.
사람이 많아 안내 화면이 안 보이거나 이어폰 때문에 방송이 안 들릴 때, 폰만 보면 현재역·다음역과 내릴 역 알림을 받을 수 있습니다.

지하에서는 GPS가 안 됩니다. 시간표로 추정하면 열차가 늦거나 멈춰 서는 순간 틀립니다.
그래서 서버가 **서울시 실시간 열차 위치**를 받아, 승강장 전광판처럼 들어오는 열차를 보여 주고 **사용자가 탄 열차를 실제 위치대로** 따라갑니다. 폰 가속도 센서는 공공 데이터보다 먼저 "방금 출발했다"를 알아채는 데 씁니다.

클라이언트: [NowStation-Android](https://github.com/sinadsl1457/NowStation-Android) · [NowStation-iOS](https://github.com/sinadsl1457/NowStation-iOS)(개인 테스트용, APNs 없이 Live Activity)

<p align="center"><img src="docs/demo/nowstation-demo.gif" width="300" alt="태평에서 야탑까지: 역 고르기, 다가오는 열차 고르기, 한 정거장 전 알림, 내릴 준비, 도착"></p>

<p align="center"><sub>2026-11-16 오전에 녹화한 실제 열차 위치를 재생 서버로 흘리고, 에뮬레이터 센서에 열차 출발·정차를 넣어 찍은 장면(6분 43초 → 35초 요약, <a href="docs/demo/nowstation-demo.mp4">mp4</a>)</sub></p>

## 핵심 아이디어: 전광판처럼 고르고, 고른 열차를 실제 위치대로 따라간다

```
고르기     태평으로 들어오는 열차   6073 "태평 진입 · 지금 타세요"  /  6075 "[2]번째 전역 (복정) · 약 4분"
           → 서버가 다음 열차(6073)를 골라 둠. 다른 열차를 탔으면 사용자가 목록에서 눌러 바꿈
따라가기   서울시 데이터 6073 모란 도착 → "이번 정거장 모란 · 야탑까지 1정거장" + 한 정거장 전 띠·진동
앞당기기   폰이 모란에서 섰다가 다시 출발(가속도 센서) → 데이터가 바뀌기 전에 "곧 야탑, 내리실 역이에요"
```
<sub>2026-11-16 오전 녹화 데이터(태평 → 야탑) 시연의 실제 순서: 09:46:30 6073 따라감 → 09:48:10 모란, 한 정거장 전 띠 → 09:49:00 폰이 모란 출발을 느낌 → 야탑 화면 → 09:51:45 야탑 도착</sub>

- **탔으면 가는 것:** 고른 열차를 폰 움직임으로 바꾸거나 "놓쳤다"고 판단하지 않습니다. 처음에는 폰이 출발한 순간 그 역을 떠난 열차를 탄 열차로 찾았지만, 실제 지하철 시험(태평 → 야탑)에서 한 번도 열차를 찾지 못했고 걷다 멈추는 것과 열차 출발을 가려내기 어려웠습니다. 그래서 사용자가 고르는 방식(같은 일을 하는 다른 앱과 같은 방식)으로 바꿨습니다.
- **갈아타기:** 경로는 서버가 하나로 정합니다(지도 앱 "지하철만" 첫 결과처럼). 따라가던 열차가 환승역에 오면 갈아타기 안내와 함께 다음 노선에서 들어올 열차를 골라 둡니다. 갈아탈 역과 내릴 역은 한 정거장 전에 알립니다.
- **남은 정거장은 언제나 최종 목적지까지:** 환승이 있어도 "강남까지 8정거장"처럼 셉니다.
- **멈춰 서면:** 실제 위치를 보여 주니 "역 사이 정차"도 그대로 나옵니다.
- **센서는 앞당기기에만:** 공공 데이터는 열차가 역에 들어설 때만 바뀌고 30초~1분 늦습니다. 폰이 그 역에서 섰다가 다시 출발하면 데이터보다 먼저 다음 역으로 넘깁니다. 센서가 공공 데이터보다 얼마나 빠르고 맞는지는 실탑승 로그(`움직임`·`열차`)로 재는 중입니다.

## 기술적으로 신경 쓴 부분

| 문제 | 해결 |
|---|---|
| 지하에서는 GPS가 없고, 시간표로는 지연·임시 정차를 반영하지 못함 | 실시간 열차 위치(서울시 API)로 **사용자가 고른 열차를 따라감**. 폰 가속도 센서는 출발을 앞당겨 알아채는 데만 씀 |
| 들어오는 열차 목록에 탈 수 없는 열차가 섞임(반대 방향, 환승역까지 안 가는 열차, 타는 역에 서지 않는 급행) | 경로 구간 방향 열차만, 구간 끝 전에 끝나는 열차는 뺌. 급행은 위치 API의 급행 값이 늘 0이라 못 믿어서 **도착정보 API(`btrainSttus`)**로 가려냄(30초 캐시, 고를 때만 호출) |
| 수인분당선은 "출발" 상태를 주지 않음 | 다음 역에 진입으로 보이는 순간으로 출발을 추정. 그만큼 늦으므로 폰이 그 역에서 섰다가 떠난 순간이 있으면 그걸로 앞당김(실제 녹화 데이터로 찾은 버그, 실패하는 테스트로 고정 후 수정) |
| 데이터가 실제보다 30~50초 늦음(1일차 실측) | 내릴 준비를 한 정거장 전(이전 역에 있을 때)에 띠·진동으로 알림. 타는 역에 서 있는 동안은 켜지 않음(한 정거장 경로에서 타자마자 울리던 것) |
| 실시간 API 하루 1,000건 | 탑승 중인 사람이 있는 노선만, 같은 노선은 모든 탑승이 한 번만 받아 공유. 앱 소식이 10분 없으면 탑승 정리(강제 종료된 앱이 한도를 쓰지 않게). 남은 호출이 적으면 자정까지 버티게 간격을 늘림 |
| 폰 시계가 서버와 다르고, 지하에서 끊겨 움직임이 늦게 올라옴 | 폰이 "보낸 시각과의 차이"만 믿어 서버 시각으로 바꿈. 늦게 온 움직임도 일어난 시각대로 판단 |
| 실기기 없음(Android 실기기·유료 Apple 계정 없음) | 출근 시간 실제 응답을 녹화 → **서버 재생 모드**(시계·데이터 출처만 바꿔 끼움) + 에뮬레이터 센서·네트워크 조작 + 화면·알림 글자 자동 대조 |
| 판단 규칙이 복잡해 Spring과 섞이면 테스트가 느리고 어려움 | 규칙(`RideSession`, `TrainTracker`, `StationMap`)은 순수 Kotlin, 시간은 주입한 `Clock`. 실제 녹화 데이터를 흘려 시나리오 테스트 |
| 배포 서버 메모리 1GB, 맥(Arm)과 서버(x86) 구조가 다름 | 이미지는 맥에서 x86용으로 만들어 보냄(서버에서 Gradle 빌드 안 함). MySQL 버퍼·서버 메모리 한도, 스왑 2GB |

## 전체 구조

```mermaid
flowchart LR
    subgraph Client["NowStation-Android"]
        A1[홈·탑승 화면]
        A2[움직임 감지<br/>가속도 센서]
        A3[진행 중 알림<br/>Live Update·하차 팝업]
    end
    subgraph Server["NowStation-Server (이 저장소, AWS EC2 + Docker)"]
        S1[ride<br/>탑승 세션·탄 열차 찾기]
        S2[tracking<br/>열차별 도착·출발]
        S3[subway<br/>API 클라이언트·역 데이터]
        S4[recording<br/>녹화·재생]
        DB[(MySQL)]
    end
    API[서울시 실시간<br/>열차 위치 API]

    A1 -- 탑승 시작·열차 고르기 --> S1
    A2 -- 출발/정차 시각 --> S1
    S1 -- 현재역·다음역·하차 알림 --> A3
    S1 --> S2 --> S3
    S3 -- 탑승 중인 노선만 15초마다 --> API
    S4 -. 녹화한 응답 재생 .-> S2
    S1 --- DB
```

## 서버 코드 지도

```
src/main/kotlin/com/nowstation/server/
├ subway/      바깥(서울시 API)과 역 데이터
│  ├ SeoulSubwayClient.kt        실시간 API 호출
│  ├ RealtimePositionParser.kt   노선마다 다른 응답 → 열차 위치 한 모양
│  ├ ApiCallBudget.kt, PollPacing.kt   하루 한도 세기, 남은 수로 간격 정하기
│  ├ ExpressTrains.kt            도착정보 API로 급행 열차 번호 가려내기
│  └ StationMap(s).kt            노선별 역 순서, 다음 역(2호선 순환·지선 포함)
├ tracking/    TrainTracker.kt   받아 온 데이터 → "열차 ○○이 언제 어느 역에 도착·출발"
├ ride/        탑승
│  ├ RideSession.kt              판단 규칙: 고른 열차 따라가기, 환승 대기, 내릴 준비·도착, 센서로 출발 앞당기기
│  ├ RoutePlanner.kt             경로 정하기(지도 앱 "지하철만" 첫 결과처럼): 구간·방향·환승역
│  ├ TrainChoices.kt             들어오는 열차 목록(경로 방향 역 + 열차, 전광판 말투, 몇 분 뒤), 골라 둘 다음 열차
│  ├ RideRules.kt                판단 기준값 한곳
│  ├ RideService.kt              진행 중인 탑승들, 필요한 노선만 받아 오기, 버려진 탑승 정리
│  ├ RidePoller.kt               탑승 중일 때만 15초마다
│  ├ RideController.kt           앱용 API
│  └ RideRecord.kt               탑승 기록(MySQL, Flyway)
└ recording/   실제 응답 녹화와 재생 모드(Replay*.kt)
```
의존 방향은 `ride → tracking → subway` 한 방향입니다.

## API

| 요청 | 하는 일 |
|---|---|
| `GET /api/stations?query=선릉` | 역 검색 → `[{"name":"선릉","lines":["2호선","수인분당선"]}]` |
| `GET /api/lines` | 지원 노선 → `["2호선","수인분당선","신분당선"]` |
| `POST /api/rides` `{"originStation":"태평","destinationStation":"야탑"}` | 탑승 시작(내릴 역은 선택) |
| `GET /api/rides/{id}/trains` | 들어오는 열차 목록(앱의 세로 노선도): 지금 구간 방향의 역(타는 역 4정거장 전 ~ 구간 끝)과 그 위 열차 → `{"leg":{"line":"수인분당선","from":"태평","to":"선릉"},"stations":["서현",…,"태평",…,"선릉"],"boardIndex":4,"trains":[{"trainNo":"6206","station":"모란","status":"ARRIVED","terminal":"왕십리","selected":true,"seenSecondsAgo":12,"message":"전역 도착 (모란)","etaMinutes":3,"atStationSeconds":20}],"secondsPerStop":130,"dwellSeconds":30}`. 경로가 없으면 204 |
| `POST /api/rides/{id}/train` `{"line":"수인분당선","trainNo":"6050"}` | 다른 열차 고르기. 고른 열차를 그대로 따라간다 |
| `POST /api/rides/{id}/motion` `{"events":[{"kind":"MOVING","at":"…"}],"sentAt":"…"}` | 폰이 출발·정차한 시각(끊긴 동안 쌓인 것을 한꺼번에) |
| `GET /api/rides/{id}` | `{"status":"MOVING","line":"수인분당선","thisStation":"수서","nextStation":"대모산입구","prepareToAlight":false,"legs":[{"line":"수인분당선","from":"태평","to":"선릉"},{"line":"2호선","from":"선릉","to":"강남"}],"trainNo":"6206","target":"선릉","remainingStops":6,"totalRemainingStops":8,"destination":"강남","notice":null}` (target·remainingStops: 지금 구간 끝까지, totalRemainingStops: 최종 목적지까지, notice: 환승역 도착 TRANSFER를 3분) |
| `DELETE /api/rides/{id}` | 탑승 종료 |
| `POST /api/feedback` `{"message":"…","contact":"(선택) 답장받을 이메일","platform":"android","appVersion":"0.1.70","device":"…"}` | 앱 설정의 문의하기(201). DB `feedback`에 저장, 1년 뒤 자동 삭제 |

`status`: `WAITING`(역에서 기다림) · `AT_STATION`(정차) · `MOVING`(이동) · `HOLDING_BETWEEN`(역 사이 정차) · `STALE`(2분 넘게 소식 없음)

## 테스트와 시연

**서버 테스트 85개**(`./gradlew test`, 2026-11-20 전부 통과)
- 판단 규칙(`RideSessionTest` 19): 고른 열차 그대로 따라가기, 환승역에서 안내 후 다음 구간 대기, 한 정거장 전 내릴 준비(타는 역에 서 있을 땐 안 켬), 내릴 역 도착, 센서로 출발 앞당기기, 최종 목적지까지 남은 정거장, 5분 임시 정차, 역 사이 정차, 소식 끊김. 알고 있는 한계 2개도 테스트로 적어 둠(이미 출발한 뒤 누르기, 지나온 역을 출발역으로)
- 경로(`RoutePlannerTest` 4): 태평 → 강남은 선릉 환승, 정자 → 강남은 신분당선 한 번에, 2호선은 가까운 쪽으로
- 들어오는 열차 목록(`TrainChoicesTest` 7): 전광판 말투와 몇 분 뒤, 반대 방향·급행·구간 끝 전에 끝나는 열차 빼기, 골라 둘 열차
- 실제 녹화 데이터(2026-11-16 오전) 시나리오 2개, 열차 추적·노선 지도·응답 해석
- 통합(Spring + MySQL을 Testcontainers로, `RideApiTest` 13): 한티 → 선릉 환승 → 강남을 API로 끝까지, 배포로 서버가 바뀌어도 이어 가기, 버려진 탑승 정리
- 한도: 간격 계산, 서울 자정 기준 초기화

**실제 지하철 탑승 시험(2026-11-20, iPhone, 수인분당선)**

| 시각 | 구간 | 따라간 열차 | 결과 |
|---|---|---|---|
| 15:03 ~ 15:12 | 야탑 → 복정(4정거장) | 6120 | 이번 역·남은 정거장·한 정거장 전 알림·도착이 실제와 맞음 |
| 19:51 ~ 19:56 | 복정 → 모란(3정거장) | 6591(골라 둔 6195를 사용자가 4초 만에 바꿈) | 같음 |

<sub>서버 로그(`단계`·`열차`·`움직임`)로 확인한 기록. 사용자 평가: "굉장히 정확한 타이밍에 열차 알림과 상태 변화". 시험은 계속합니다.</sub>

**에뮬레이터 화면까지 (`scripts/demo/run_scenario.py`)**

재생 모드 서버 + 녹화 시각에 맞춘 센서 주입 + 화면·알림 글자 대조를 한 번에 돌립니다.

| 시나리오 | 데이터 | 확인하는 장면 |
|---|---|---|
| `yatap` | 실제 녹화(11-16 오전) | 6073을 골라 따라감 → 모란에서 한 정거장 전 띠 → 폰이 모란 출발을 느끼면 야탑 화면 → 야탑 도착 |
| `yatap --mode=background` / `--mode=locked` | 실제 녹화 | YouTube를 보는 중·잠긴 폰에서 알림 갱신, 상태 표시줄 칩, 다른 화면 위 하차 팝업, 진동 |
| `transfer` | 실제 녹화 | 한티 → 지하 끊김 표시 → 선릉 환승 안내, 2호선 열차 골라 둠 → 강남 직전 알림 |
| `rush_up` | 퇴근 시간 **합성**(`make_rush_hour.py`) | 태평 → 한티 환승 띠 → 선릉에서 2호선 → 역삼 내릴 준비 → 강남 도착. README 시연 영상(Android) |
| `rush` | 퇴근 시간 합성 | 강남 → 선릉 환승 → 태평(반대 방향, 들어오는 열차 여러 대) |

<sub>`missed`(열차를 놓치면 폰 움직임으로 다음 열차를 찾던 예전 방식)는 "고른 열차를 그대로 따라간다"로 바뀐 뒤 확인 문구를 고치지 않았습니다.</sub>

```bash
# 앱은 이 컴퓨터의 재생 서버를 보게 설치: NowStation-Android에서
#   ./gradlew installDebug -PAPI_BASE_URL=http://10.0.2.2:8080/
python3 scripts/demo/run_scenario.py yatap                     # 약 7분(녹화를 실제 속도로)
python3 scripts/demo/run_scenario.py yatap --mode=story --record  # 시연 영상 녹화
```

## 한계와 다음

- **움직임 판단 기준값은 실기기로 다시 맞춰야 합니다.** 센서 값은 합성 신호와 에뮬레이터 주입으로만 확인했습니다(`RideRules`, 앱의 `MotionRules` 한곳에 모음).
- 노선은 2호선·수인분당선·신분당선 3개입니다. 역 데이터는 녹화와 대조해 만드는 스크립트(`scripts/build_stations.py`)로 늘릴 수 있습니다.
- 하루 호출 수를 메모리에서 셉니다(재시작하면 0부터, 여유분 50건).
- 센서가 공공 데이터보다 얼마나 빠르고 맞는지는 아직 실측 중입니다(실탑승 로그 몇 건뿐).
- 급행이 서는 역 목록이 없어 급행은 모두 뺍니다(수인분당선은 지금 급행이 없어 영향이 거의 없고, 9호선을 넣을 때 필요).
- iOS는 무료 Apple 계정으로 개인 테스트용입니다(APNs 없이 앱이 Live Activity를 직접 갱신).

## 기술 스택

Kotlin · Spring Boot 4 (Web MVC, Data JPA, Validation, Actuator, Scheduling) · MySQL 8.4 · Flyway · Jackson 3 · RestClient · JUnit 5 · Testcontainers · Docker · AWS EC2

## 실행

필요: JDK 25, Docker

```bash
cp .env.example .env          # 서울 열린데이터광장 "실시간 지하철 인증키"
./gradlew bootRun             # 로컬 MySQL은 Docker로 자동 실행
./gradlew test

# 배포는 main에 push하면 자동(아래 "배포")

# 실제 운행 데이터 녹화(1시간, 3개 노선 20초 간격 ≈ 540건)
./gradlew bootRun --args='--recording.enabled=true --recording.duration=PT60M --server.port=8081'
```

## 배포 (CI/CD)

**main에 push하면 끝입니다.** 사람이 서버에 들어가지 않습니다.

```
push ─→ [GitHub Actions] 테스트 85개 → 이미지(이름표 = 커밋 번호)를 GHCR에 올림
                                                     │
        [EC2] 1분마다 "main 이미지가 바뀌었나?" ←──────┘  (pull 방식: GitHub은 서버에 들어오지 않음)
              바뀌었으면 받아서 서버만 교체 → 2분 안에 UP이 아니면 이전 이미지로 되돌림
                                                     │
        [GitHub Actions] https /actuator/info의 버전이 이번 커밋이 될 때까지 확인 ─→ 초록 체크 / 빨간 X
```

> **pull 방식을 쓴 이유:** 유료 계정이라면 보통 GitHub OIDC로 임시 권한을 받아 SSM으로 서버에 배포를 지시하는 push 방식으로 만들지만, 이 프로젝트는 AWS 무료 플랜이라 OIDC 연결 생성이 조직 정책(SCP)으로 막혀 있어(`iam:CreateOpenIDConnectProvider` 거부) 서버가 스스로 가져가는 방식을 택했습니다.

| 결정 | 이유 |
|---|---|
| **pull 방식**(서버가 가져감) | GitHub에 서버 접속 키·AWS 키를 두지 않고, SSH는 내 IP만 열어 둔다 |
| 실패한 버전 기억 | 되돌린 버전을 다음 확인 때 또 깔면 매분 끊긴다 → 그 커밋은 다시 시도하지 않음 |
| GitHub이 버전으로 확인 | 서버의 읽기 토큰이 만료되거나 타이머가 멈추면 조용히 배포가 멈춘다 → 8분 안에 안 바뀌면 빨간 X |
| **배포 뒤 탑승 이어 가기** | 진행 중인 탑승은 서버 메모리에 있어 교체하면 사라진다(앱에 "탑승을 잃음"). 단계(기다림/탑승 중, 열차 번호)를 바뀔 때마다 DB에 적고, 새 서버는 모르는 탑승을 DB로 다시 만든다. 앱 소식이 10분 넘게 없던 탑승은 이어 가지 않음 |
| 설정 파일도 이미지 안에 | compose·Caddyfile·정적 페이지·배포 스크립트를 이미지에 담아, 코드와 설정 버전이 항상 같게 |

서버 구성: AWS EC2(t3.micro, x86, 메모리 1GB + 스왑 2GB, 고정 IP)에 Docker로 서버 + MySQL + Caddy(`compose.prod.yaml`).
https는 Caddy가 무료 주소(`<IP를 -로 이은>.sslip.io`)로 Let's Encrypt 인증서를 자동으로 받고 갱신합니다.

처음 한 번(새 EC2):
1. EC2 생성(Amazon Linux 2023), 탄력적 IP 연결. 보안 그룹: SSH는 내 IP만, 80·443은 모두
2. EC2에 Docker·compose 설치, 스왑 2GB
3. EC2 `~/nowstation/.env`에 `SEOUL_SUBWAY_KEY`, `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `DOMAIN`
4. `docker login ghcr.io`(GitHub classic 토큰, `read:packages`만)
5. `scripts/auto-deploy.sh` → `~/nowstation/`, `infra/ec2/nowstation-deploy.{service,timer}` → `/etc/systemd/system/`, `systemctl enable --now nowstation-deploy.timer`

확인: `journalctl -u nowstation-deploy -n 50`(배포 기록), `systemctl list-timers nowstation-deploy`

## 문서
- [docs/PLAN.md](docs/PLAN.md): 7일 계획과 위험
- [CLAUDE.md](CLAUDE.md): 실측한 데이터 사실, 구조 규칙, 작업 방식
