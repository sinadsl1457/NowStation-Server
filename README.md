# NowStation Server

지하철에서 **지금 무슨 역인지**를 잠금화면에 보여 주는 앱 NowStation의 서버입니다.
사람이 많아 안내 화면이 안 보이거나 이어폰 때문에 방송이 안 들릴 때, 폰만 켜면 현재역·다음역을 알 수 있게 합니다.
지하에서는 GPS가 안 되기 때문에, 서버가 서울시 실시간 열차 위치를 받아 **사용자가 탄 열차를 찾아 따라갑니다.**

> 진행 중(2026-11-16 ~ 11-22, 7일). 계획과 일정: [docs/PLAN.md](docs/PLAN.md)

## 전체 구조

```mermaid
flowchart LR
    subgraph Client["NowStation-Android (클라이언트, 3일차부터)"]
        A1[홈·탑승 화면]
        A2[움직임 감지<br/>가속도 센서]
        A3[잠금화면 진행 중 알림]
    end
    subgraph Server["NowStation-Server (이 저장소)"]
        S1[ride<br/>탑승 세션·탄 열차 찾기]
        S2[tracking<br/>열차별 역 변화]
        S3[subway<br/>API 클라이언트·역 데이터]
        S4[recording<br/>녹화·재생]
        DB[(MySQL)]
    end
    API[서울시 실시간<br/>열차 위치 API]

    A1 -- 탑승 시작·출발역 --> S1
    A2 -- 출발/정차 시각 --> S1
    S1 -- 현재역·다음역 --> A3
    S1 --> S2 --> S3
    S3 -- 탑승 중인 노선만 주기적으로 --> API
    S4 -. 녹화한 응답 재생 .-> S2
    S1 --- DB
```

| 저장소 | 하는 일 | 상태 |
|---|---|---|
| **NowStation-Server** (이 저장소) | 실시간 데이터 받기, 탄 열차 찾기·따라가기, 현재역·다음역 계산, 앱에 알려 주기 | 진행 중 |
| NowStation-Android | 시작 버튼, 움직임 감지, 잠금화면 표시 | 3일차부터 |
| NowStation-iOS | 같은 기능(잠금화면·다이내믹 아일랜드) | 다음 단계 |

## 서버 코드 지도

```
src/main/kotlin/com/nowstation/server/
├ NowstationServerApplication.kt   시작점
├ subway/      ✅ 바깥(서울시 API)과 역 데이터
│  ├ SeoulSubwayClient.kt        실시간 API 호출(원문 그대로 받아 옴)
│  ├ RealtimePositionParser.kt   응답 → 열차 위치(노선마다 다른 응답을 한 모양으로)
│  ├ ApiCallBudget.kt            하루 1,000건 한도 세기
│  ├ StationMap(s).kt            노선별 역 순서, 다음 역 계산(2호선 순환·지선 포함)
│  └ SubwayConfig.kt             위 객체들을 Spring에 등록
├ recording/   ✅ 실제 응답 녹화(실기기 없이 테스트·시연하려고)
├ tracking/    ⏳ 2일차: 받아 온 데이터에서 "열차 ○○이 언제 어느 역에 진입·도착·출발" 기록
└ ride/        ⏳ 2일차: 탑승 세션, 탄 열차 찾기(폰 출발 ↔ 열차 출발), 환승, 앱용 API

src/main/resources/
├ application.properties     설정(키는 .env에서 읽음)
└ stations/*.json            노선별 역 순서(scripts/build_stations.py가 녹화와 대조해 생성)

src/test/        규칙 테스트 + 녹화 데이터 재생 시나리오 테스트
scripts/         녹화 분석, 역 데이터 생성(개발용 Python)
docs/PLAN.md     계획·일정
```

의존 방향은 `ride → tracking → subway` 한 방향입니다. 아래쪽은 위쪽을 모릅니다.
판단 규칙(다음 역, 탄 열차 찾기)은 Spring 없이 테스트할 수 있게 순수 Kotlin으로 둡니다.

## 요청 하나의 흐름 (2일차 이후)

1. 앱이 탑승을 시작합니다: `POST /rides` (출발역) → 탑승 세션이 생깁니다.
2. 앱이 폰이 출발·정차한 시각을 보냅니다: `POST /rides/{id}/motion`
3. 서버는 그 노선을 주기적으로 받아(`subway`) 열차별 역 변화를 만들고(`tracking`), 폰이 출발한 순간 그 역을 떠난 열차를 **탄 열차**로 정합니다(`ride`).
4. 앱이 현재역·다음역을 받아 잠금화면에 보여 줍니다: `GET /rides/{id}`

## 확인한 데이터 사실 (1일차 실측)
- 열차 상태는 진입·도착·출발. **수인분당선은 출발을 주지 않습니다.**
- 데이터는 실제보다 보통 30~50초 늦습니다.
- 역 ID는 노선 안에서 연속 번호라 역 순서를 데이터로 만들 수 있습니다.
- 방향 값(`updnLine`)의 뜻을 녹화한 실제 이동 92건으로 확인했습니다.

자세한 내용은 [CLAUDE.md](CLAUDE.md)에 있습니다.

## 실행

필요: JDK 25, Docker

```bash
cp .env.example .env          # 서울 열린데이터광장 "실시간 지하철 인증키"를 넣는다
./gradlew bootRun             # 로컬 MySQL은 Docker로 자동 실행된다
./gradlew test                # 테스트(Testcontainers로 MySQL을 띄운다)

# 실제 운행 데이터 녹화(1시간, 3개 노선 20초 간격 ≈ 540건)
./gradlew bootRun --args='--recording.enabled=true --recording.duration=PT60M --server.port=8081'
```
