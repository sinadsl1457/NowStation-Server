# NowStation 서버는 어떻게 돌아가나

Kotlin 서버를 처음 보는 사람을 위한 설명입니다. Android 앱은 만들어 봤다는 전제로, 앱에서 이미 아는 것과 비교해 가며 씁니다.
여기 나오는 명령은 모두 이 컴퓨터에서 실제로 돌려 본 것이고, 결과도 그때 나온 그대로입니다.

---

## 0. 먼저 지금 상태부터

**이 서버는 아직 어디에도 배포되지 않았습니다.** 지금은 **내 맥에서만** 돌아갑니다.

```
[에뮬레이터 속 앱] ──→ 10.0.2.2:8080 ──→ [내 맥에서 도는 서버 프로그램] ──→ 서울시 API(인터넷)
                                              │
                                              └──→ [내 맥의 Docker 안 MySQL]
```

- 에뮬레이터 안에서 `10.0.2.2`는 "에뮬레이터를 띄운 컴퓨터(내 맥)"를 가리키는 특별한 주소입니다.
- 그래서 맥에서 서버를 끄면 앱에 "서버에 연결할 수 없어요"가 뜹니다(3일차 끝에 실제로 겪은 일).
- 인터넷 어디서나 접속되게 하는 일(배포)은 6일차에 합니다(→ 10절).

---

## 1. "서버가 열렸다"는 게 무슨 뜻인가

서버는 특별한 기계가 아니라 **계속 켜져 있는 프로그램 하나**입니다.
이 프로그램은 컴퓨터의 **8080번 문(포트)** 앞에서 누가 요청을 보내기를 기다립니다. 요청이 오면 답을 돌려주고, 다시 기다립니다.

서버를 켜면 로그에 이 줄이 나옵니다:

```
Tomcat started on port 8080 (http) with context path '/'
Started NowstationServerApplicationKt in 3.977 seconds
```

- **Tomcat**: 8080번 문에서 실제로 요청을 받아 주는 부품입니다. Spring Boot 안에 들어 있어서 따로 설치하지 않습니다.
- 첫 줄이 "8080번 문을 열었다", 둘째 줄이 "준비 끝"이라는 뜻입니다.

지금 누가 8080번 문을 열고 있는지 직접 확인할 수 있습니다:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
# java  65451 ...  TCP *:8080 (LISTEN)   ← java 프로그램(우리 서버)이 기다리는 중

curl localhost:8080/actuator/health
# {"status":"UP", ...}                  ← 살아 있음
```

`/actuator/health`는 우리가 만든 주소가 아닙니다. `spring-boot-starter-actuator`를 넣으면 자동으로 생기는 "살아 있니?" 확인용 주소입니다.

---

## 2. 누가 켜고, 어디서 끄나

### 켜기

```bash
cd ~/dev/NowStation-Server
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew bootRun
```

이 명령 하나가 하는 일:

1. **Gradle**이 Kotlin 코드를 컴파일합니다. Android 빌드에서 쓰던 그 Gradle이고, 설정 파일 `build.gradle.kts`도 같은 모양입니다.
2. 컴파일된 프로그램의 시작점을 실행합니다. 시작점은 `NowstationServerApplication.kt`의 `main` 함수입니다.
   ```kotlin
   fun main(args: Array<String>) {
       runApplication<NowstationServerApplication>(*args)
   }
   ```
   Android 앱에서 `Application.onCreate`가 시작점인 것과 비슷합니다. 다른 점은 서버는 그냥 평범한 `main`에서 시작한다는 것입니다.
3. `runApplication`이 Spring을 깨웁니다. Spring이 하는 일은 3절에서 봅니다.
4. 같은 순간, **MySQL도 Docker로 자동으로 켜집니다.**
   - `compose.yaml`에 "mysql:8.4를 띄워라"라고 적혀 있고, `spring-boot-docker-compose`가 그 파일을 보고 켜 줍니다.
   - Docker는 OrbStack이 켜져 있어야 동작합니다.

### 끄기

- 서버를 켠 터미널에서 **Ctrl+C**를 누르면 꺼집니다.
- 지금은 제가 대화 중에 **백그라운드**로 켜 두었습니다. 그래서 터미널 창이 보이지 않고, 이 대화가 끝나면 같이 꺼집니다.
- 이미 켜진 서버를 찾아서 끄려면 이렇게 합니다:
  ```bash
  lsof -nP -iTCP:8080 -sTCP:LISTEN      # PID 확인
  kill <PID>
  ```

### 켤 때 설정 바꾸기

`--args`로 설정을 덮어씁니다. 녹화 모드가 이렇게 켜집니다:

```bash
./gradlew bootRun --args='--recording.enabled=true --server.port=8081'
```

---

## 3. 켜질 때 Spring이 하는 일: 부품 목록 만들기

**Hilt를 써 봤다면 이미 아는 개념입니다.**
Spring은 켜질 때 코드 전체를 훑어서 특정 표시(어노테이션)가 붙은 클래스를 찾고, 그것들을 **하나씩 만들어 서로 연결**해 둡니다. 이렇게 만들어진 부품 하나를 Spring에서는 **빈(bean)**이라고 부릅니다.

| 표시 | 뜻 | 이 프로젝트에서 |
|---|---|---|
| `@RestController` | 앱의 요청을 받는 창구 | `RideController` |
| `@Service` | 실제 일을 하는 곳 | `RideService` |
| `@Component` | 그냥 부품 하나 | `RidePoller`, `Recorder` |
| `@Configuration` + `@Bean` | "이 객체는 이렇게 만들어라"라는 직접 만든 조립 설명서 | `SubwayConfig` |

부품끼리는 **생성자로 연결**됩니다. Hilt의 `@Inject constructor`와 같은 방식입니다. 서버에서는 표시를 붙이지 않아도 생성자가 하나뿐이면 자동으로 채워 줍니다.

```kotlin
@RestController
class RideController(private val rides: RideService, private val stations: StationMaps)
//                     ↑ Spring이 RideService를 먼저 만들어서 여기 넣어 준다
```

```kotlin
@Service
class RideService(
    private val stationMaps: StationMaps,      // SubwayConfig.stationMaps()가 만든 것
    private val dataSource: TrainDataSource,   // SubwayConfig.trainDataSource()가 만든 것
    private val records: RideRecordRepository, // JPA가 자동으로 만든 것(6절)
    private val clock: Clock,                  // SubwayConfig.clock()
)
```

모든 부품은 **기본적으로 하나씩만** 만들어집니다. Hilt의 `@Singleton`과 같습니다. 그래서 앱 사용자 100명이 동시에 요청해도 `RideService`는 하나이고, 그 안의 탑승 목록(`sessions`)을 모두가 같이 씁니다.

**설정 값 읽기**도 이때 합니다:
- `src/main/resources/application.properties`
- `.env` 파일(API 키. git에 올리지 않음)

`@Value("\${seoul.subway.api-key}")`라고 쓰면 그 자리에 설정 값이 들어옵니다.

---

## 4. 앱의 요청 한 번이 지나가는 길

앱이 5초마다 하는 "지금 무슨 역이야?"를 끝까지 따라가 봅니다.

```
[앱] RideTracker.refresh()
  → RideRepositoryImpl → Retrofit RideApi.get(id)
  → OkHttp가 보냄:  GET http://10.0.2.2:8080/api/rides/{id}
──────────────── 네트워크 ────────────────
[서버] Tomcat이 8080번 문에서 받음
  → Spring이 주소를 보고 맞는 함수를 찾음: RideController.get(id)
  → RideService.view(id): 메모리의 탑승(RideSession)에게 지금 상태를 물어봄
  → RideResponse(Kotlin 객체)를 Jackson이 JSON 글자로 바꿈
  → 응답 200 {"status":"MOVING","thisStation":"역삼",...}
──────────────── 네트워크 ────────────────
[앱] kotlinx.serialization이 JSON을 RideDto로 → RideView → 화면·알림
```

**주소와 함수를 잇는 곳**은 `RideController.kt`입니다. 앱의 Retrofit 인터페이스와 짝을 이룹니다.

| 앱 (Retrofit, `RideApi.kt`) | 서버 (`RideController.kt`) |
|---|---|
| `@POST("api/rides")` | `@PostMapping("/rides")` (클래스에 `@RequestMapping("/api")`) |
| `@POST("api/rides/{id}/motion")` | `@PostMapping("/rides/{id}/motion")` |
| `@GET("api/rides/{id}")` | `@GetMapping("/rides/{id}")` |
| `@DELETE("api/rides/{id}")` | `@DeleteMapping("/rides/{id}")` |
| `@Path("id")` | `@PathVariable id` |
| `@Body request` | `@RequestBody request` |

모양이 거의 같습니다. Retrofit은 "이 주소로 **보낸다**"이고, 서버는 "이 주소로 오면 **받는다**"입니다.

### JSON 변환은 누가 하나

- **서버:** Jackson이 자동으로 합니다. 함수가 Kotlin 객체를 돌려주면 JSON 글자로 바꾸고, 요청 본문(JSON)은 Kotlin 객체로 바꿔서 넣어 줍니다.
- **앱:** kotlinx.serialization이 같은 일을 합니다.
- 양쪽 필드 이름(`rideId`, `thisStation` …)이 같아야 합니다. 이 약속을 적어 둔 곳이 서버 README의 API 표입니다.

### 잘못된 요청은

**모르는 역 이름이 오면:**
1. `RideService`가 `UnknownStationException`을 던집니다.
2. `RideController`의 `@ExceptionHandler`가 그 예외를 받아 **400** 응답으로 바꿉니다.
3. 앱은 이 응답을 `AppError.BadRequest`로 받습니다.

**없는 탑승을 물으면:**
1. 같은 방식으로 **404** 응답이 갑니다.
2. 앱은 이걸 "탑승이 사라졌다"(`RideNotice.Lost`)로 받습니다.

---

## 5. 앱이 묻지 않아도 서버가 혼자 하는 일

서울시 API를 부르는 건 앱이 아니라 **서버가 15초마다 스스로** 합니다.

```kotlin
@Component
class RidePoller(...) {
    @Scheduled(fixedDelayString = "\${ride.poll-interval:PT15S}")   // 15초마다
    fun poll() { ... rides.poll() ... }
}
```

- `@Scheduled`는 Spring에 "이 함수를 정해진 간격으로 불러 줘"라고 하는 표시입니다. Android의 WorkManager 주기 작업과 비슷한 역할입니다.
- 이 표시가 동작하려면 시작점에 `@EnableScheduling`이 있어야 합니다(`NowstationServerApplication.kt`).

15초마다 흐르는 길:

```
RidePoller.poll()
  → RideService.poll()
      1. linesInUse(): 지금 탑승 중인 사람에게 필요한 노선만 고른다(아무도 없으면 빈 목록 → API 안 부름)
      2. 노선마다 TrainDataSource.fetch(노선)
           → SeoulSubwayClient.fetchRaw(): ApiCallBudget으로 오늘 남은 횟수 확인 → RestClient로 서울시 API 호출
           → RealtimePositionParser: 응답 JSON → 열차 위치 목록
      3. TrainTracker.update(): 지난번과 비교해 "열차 ○○ 출발/도착" 사건을 만든다
      4. 각 RideSession.onTrainEvents(): 내 탑승과 관련 있나? (탄 열차 찾기·따라가기)
      5. 각 탑승의 마지막 상태를 MySQL에 적는다
```

**서버가 바깥으로 요청을 보내는 곳은 `SeoulSubwayClient.kt` 한 곳뿐입니다.**
- 여기서 쓰는 `RestClient`는 서버판 Retrofit/OkHttp라고 보면 됩니다.
- 앱의 OkHttp는 우리 서버에 보내고, 서버의 RestClient는 서울시에 보냅니다.

**API 키를 앱이 아니라 서버가 갖는 이유:**
- 하루 1,000건 한도를 앱 사용자 모두가 나눠 써야 합니다. 서버가 모아서 노선마다 한 번만 부르면 됩니다.
- 앱에 키를 넣으면 앱 파일을 열어 키를 빼낼 수 있습니다.

로그에서 보이는 줄:

```
RidePoller : 받아 옴: 수인분당선 (오늘 22건)
```

---

## 6. 데이터는 어디에 있나: 메모리 vs MySQL

| 무엇 | 어디 | 서버를 끄면 |
|---|---|---|
| 진행 중인 탑승(탄 열차, 폰 움직임 기록) | **메모리**: `RideService.sessions`(Map) | **사라짐** |
| 열차들의 최근 위치 | **메모리**: `TrainTracker` | 사라짐 |
| 탑승 기록(언제 어디서 시작·끝, 마지막 열차) | **MySQL**의 `ride` 테이블 | 남음 |

진행 중인 탑승을 메모리에 두는 이유:
- 15초마다 모든 탑승을 다시 판단하는데, 매번 DB를 읽고 쓰면 느리고 복잡해집니다.
- 그 대가로 서버를 다시 켜면 진행 중이던 탑승이 사라집니다. 이때 앱은 404를 받고 "탑승이 끝났다"고 알립니다.

### MySQL과 연결되는 방식

- **테이블 만들기(Flyway):** `src/main/resources/db/migration/V1__create_ride.sql`에 `CREATE TABLE`이 적혀 있습니다. 서버가 켜질 때 Flyway가 이 파일을 아직 안 돌렸으면 돌립니다. 나중에 표를 바꾸면 `V2__...sql`을 새로 추가합니다(이미 돌린 파일은 고치지 않음).
- **읽고 쓰기(JPA):** Android의 Room과 같은 역할입니다.
  ```kotlin
  @Entity @Table(name = "ride")
  class RideRecord(...)                                           // Room의 @Entity

  interface RideRecordRepository : JpaRepository<RideRecord, String>   // Room의 @Dao
  ```
  인터페이스만 쓰면 `save`, `findById` 같은 함수를 JPA가 자동으로 만들어 줍니다.
- **바꾼 값 저장:** `@Transactional`이 붙은 함수 안에서 `record.endedAt = ...`처럼 값을 바꾸면, 함수가 끝날 때 자동으로 DB에 저장됩니다.

### DB 안을 직접 보기

```bash
docker exec nowstation-server-mysql-1 mysql -unowstation -pnowstation nowstation \
  --default-character-set=utf8mb4 \
  -e "select origin_station, destination_station, started_at, ended_at from ride order by started_at desc limit 2"

# origin_station  destination_station  started_at               ended_at
# 선릉             강남                  2026-11-16 01:49:51.065  2026-11-16 01:49:51.147
# 가천대           NULL                  2026-11-16 01:45:18.560  2026-11-16 01:46:27.978
```

시각이 9시간 이르게 보이는 건 **UTC**(세계 표준시)로 저장하기 때문입니다. 01:45는 한국 시각 10:45입니다. 서버는 어디에 배포될지 모르니 표준시로 저장하고, 보여 줄 때 한국 시각으로 바꿉니다.

---

## 7. 비밀 값과 설정

| 파일 | git에 올라가나 | 내용 |
|---|---|---|
| `application.properties` | 올라감 | 공개돼도 되는 설정(15초 간격, 서울시 API 주소) |
| `.env` | **안 올라감**(`.gitignore`) | `SEOUL_SUBWAY_KEY=...` 실제 키 |
| `.env.example` | 올라감 | "이런 키가 필요하다"는 빈 양식 |

`application.properties`의 이 두 줄이 연결 고리입니다:

```properties
spring.config.import=optional:file:.env[.properties]   # .env도 설정으로 읽어라
seoul.subway.api-key=${SEOUL_SUBWAY_KEY:}               # 그 안의 SEOUL_SUBWAY_KEY를 쓴다
```

배포할 서버 컴퓨터에는 `.env` 파일 대신 **환경 변수**로 키를 넣습니다. `${SEOUL_SUBWAY_KEY}`는 어느 쪽에서 오든 읽힙니다.

---

## 8. 서버를 켜지 않고 어떻게 테스트하나

| 테스트 | 무엇을 | 서버·DB 필요? |
|---|---|---|
| `RideSessionTest`, `TrainTrackerTest`, `StationMapTest` … | 판단 규칙(탄 열차 찾기, 다음 역) | 아니요. 순수 Kotlin이라 Spring 없이 바로 |
| `RecordedScenarioTest` | 실제로 녹화한 아침 데이터를 넣어 놓침·환승·하차 알림 | 아니요 |
| `SeoulSubwayClientTest` | 서울시 API 호출 모양 | 아니요. 가짜 서울시(`MockRestServiceServer`) |
| `RideApiTest` | 앱처럼 주소로 요청을 보내 처음부터 끝까지 | 예. **Testcontainers**가 테스트 때만 MySQL을 띄우고 끝나면 지운다 |

판단 규칙을 Spring에서 떼어 순수 Kotlin(`RideSession`, `TrainTracker`)으로 둔 이유가 바로 이것입니다. 그래야 서버 없이 0.1초 만에 확인할 수 있습니다.

```bash
./gradlew test
```

---

## 9. 직접 해 보기 (서버가 켜져 있을 때)

앱 없이 터미널에서 앱이 하는 일을 똑같이 해 볼 수 있습니다.

```bash
# 역 검색 (한글은 --data-urlencode로 보내야 한다)
curl -s -G localhost:8080/api/stations --data-urlencode "query=선릉"
# [{"name":"선릉","lines":["2호선","수인분당선"]}]

# 탑승 시작 → rideId를 받는다(응답 코드 201)
curl -s -X POST localhost:8080/api/rides \
  -H 'Content-Type: application/json' \
  -d '{"originStation":"선릉","destinationStation":"강남"}'
# {"rideId":"bccd500f-...","status":"WAITING","thisStation":"선릉",...}

# 지금 상태(앱이 5초마다 하는 것)
curl -s localhost:8080/api/rides/<rideId>

# 끝내기 → 204(내용 없음, 성공)
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8080/api/rides/<rideId>

# 끝낸 탑승을 다시 물으면 404
curl -s localhost:8080/api/rides/<rideId>
# {"detail":"없는 탑승: bccd500f-...","status":404,"title":"Not Found",...}
```

탑승을 시작해 두면 서버 로그에 15초마다 `받아 옴: 2호선, 수인분당선 (오늘 n건)`이 찍히고, 끝내면 멈춥니다(5절의 `linesInUse`).

### 재생 모드: 같은 서버를 "그날 아침"으로 되돌려 켜기

```bash
./gradlew bootRun --args='--replay.enabled=true --replay.start=2026-11-16T09:42:00+09:00'
```
- 이렇게 켜면 두 부품만 바뀌어 끼워진다(3절의 부품 목록): **시계**(`Clock`)와 **열차 데이터 출처**(`TrainDataSource`).
  - 시계: 지금을 그날 09:42로 보고 실제 속도로 흐른다
  - 데이터 출처: 서울시 API 대신 그 시각까지 녹화된 응답
- 나머지(Controller, RideService, 판단 규칙)는 한 줄도 다르지 않다. 처음부터 시각을 `Instant.now()`로 직접 읽지 않고 주입받은 `Clock`으로 읽게 만든 이유가 이것이다.
- 어느 쪽을 끼울지는 `ReplayConfig`/`SubwayConfig`의 `@ConditionalOnProperty("replay.enabled")`가 정한다.
- `scripts/demo/run_scenario.py`는 이 모드로 서버를 켜고 에뮬레이터 센서까지 맞춰 돌린다(README).

---

## 10. 배포: 아직 안 했고, 6일차에 할 일

지금 "서버를 어디서 제어하나"의 답은 **내 맥의 터미널**입니다. 배포는 이 프로그램을 **항상 켜져 있는 다른 컴퓨터(AWS)**로 옮기는 일입니다.

순서(계획):

1. **실행 파일 하나로 묶기**
   - `./gradlew bootJar`를 돌리면 `build/libs/nowstation-server-*.jar` 하나가 나옵니다.
   - 이 파일 하나에 우리 코드, Spring, Tomcat이 모두 들어 있습니다.
   - Java만 있으면 `java -jar 그파일.jar`로 어디서든 켜집니다. 지금의 `bootRun`과 같은 일을, Gradle 없이 하는 것입니다.
2. **AWS에 컴퓨터 빌리기**
   - 서버 프로그램을 켤 컴퓨터를 하나 빌립니다(EC2).
   - MySQL은 AWS가 관리해 주는 DB(RDS)를 쓰거나, 같은 컴퓨터에 Docker로 띄웁니다.
3. **jar를 올리고 켜기**
   - 키는 환경 변수로 넣습니다(7절).
   - 꺼지면 자동으로 다시 켜지게 설정합니다.
4. **인터넷에 문 열기**
   - AWS 방화벽에서 8080번(또는 443번)을 엽니다.
   - 공개 주소가 생깁니다(예: `http://13.x.x.x:8080`, 나중에는 도메인 + https).
5. **앱 주소 바꾸기**
   - 앱의 `local.properties`에서 `API_BASE_URL=<공개 주소>`로 바꿉니다.
   - 지금은 이 값이 없어서 `10.0.2.2:8080`(내 맥)이 기본값입니다.

배포 뒤에는 서버를 제어하는 방법이 이렇게 바뀝니다:
- 켜고 끄기는 AWS 컴퓨터에 원격 접속(SSH)해서 합니다.
- 로그도 그 컴퓨터에서 봅니다.
- 상태는 `<공개 주소>/actuator/health`로 확인합니다.

자세한 방법은 6일차에 이 문서에 이어서 적습니다.

---

## 11. Android ↔ 서버 대응표

| Android 앱에서 아는 것 | 서버에서는 | 이 프로젝트 파일 |
|---|---|---|
| `Application.onCreate` | `main()` → `runApplication` | `NowstationServerApplication.kt` |
| Hilt `@Inject constructor`, `@Module @Provides` | 생성자 주입, `@Configuration` + `@Bean` | `SubwayConfig.kt` |
| `@Singleton` | 모든 빈이 기본 하나 | `RideService` |
| Retrofit 인터페이스(보내기) | `@RestController`(받기) | `RideController.kt` |
| Retrofit/OkHttp로 바깥 API 부르기 | `RestClient` | `SeoulSubwayClient.kt` |
| kotlinx.serialization | Jackson | 자동 |
| Room `@Entity` / `@Dao` | JPA `@Entity` / `JpaRepository` | `RideRecord.kt` |
| Room migration | Flyway `V1__*.sql` | `db/migration/` |
| WorkManager 주기 작업 | `@Scheduled` | `RidePoller.kt` |
| `local.properties`(키) | `.env` + `application.properties` | |
| 에뮬레이터에서 실행 | `./gradlew bootRun`(내 맥에서 실행) | |
| Play 스토어 배포 | jar를 AWS 컴퓨터에 올려 실행 | 6일차 |

---

## 12. 코드 읽는 순서 추천

1. `NowstationServerApplication.kt`: 시작점(10줄)
2. `ride/RideController.kt`: 앱이 부를 수 있는 주소 전부
3. `ride/RideService.kt`: 주소마다 실제로 하는 일, 15초마다 하는 일
4. `ride/RidePoller.kt`: 15초 타이머
5. `subway/SubwayConfig.kt` → `subway/SeoulSubwayClient.kt`: 부품 조립, 서울시 API 부르기
6. `tracking/TrainTracker.kt` → `ride/RideSession.kt`: 핵심 판단 규칙(여기부터는 Spring이 없는 순수 Kotlin)
7. `src/test/.../RideSessionTest.kt`: 규칙이 어떤 상황을 다루는지 테스트 이름만 읽어도 보입니다

전체 흐름을 먼저 잡고 싶으면 13절(탑승 한 번을 시간 순서로)부터 읽어도 됩니다.

---

## 13. 탑승 한 번을 시간 순서로 따라가기 (태평 → 야탑)

`scripts/demo/run_scenario.py yatap`으로 실제로 돌린 기록입니다. 시각은 녹화 속 시각(2026-11-16 오전)입니다.
**앱이 하는 일**, **서버가 하는 일**, **서울시 데이터에 찍힌 일**이 어떻게 맞물리는지 보세요.

| 시각 | 어디서 | 무슨 일 |
|---|---|---|
| 09:45:30 | 앱 | 태평 → 야탑 "시작" → `POST /api/rides` |
| | 서버 | 탑승 세션 생성: "태평에서 기다림". 받아 올 노선 = 태평을 지나는 **수인분당선만** |
| 09:46:55 | 데이터 | 6073 열차가 태평에 **도착**으로 찍힘 |
| 09:47:05 | 폰 | 열차가 출발하며 가속 → 앱의 움직임 판단이 4초 동안 이어지는지 보고 "출발(09:47:05)"을 서버로 보냄 |
| | 서버 | 폰 출발 시각을 기록만 함. 아직 어느 열차인지 모름(수인분당선은 "출발" 상태를 주지 않음) |
| 09:47:45 | 폰 | 감속이 끝남 → "정차(09:47:45)" 보냄 |
| 09:47:55 | 데이터 | 6073이 **모란에 진입**으로 찍힘 |
| | 서버 | "6073이 태평을 떠났구나"라고 판단(`TrainTracker`). 그 출발 앞뒤(09:44:55~09:48:10)에 **폰도 출발했나?** → 09:47:05에 출발함 → **6073에 탔다**(`RideSession`) |
| ~09:48 | 앱 | 5초마다 `GET /api/rides/{id}` → "이번 역 모란 · 정차 중 · 다음 역 야탑" |
| 09:48:20 | 폰 | 모란에서 다시 출발 |
| 09:50:57 | 데이터 | 6073이 **야탑에 진입** |
| | 서버 | 이번 역 = 야탑 = 내릴 역 → `prepareToAlight: true` |
| ~09:51 | 앱 | "곧 야탑, 내릴 준비하세요" + 진동 + 팝업 알림 |

**같은 시간에 지나간 다른 열차를 고르지 않은 이유:**
6071은 09:44:33에 태평을 떠났습니다. 그 앞뒤에 폰은 움직이지 않았습니다. 탑승을 시작하기도 전입니다. 그래서 탄 열차 후보가 아닙니다.
열차를 "몇 분에 오는 열차"로 미리 정하지 않습니다. **"폰이 출발한 순간 그 역을 떠난 열차"**로 정합니다. 그래서 열차를 놓쳐도, 열차가 늦어도 맞게 따라갑니다.

**알림이 열차가 진입한 뒤에야 오는 이유:**
서울시 데이터가 실제보다 30~50초 늦게 찍히기 때문입니다. 그래서 내릴 역 알림은 "그 역에 다가가는 중"이 데이터에 보이는 순간에 냅니다.

---

## 14. 앱이 안 보여도 알림이 바뀌는 원리 (Android)

사용자는 탑승 중에 YouTube를 보거나 폰을 잠가 둡니다. 그래도 알림이 바뀌는 건 아래 네 가지 덕분입니다.

1. **포그라운드 서비스**(`RideService`)
   - "알림을 달고 있는 동안은 이 작업을 멈추지 말아 달라"고 시스템과 한 약속입니다.
   - 화면(Activity)이 사라져도 서비스는 살아 있습니다. 5초마다 서버에 묻고, 진행 중 알림을 고쳐 씁니다.
   - 탑승 상태는 화면이 아니라 `RideTracker`(@Singleton)가 갖고 있습니다. 그래서 화면이 없어도 상태가 남습니다.
2. **CPU 깨워 두기**(WakeLock)
   - 화면이 꺼지면 폰은 CPU를 재우려 합니다. 그러면 센서 읽기와 5초 갱신이 멈춥니다.
   - 그래서 탑승 중에만 "CPU는 깨워 둬"를 잡아 둡니다. 최대 3시간이고, 탑승을 끝내면 풉니다.
3. **알림 채널 두 개**
   - 진행 중 알림: 5초마다 글자가 바뀌니 **조용히**(소리·팝업 없음).
   - 내릴 역 알림: 딱 한 번 **중요하게**. 다른 앱 위로 팝업이 내려오고, 진동은 무음 모드에서도 울리게 알람 용도로 냅니다.
   - 채널을 나눠 두면 사용자가 설정에서 하나만 끌 수도 있습니다.
   - 잠긴 폰에서는 팝업이 올라와도 화면이 저절로 켜지지는 않았습니다(에뮬레이터). 실기기는 "알림 오면 화면 켜기" 설정에 따라 다릅니다. 그래서 잠긴 폰에서 확실한 신호는 **진동**입니다. 화면을 강제로 켜는 "전체 화면 알림"은 Android 14부터 전화·알람 앱에만 허용돼 쓰지 않습니다.
4. **Live Update**(Android 16+)
   - 진행 중 알림을 "중요한 진행 상황"으로 올려 달라고 요청합니다.
   - 시스템이 받아 주면 상태 표시줄 왼쪽 위에 칩(🚆 야탑)이 붙고, 잠금화면 맨 위에 고정됩니다.

시연에서 확인한 것(`--mode=background`, `--mode=locked`):
- 앱 화면 대신 **시스템에 실제로 올라간 알림의 글자**를 읽어 비교했습니다(`adb shell dumpsys notification`).
- 진동은 시스템 진동 기록으로 확인했습니다(`dumpsys vibrator_manager`).

---

## 15. 실기기 없이 화면까지 확인하는 원리

앱이 동작하는 데 필요한 바깥 입력은 넷입니다. 시연 스크립트는 이 넷을 모두 손에 쥐고 같은 시계에 맞춥니다.

| 바깥 입력 | 실제로는 | 시연에서는 |
|---|---|---|
| 지금 시각 | 진짜 시계 | 서버 재생 모드의 시계(그날 아침부터 실제 속도로) |
| 열차 위치 | 서울시 실시간 API | 그날 아침 받아 둔 응답(`*.jsonl`)을 그 시각대로 |
| 폰 움직임 | 가속도 센서 | `adb emu sensor set acceleration`으로 녹화 시각에 맞춰 값 넣기 |
| 지하 끊김 | 터널 | `adb shell svc wifi disable` |

확인은 정해 둔 순간마다 이렇게 합니다:
- 앱 화면이면 화면 글자를 읽습니다(`uiautomator dump`).
- 다른 앱을 보거나 폰이 잠긴 상황이면 알림 글자를 읽습니다.
- 둘 다 기대한 글자가 있는지 비교하고, 스크린숏을 남깁니다.

실제 속도로 돌기 때문에 도중에 맥이 잠들면 시각이 어긋납니다(한 번 실제로 실패했습니다). 그래서 스크립트가 끝날 때까지 맥을 깨워 둡니다.
