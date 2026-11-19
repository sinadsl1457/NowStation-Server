# NowStation Server

지하철 승객이 탄 열차를 찾아 따라가며 현재역·다음역을 알려 주는 서버. 클라이언트는 NowStation-Android(먼저), iOS(나중).
계획과 일정은 `docs/PLAN.md`.

## 스택
- Kotlin + Spring Boot 4, Spring Data JPA, MySQL 8.4, Gradle(Kotlin DSL), JDK 25
- 로컬 MySQL은 `compose.yaml`(Spring Boot Docker Compose 연동이 실행 때 자동으로 띄움), 테스트는 Testcontainers
- JDK: 시스템 Java가 없어 Android Studio 내장 JDK를 쓴다
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

## 배포
- AWS 시드니 EC2 t3.micro(x86, 1GB + 스왑 2GB, 탄력적 IP), Docker로 서버 + MySQL + Caddy(https, `compose.prod.yaml`). 무료 플랜 계정이라 리전은 시드니 고정
- https 주소는 무료 `<IP를 -로>.sslip.io`(Caddy가 Let's Encrypt 자동 발급·갱신). 고정 IP가 바뀌면 주소도 바뀐다
- 배포: main에 push하면 GitHub Actions(테스트 → GHCR 이미지) → EC2의 systemd 타이머가 1분마다 pull해 교체(`scripts/auto-deploy.sh` → `remote-deploy.sh`, 실패 시 되돌림) → Actions가 `/actuator/info` 버전으로 확인
- pull 방식인 이유: 무료 플랜이 GitHub OIDC(push 방식)를 조직 정책으로 막음. GitHub에 서버·AWS 키를 두지 않는다
- 진행 중인 탑승은 메모리(RideSession)에 있고, 단계는 바뀔 때마다 `ride` 테이블에 적는다. 메모리에 없는 탑승 요청은 DB로 이어 간다(배포로 서버가 바뀌어도 앱이 안 끊기게). 단계 모양을 바꾸면 `RideService.resume`·`write`도 같이 고친다
- EC2는 이미지만 받는다(서버에서 빌드하지 않음: 메모리 1GB). 배포 설정 파일도 이미지 안 `/deploy`에서 꺼낸다
- 비밀 값은 EC2 `~/nowstation/.env`. EC2 IP는 저장소에 적지 않는다
- 키: `~/.ssh/nowstation.pem`, SSH는 보안 그룹에서 내 IP만

## 외부 API — 서울시 실시간 열차 위치정보
- `GET {base}/{key}/json/realtimePosition/{start}/{end}/{호선명}` (예: 2호선, 수인분당선, 신분당선)
- 키는 "실시간 지하철 인증키"(일반 인증키와 별도). `.env`의 `SEOUL_SUBWAY_KEY`, 예시는 `.env.example`
- **하루 1,000건 한도.** 탑승 중인 사람이 있는 노선만, 정해진 간격으로만 받는다. 호출 수를 세서 한도 전에 간격을 늘린다.
- 1일차 실측(2026-11-16 07:4x, 평일 출근 시간):
  - 열차 상태 `trainSttus`: 0 진입, 1 도착, 2 출발. **수인분당선은 2(출발)를 주지 않는다** → 출발은 "다음 역 진입"으로 판단
  - 기록 시각 `recptnDt` 지연: 2호선 중간 34초(최대 93초), 수인분당선 중간 49초(최대 295초)
  - **신분당선 `recptnDt`는 열차마다가 아니라 응답 생성 시각**이라 믿지 않는다 → 상태가 바뀐 시각은 서버가 받은 시각으로 기록
  - 역 ID(`statnId`)는 노선 안에서 연속 번호다(수인분당선 1075075209 청량리 ~ 1075075271 인천). 역 순서 = ID 순서
  - `updnLine` 뜻(녹화한 이동 92건으로 확인): 2호선 0 = ID 증가(내선), 1 = 감소 / 수인분당·신분당 0 = ID 감소(왕십리·신사 방면), 1 = 증가
  - 역 데이터는 `scripts/build_stations.py`로 녹화와 대조해 만든다. 녹화에 아직 없는 역 이름은 공식 순서 목록에서 채운다

## 구조
```
com.nowstation.server
├ subway/      역·노선 데이터, 실시간 API 클라이언트, 노선별 차이를 흡수해 "열차 상태"로 바꾸는 곳
├ tracking/    열차 추적: 받아 온 데이터에서 열차별 역 변화를 만든다
├ ride/        탑승 세션: 탄 열차 찾기(폰 움직임 ↔ 열차 출발), 환승, 현재역·다음역 계산, API
└ recording/   실시간 응답 녹화·재생(실기기 없이 테스트·시연)
```
- 의존 방향: `ride → tracking → subway`. 아래쪽은 위쪽을 모른다.
- 기능 폴더는 "상태를 누가 갖고 언제 바뀌나"로 나눈다. 같은 API를 쓴다는 것만으로 합치지 않는다.
- 경로는 `RoutePlanner`가 하나로 정한다(지도 앱 "지하철만" 첫 결과처럼: 정거장 평균 시간 + 환승 5분). 방향·환승역·내릴 준비 기준(구간 끝)이 여기서 나온다
- 탄 열차는 사용자가 고르고(서버가 시작·환승역에서 다음에 들어올 열차를 골라 둠, 사용자는 가로 노선도에서 바꿈) **고른 열차를 그대로 따라간다**. 폰 움직임으로 열차를 바꾸거나 "놓쳤다"고 판단하지 않는다(사용자 결정 2026-11-19: 탔으면 가는 것). 따라가는 열차가 구간 끝(환승역)에 오면 TRANSFER 알림 + 다음 구간 대기
- 폰이 느낀 정차·출발(`움직임` 로그)과 따라가는 열차의 공공 데이터 관측(`열차` 로그)을 둘 다 남긴다: 센서가 공공 데이터보다 얼마나 빠르고 맞는지 실측해서 차별점으로 쓸지 정한다
- 판단 규칙(탄 열차 찾기, 다음역 계산, 노선별 상태 해석)은 Spring 없이 테스트할 수 있는 순수 Kotlin으로 둔다.
- 시간은 `java.time.Clock`을 주입받는다(`Instant.now()` 직접 호출 금지). 녹화 재생·테스트에서 시계를 바꿔 끼운다.

## 작업 방식
- 커밋은 기능 하나씩. 피드백을 반영한 커밋은 메시지에 무엇을 반영했는지 쓴다. squash·amend·force push 금지.
- 버그를 고치면 고치기 전에 실패하는 테스트로 고정한다.
- 시나리오 테스트는 실제 녹화 데이터로 만든다: 정상 / 열차 놓침 / 임시 정차 / 환승 / 데이터 끊김 / 하차 알림.
- 화면까지 확인은 `scripts/demo/run_scenario.py <시나리오>`(재생 모드 서버 + 에뮬레이터 센서·네트워크 조작 + 화면 글자 대조). 폰 움직임 시각은 RecordedScenarioTest와 같게 둔다.
- 재생 모드: `--replay.enabled=true --replay.start=2026-11-16T09:42:00+09:00`. 시계·열차 데이터 출처만 바뀌고 나머지 코드는 같다.
- 설계가 바뀌면 이 파일과 `docs/PLAN.md`를 같이 고친다.
- 주석은 결정과 이유만 짧게. 코드를 그대로 읽어 주는 설명은 쓰지 않는다.
