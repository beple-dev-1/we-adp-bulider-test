# 코딩 규약 — 이 저장소는 코드를 어떻게 쓰나

> **이 문서가 답하는 질문 하나: 이 저장소에서 코드를 어떤 법으로 쓰나.**
> 앞 절은 **이름을 어느 말로 짓나**, 뒤 절은 **자바·스프링 부트를 어떻게 쓰나**다.
> *무엇을 만드나*는 `docs/artifacts.md`, *DB 가 무엇을 담나*는 `docs/data-model.md` 가 답한다.
>
> **날짜**: 2026-08-13. **결정은 병주 확정.**

---

## 법 하나

> **기계가 부르는 이름은 영문. 사람에게 하는 말은 한글.**

**새 원칙이 아니다.** `data-model.md` §0 규칙 6·7 이 DB 에 대해 이미 같은 것을 정했다 —
「열 이름은 영문 snake_case」 · 「뜻은 한글 COMMENT 로 적는다. 자세히 적는다」.
**그 법을 자바와 화면에도 그대로 편다.** 규칙이 하나면 어긋날 자리가 없다.

⚠️ **한글을 줄이자는 규약이 아니다.** 이 저장소는 설명을 한글로 자세히 적는 것이 관례이고
그것은 **그대로 간다.** 바뀌는 것은 **부르는 이름**뿐이다.

---

## 어디에 무엇을 쓰나

| 자리 | 무엇 | 예 |
|---|---|---|
| **클래스 · 인터페이스 · enum · record** | 영문 PascalCase | `DocumentReadCheck` · `ReadVerdict` |
| **메서드** | 영문 camelCase | `register(...)` · `canRead()` |
| **필드 · 지역변수 · 매개변수** | 영문 camelCase | `documentType` · `preparationState` |
| **enum 상수** | 영문 SCREAMING_SNAKE_CASE | `MEETING_MINUTES` · `REVIEW_REQUIRED` |
| **상수** | 영문 SCREAMING_SNAKE_CASE | `MIN_TEXT_RATIO` |
| **화면 모델 키 · 조각 인자** | 영문 camelCase | `${title}` · `layout(title, shape, current, content)` |
| **DB 표 · 열** | 영문 snake_case (`adk_builder_` 프리픽스) | `adk_builder_received_document.preparation_state` |
| **URL 경로 · 쿼리 이름** | 영문 kebab-case | `/artifacts/received-docs` |
| **폼 필드 `name`** | 영문 camelCase | `name="documentType"` |
| | | |
| **주석 · Javadoc** | **한글** | `/** 올린 파일에서 글자가 실제로 나오나를 잰다. */` |
| **DB COMMENT** | **한글** | `comment on column … is '…'` |
| **테스트 메서드 이름** | **한글** | `void 못_읽는_파일도_등록은_되고_상세에_까닭이_뜬다()` |
| **화면에 뜨는 글** | **한글** | `문서 등록` · `처리 대기` |
| **예외 메시지 · 로그** | **한글** | `throw new IllegalArgumentException("문서명을 입력해 주세요.")` |
| **커밋 메시지 · 문서** | **한글** | |

---

## 예외로 못 박은 것 셋

### 1. 테스트 메서드 이름은 한글이다

⛔ **바꾸지 마라.** 테스트 이름은 **호출되는 이름이 아니라 「무엇을 보장하나」를 적는 문장**이다.
실패하면 그 문장이 보고서에 그대로 찍혀 사람이 읽는다.

```java
@Test
void 못_읽는_파일도_등록은_되고_상세에_까닭과_다음_행동이_뜬다() { … }
```

⚠️ **테스트 안의 변수와 도구 메서드는 영문이다.** 예외는 **`@Test` 가 붙은 메서드의 이름 하나**뿐이다.
`private Project 준비된_프로젝트(…)` 같은 도구는 호출되는 이름이라 영문으로 쓴다.

### 2. enum 상수는 영문이고 한글은 표시 이름으로 든다

DB 에 나가는 코드값과 화면에 뜨는 말을 **가른다.** 목업 `_README` 가 이미
「구현할 때 코드값과 표시 이름을 분리한다」고 적어 뒀다.

```java
public enum DocumentType {
    MEETING_MINUTES("회의록"),
    WORK_REQUEST("과업요청서"),      // ⛔ 밖에서 받는 문서 이름이라 「과업」 금지의 예외다
    PROPOSAL("제안서"),
    OTHER("기타");

    private final String label;
    public String label() { return label; }
}
```

⭐ **가르면 표시 이름을 고칠 때 DB 가 안 움직인다.** 붙여 두면 화면 문구 하나를 다듬는 데
마이그레이션이 딸려온다.

⛔ **DB `CHECK` 도 영문 코드값을 검사한다.** 한쪽만 바꾸면 저장이 조용히 거절된다.

### 3. 한글이 이미 나간 자리는 그대로 둔다

**주소와 DB 값은 밖으로 새어 나간 이름**이다. 바꾸려면 사람이 쓰던 링크와 앉은 데이터가 같이 움직인다.
⚠️ **아직 아무 데도 안 나갔을 때만 싸다** — 지금이 그때라 `V4` 는 영문 코드값으로 고쳤다.
운영에 한 번이라도 나간 뒤에는 **바꾸지 않고 새 이름을 옆에 세운다.**

---

## 이름을 지을 때

- **줄이지 않는다** — `doc` 이 아니라 `document`, `req` 가 아니라 `request`.
  ⚠️ 단 `FRD-`·`DR-`은 **현재 산출물 번호의 형식**이라 그대로 쓴다(`data-model` §4).
  `REQ-`·`RD-`·`BRD-`는 과거 데이터나 이력을 가리킬 때만 쓴다.
- **뜻이 안 서면 주석에 한글로 적는다.** 이름을 길게 늘여 설명하려 하지 않는다 —
  영문 이름이 잃는 「보면 안다」는 **주석이 도로 채운다.** 그래서 짧게 적지 않는다.
- **같은 것을 두 이름으로 부르지 않는다.** 화면·컨트롤러·DB 가 같은 것을 가리키면 이름을 맞춘다 —
  `preparationState` ↔ `preparation_state` ↔ `${preparationState}`.

---

## 자동으로 잡히나

✅ **빌드가 잡는다** (2026-08-14 부터). 자바는 유니코드 식별자를 허용하므로 컴파일러는 안 막는다 —
그래서 테스트로 막는다: `src/test/java/com/bizplay/builder/convention/KoreanIdentifierTest.java`.

`mvnw test` 가 `src/main/java` 와 `src/test/java` 를 전부 읽어 **주석과 문자열 리터럴을 걷어낸 뒤**
남은 한글을 위반으로 본다. 걸리면 `파일:줄:내용` 을 찍고 빌드가 깨진다.

| 넘어가는 것 | 왜 |
|---|---|
| 주석 (`//` · `/* */`) | 설명하는 글은 한글이다 |
| 문자열 (`"…"` · `'…'` · `"""…"""`) | 예외 메시지 · enum 표시 이름 · 테스트 데이터가 전부 여기 든다 |
| `@Test` 가 붙은 메서드의 **이름 한 토큰** | 예외 ① — 아래 「예외로 못 박은 것 셋」 |

⚠️ **예외는 이름 자리 하나만 도려낸다.** `void 무엇을_보장한다() { int 개수 = 1; }` 에서 `개수` 는 그대로 걸린다.
`@Test` 와 메서드 선언 사이에 다른 애너테이션이 끼어도 예외가 유지된다(`CloneWorkerTest` 가 그 모양이다).

**새 의존성은 0이다** — Checkstyle 을 쓰지 않았다. 이 기계는 사내 Nexus 미러를 거치고 회사가 TLS 를 가로채
새 플러그인을 받는 것 자체가 이 검사의 최대 실패 위험이었다.

⚠️ **사람이 지키는 몫이 사라진 것은 아니다.** 검사기가 보는 것은 **식별자에 한글이 들었나** 하나뿐이라,
`getData` 같은 뜻 없는 영문 이름은 못 잡는다. 규칙을 어긴 것이 하나 들어오면
**다음 사람이 그것을 보고 따라 한다** — 그렇게 122군데가 됐다.

---

---

# 자바 · 스프링 부트

> Spring Boot 3.5 · **Java 17** · MyBatis · Flyway · Thymeleaf · PostgreSQL 17.
> 아래는 **이 저장소가 이미 지키고 있는 것을 받아 적은 것**이다 — 새로 지어낸 규칙이 아니다.

## 뼈대

| 규칙 | 왜 |
|---|---|
| **패키지는 기능으로 가른다** — `account` · `project` · `intake` · `claude` · `ai` · `artifact` · `checker` | 계층(`controller`/`service`/`repository`)으로 가르면 한 기능을 고칠 때 세 폴더를 오간다 |
| **업무를 모르는 것은 기능 밖으로 뺀다** — `git` · `secret` · `id` · `shell` · `web` · `config` | 판정 기준 하나: **그 파일이 어떤 업무의 타입(`Account`·`Project`…)이나 저장소를 쓰나.** 쓰면 그 기능 안에 남고, 안 쓰면 밖으로 나간다 |
| ⛔ **`common` 이라는 패키지를 만들지 않는다** | 「공용」은 *무엇인가*가 아니라 *어디에도 안 맞는다*는 뜻이라 넣을지 말지 판정할 기준이 없다. 시간이 지나면 애매한 것이 전부 거기로 간다. 위 여섯처럼 **하는 일로 이름을 붙인다** |
| ⛔ **Lombok 을 쓰지 않는다** | 지금 의존성에 없다. `record` 와 자바 17 이 대부분을 덮는다 — 애너테이션이 만든 코드를 디버거로 못 보는 값을 치르지 않는다 |
| **DTO·값 묶음은 `record`** | 지금 13개가 그렇다 |
| **자바 17 이다** — `record` · `switch` 식 · 텍스트 블록 · `instanceof` 패턴은 산다 | ⛔ **21 전용은 못 쓴다** — record 패턴 · `switch` 패턴 매칭 · 가상 스레드. 2026-08-13 에 21 → 17 로 내렸다(아래) |

## 의존성 주입

⛔ **생성자 주입만 쓴다. 필드에 `@Autowired` 를 달지 않는다.**
final 로 잠기고, 테스트가 `new` 로 만들 수 있고, 안 채워진 채 뜨는 일이 없다.

```java
@Service
public class IntakeService {
    private final IntakeRepository intakes;
    private final IdSequence ids;

    public IntakeService(IntakeRepository intakes, IdSequence ids) {   // 애너테이션 없이도 주입된다
        this.intakes = intakes;
        this.ids = ids;
    }
}
```

⚠️ **예외 하나** — **생성자가 둘 이상이면** 스프링이 어느 것도 못 고르고 `No default constructor found`
로 죽는다. 그때만 쓸 생성자에 `@Autowired` 를 단다(`SecretSealer` 가 그 자리다. 왜인지 그 파일에 적혀 있다).

## 층 — 누가 무엇을 하나

| 층 | 하는 일 | ⛔ 안 하는 일 |
|---|---|---|
| `@Controller` | 주소를 받고 · 값을 읽고 · 모델에 담고 · 화면 이름을 돌려준다 | **업무 규칙을 두지 않는다.** 트랜잭션을 열지 않는다 |
| `@Service` | 업무 규칙 · 트랜잭션 경계 | HTTP 를 모른다 — `HttpServletRequest` 를 받지 않는다 |
| `Mapper` | 데이터 접근 하나. MyBatis 매퍼 인터페이스 + XML | 구현 클래스를 손으로 만들지 않는다 |
| 값 묶음 | **값을 담는다.** 만드는 문은 정적 팩터리 하나(`Project.create`) | ⛔ **자기 상태를 바꾸는 법을 두지 않는다** — `markReady()` 꼴은 MyBatis 로 옮기며 전부 걷어냈다(아래 절). setter 도 열지 않는다 |

## 데이터 접근 — MyBatis 다 (2026-08-15 에 옮겨 끝냈다)

**까닭은 기술 우열이 아니라 인수인계다.** 이 저장소엔 조인도 집계도 없어 두 쪽 다 잘 맞았다 —
그러면 기준은 하나뿐이다. **이 서버를 나중에 받을 사내 개발자가 매일 읽고 고칠 것이 SQL 이다.**

**조각 단위로 옮겼고 하루 만에 다섯이 다 끝났다.** 엮인 정도가 낮은 것부터였다:
`claude` ✅ → `ai` ✅ → `intake` ✅ → `project` ✅ → `account` ✅.
`account` 가 마지막이었던 것은 그것이 **테스트 전체의 공용 밑재료**여서다 — 통합 테스트 대부분이 로그인할 계정을 만든다.

⛔ **이제 `@Entity` 도 `JpaRepository` 도 하나도 없다.** 새 표를 더할 때 JPA 로 되돌리지 마라 —
한 저장소에 데이터 접근이 둘이면 다음 사람이 어느 쪽을 고쳐야 하는지부터 헷갈린다.

⛔ **의존성에서도 뺐다** — `spring-boot-starter-data-jpa` 자리에 `spring-boot-starter-jdbc` 가 섰다.
트랜잭션 관리자는 `DataSourceTransactionManager` 로 갈아탔고 테스트의 `@Transactional` 롤백도 그대로다.
그와 함께 **스프링 설정 파일의 `spring.jpa.*` 블록이 통째로 사라졌다** — `ddl-auto: validate` 는
볼 엔티티가 없어 이미 아무것도 안 재고 있었고, 그 그물은 `BuilderApplicationTest` 가 메운다.

⚠️ **채번 한 곳만 `JdbcTemplate` 을 직접 쓴다**(`IdSequence`). 시퀀스 이름은 SQL 에 자리표시자로
못 넣어 이어붙일 수밖에 없어 매퍼 XML 로 안 내렸다 — 대신 `enum Kind` 가 이어붙일 값을 가둔다.
그리고 **스키마 이름을 `spring.flyway.default-schema` 에서 읽는다**: 옛 `jpa.…default_schema` 가
사라졌고, **시퀀스를 실제로 만드는 것이 Flyway**(`V1`·`V3`)라 정본이 그쪽이 맞다.

| 규칙 | 왜 |
|---|---|
| **매퍼는 `{Domain}Mapper`, SQL 은 `resources/mapper/{도메인}/` 의 XML** | 사내 표준과 같은 모양이다 |
| **메서드는 SQL 종류를 접두사로** — `selectByAccountId` · `insert` · `updateToken` | ⛔ g2c 의 `...ListPage`·`...Action` 접미사는 안 쓴다. 그건 그쪽 화면 흐름을 전제한 이름인데 우리 컨트롤러 모양이 다르다 |
| ⛔ **표 이름에 `builder.` 를 손으로 붙인다** | 우리 표는 `public` 이 아니라 `builder` 스키마에 산다. **MyBatis 는 `jpa.default_schema` 를 안 물려받는다** — 빠뜨리면 「표가 없다」로 죽는다 |
| ⛔ **값 묶음에 상태 변경 메서드를 두지 않는다** | JPA 는 찾아온 것을 고치면 저장됐지만(더티 체킹) **MyBatis 엔 그것이 없다.** 두면 부르는 쪽은 저장된 줄 알고 DB 는 안 바뀌는데 **예외도 안 난다.** 고치는 길은 매퍼의 `update` 하나 |
| ⛔ **뜻이 다른 두 변경을 한 `update` 로 뭉개지 마라** | `Account` 의 `changePassword`(최초 설정을 **빠져나간다**)와 `resetToTemporary`(**한 번 더 밟게 한다**)가 그 자리다. 해시를 간다는 것만 같고 뜻이 정반대라 `AccountMapper.updatePassword` · `updateToTemporaryPassword` 로 갈랐다. 깃발 인자 하나로 합치면 **참·거짓이 뒤집혀도 컴파일도 예외도 안 나고**, 사람이 최초 설정 화면에 갇히는 것으로만 드러난다 |
| ⚠ **`update` 뒤에 그 값을 쓸 것이면 다시 읽는다** | 값 묶음이 불변이라 매퍼로 고쳐도 **손에 든 객체는 옛 값 그대로**다. 그것으로 `BuilderUser` 를 만들면 「비밀번호를 바꿨다」가 신원에 안 실려 `/password` 로 무한히 되튕긴다(`PasswordController`). ⚠ **쓸 데가 없으면 되읽지 않는다** — `AdminAccountController.reissue` 는 바로 리다이렉트라 왕복만 는다 |
| **「먼저 `update`, 0 이면 `insert`」** | 찾아보고 갈라지는 것보다 왕복이 하나 적고, 사이에 낀 다른 요청과 안 부딪힌다 (`ClaudeCredentialService.store`) |
| **경합을 가르는 UPDATE 는 `returning` 을 달고 `<select>` 로 쓴다** | 「누가 이겼나」를 DB 가 심판하는 자리다(`AiRunMapper.updateToFinished`). ⚠ 돌려받는 것이 있는 문은 `executeUpdate` 로 못 쏘니 태그가 `<select>` 여야 한다 — **메서드 이름은 SQL 종류대로 `update`** 를 유지한다. ⛔ `flushCache="true" useCache="false"` 를 지우지 마라. 쓰는 문인데 select 캐시에 걸리면 **한 트랜잭션의 둘째 호출이 SQL 을 안 쏘고 첫째의 답을 되줘 「진 쪽」이 이겼다고 믿는다** |
| **null 이 올 수 있는 인자에는 `jdbcType` 을 박는다** — `#{developerLog,jdbcType=VARCHAR}` | 값이 `null` 이면 MyBatis 는 자바 타입으로 종류를 못 정해 기본값(`OTHER`)으로 `setNull` 을 부르고, **PostgreSQL 이 그것을 거절한다** |
| ⛔ **값 묶음에서 걷어낸 판정은 서비스로 옮긴다. SQL 의 `where` 로 내리지 마라** | 옛 엔티티의 상태 변경 메서드에는 규칙이 같이 살았다(「`UNDECIDED` 로 되돌리지 않는다」·「못 읽은 문서는 확인할 것이 없다」). 메서드만 지우면 규칙도 같이 사라진다. **`where` 로 내리면 거절이 「바뀐 줄 0」으로만 보여** 사람에게 무슨 말을 해야 할지가 없어진다 — 판정은 서비스가 하고 한글 예외로 던진다 (`IntakeService.chooseProcessType`·`confirmNormalized`) |
| **`<constructor>` 의 `<idArg>` 는 앞에 몰려 있어야 한다** | DTD 가 `(idArg*, arg*)` 다. 복합키 열이 생성자에서 떨어져 있으면(`IntakeFacet` 은 `(intake_id, name)` 사이에 `project_id` 가 낀다) **자리가 어긋나 엉뚱한 열이 담긴다.** 평평한 목록이라 `idArg` 로 얻을 것도 없으니 그때는 전부 `<arg>` 로 적는다 |
| ⛔ **`in` 절을 쓰는 매퍼를 빈 목록으로 부르지 마라** | `in ()` 이 되어 SQL 이 깨진다. 부르는 쪽이 먼저 비었나를 본다(`IntakeController.list`). JPA 의 `...In(...)` 은 빈 목록을 알아서 처리해 줬다 — **그 편의가 없어졌다** |
| ⛔ **DB 기본값이 채우는 열을 되읽으려고 `<selectKey>` 를 쓰지 마라. `insert` 뒤에 `selectById` 로 한 번 더 읽는다** | 우리 값 묶음은 전부 불변이라 **꽂을 자리가 없다**. `ProjectService.register` 가 그 자리다 — 예전에는 `flush()` + `em.refresh()` 였는데, 그것은 JPA 가 쓰기를 미뤄서 행이 아직 없던 사정 때문이었고 MyBatis 엔 그 사정이 없다. **왕복 하나가 느는 것이 값이고**, 대신 `created_at` 이 자바에서 `null` 일 수 있다는 갈래가 통째로 사라진다 |
| ⚠ **읽개 이름은 그 값이 화면에 뜨나로 갈린다** | 값 묶음은 `id()` 꼴이 기본인데 **`Project` 만 `getId()` 꼴**이다. 타임리프의 `${p.name}` 이 자바빈 규약을 타기 때문이다 — 바꾸면 관리 화면 넷이 통째로 「그런 속성이 없다」로 깨진다. ⛔ 「통일한다」며 손대지 마라 |

### ✅ 공존 기간이 끝났다 — `saveAndFlush` 는 하나도 안 남았다 (2026-08-15)

**옮기는 동안만 있던 규칙이라 여기 이력으로 남긴다.** JPA 는 쓰기를 미뤘다(write-behind) —
`save()` 는 영속성 컨텍스트에 담아만 두고 커밋 직전에 INSERT 를 날렸다. 그런데 **MyBatis 는
JDBC 로 곧장 쏜다.** 그래서 한 트랜잭션 안에서 JPA 로 만든 줄을 MyBatis 가 보면 **아직 없었고**,
FK 위배로 죽었다. 그래서 픽스처가 `saveAndFlush` 였다.

⚠️ **실물에서는 잘 안 났다** — 계정은 앞선 요청에서 이미 커밋돼 있다.
**테스트에서만 터졌다**(`@Transactional` 이라 둘이 한 트랜잭션에 든다). 같은 날 네 번 잡혔다 —
`claude` 조각(`ClaudeConnectTest`·`AiRunServiceTest`) · `ai` 조각(`AiRunServiceTest.readyProject()` 가
`adk_builder_ai_run.project_id` FK) · `intake` 조각(`IntakeUploadTest.readyProject()` 가
`adk_builder_intake.project_id` FK) · 그리고 계정을 FK 로 쓰던 자리들.

⛔ **`saveAndFlush` 를 되살리지 마라 — 매퍼에는 `flush` 라는 것 자체가 없다.** 마지막 둘
(`AiRunServiceTest.someone()` · `ClaudeConnectTest.planner()`)은 `account` 조각에서 걷어냈다.
지운 자리마다 **왜 이제 안 필요한지**를 주석으로 남겼다.

⚠ **운영 코드에 있던 한 자리도 같이 사라졌다** — `ProjectService.register` 의
`repository.flush()` + `em.refresh()` 다. 까닭이 둘(`created_at` 되읽기 · 뒤따르는 적용 구분
MyBatis INSERT 의 FK)이었는데 프로젝트가 MyBatis 가 되며 **둘 다 없어졌다.** `EntityManager`
주입도 걷어냈다.

⚠ **`EntityManager` 가 아직 한 군데 산다** — `IdSequence` 가 `createNativeQuery("select nextval(...)")`
로 채번한다. 그것만이 `spring-boot-starter-data-jpa` 를 붙들고 있는 마지막 줄이다(→ `pom.xml` 주석).

## 트랜잭션

- **경계는 서비스다.** `@Transactional` 은 서비스 메서드에 붙인다
- **읽기만 하면 `@Transactional(readOnly = true)`**
- ⛔ **오래 도는 일(`@Async`)에 걸지 마라.** `CloneWorker.clone` 에 그 이유가 길게 적혀 있다 —
  요약하면 둘이다: ① 안에서 던진 예외가 공유 트랜잭션을 **rollback-only** 로 만들어
  **실패를 저장하는 것조차 실패한다** ② 커넥션을 30분 문다.
  **DB 를 만지는 구간을 짧게 토막 내고** 결과 쓰기는 서비스의 제 트랜잭션에서 따로 커밋한다
- ⚠ **컨트롤러가 받기 전에 다 읽어 둔다.** MyBatis 엔 지연 로딩이 애초에 없다 —
  옛 `open-in-view: false` 가 지키던 것이 이제 구조로 지켜진다

## 값 묶음 — 표 한 줄을 담는 것

**애너테이션이 하나도 없는 맨 자바 클래스다.** 2026-08-15 까지는 `@Entity` 였다.

```java
public class Account {

    private final String id;            // ⛔ DB 의 default 에 기대지 마라 — IdSequence 가 채번한다
    private final boolean mustChangePassword;
    private final Instant createdAt;    // DB 의 default now() 가 채운다 — 새것에는 아직 없다

    /** MyBatis 가 조회 결과를 담을 때 쓴다 (XML 의 <constructor>). ⛔ 인자 순서를 바꾸지 마라. */
    private Account(String id, …, boolean mustChangePassword, Instant createdAt) { … }

    /** 만드는 문은 정적 팩터리 하나. ⚠ createdAt 은 담지 않는다. */
    public static Account create(String id, …) { … }

    public String getId() { return id; }
    public boolean isMustChangePassword() { return mustChangePassword; }
}
```

- **필드는 전부 `final`.** 만드는 문은 **정적 팩터리 하나**다
- ⛔ **setter 도 상태 변경 메서드도 두지 않는다.** 고치는 길은 매퍼의 `update` 하나다 —
  까닭은 위 「데이터 접근」 절에 있다(더티 체킹이 없어 **조용히 잃는다**)
- **PK 는 `String`** 이고 값은 `'0000001'` 꼴이다. ⛔ 숫자형을 쓰지 않는다(`data-model` §0)
- **private 생성자는 MyBatis 의 `<constructor>` 가 부른다.** ⛔ **인자 순서를 바꾸지 마라** —
  XML 의 `<arg>` 와 **자리로** 맞춘다. 같은 타입이 이웃하면 **뒤바뀌어도 컴파일도 예외도 안 나고**
  화면에 엉뚱한 값이 뜨는 것으로만 드러난다
- ⚠ **읽개 이름은 그 값이 화면에 뜨나로 갈린다** — `Intake` 는 `id()` 꼴, `Project`·`Account` 는
  `getId()` 꼴이다. 타임리프의 `${account.loginId}` 가 자바빈 규약을 타기 때문이다.
  ⛔ 「통일한다」며 손대지 마라 — 관리 화면이 통째로 「그런 속성이 없다」로 깨진다
- ⛔ **`record` 로 바꾸지 마라** — 열이 여덟이면 생성자 인자도 여덟인데, `record` 는 **읽개 이름이
  필드 이름에 묶여** 위의 `getId()` 꼴을 못 만든다. 화면에 안 뜨는 값 묶음은 `record` 로 써도 된다

## 스키마와 마이그레이션

- ⛔ **스키마는 Flyway 단독 관리.**
  ⚠ **2026-08-15 부터 `ddl-auto: validate` 는 아무것도 안 잰다** — 볼 `@Entity` 가 하나도 없어졌다.
  그 자리를 메우는 것이 `BuilderApplicationTest` 다: 표 이름을 **밖에서 박아 두고** 마이그레이션이
  진짜 그 표를 만들었나를 잰다. ⛔ **그 테스트를 지우지 마라** — 지금은 그것이 유일한 그물이다
- **파일 하나 = 회차 하나** — `V4__intake.sql`. ⛔ **이미 나간 회차를 고치지 마라**(체크섬이 깨진다).
  ⚠️ 아직 아무 DB 에도 안 나갔을 때만 그 자리에서 다시 쓴다 — 되는 조건은 `data-model` §1 에 있다
- **표마다 `comment on table` · 열마다 `comment on column` 을 한글로 자세히 적는다**(`data-model` §0 규칙 7)
- ⚠️ **테스트는 zonky PostgreSQL 14.22 로 돈다.** 운영 목표는 17 이라 **15+ 문법을 쓰면 테스트에서만 깨진다**
- ⚠️ `spring.flyway.schemas` 를 `application-local.yml` 로 옮기지 마라 — 테스트가 못 물려받아
  **「테스트만 초록」**이 된다

## 컨트롤러와 화면

- 주소는 **영문 kebab-case** — `/projects/{projectId}/artifacts/received-docs`
- **모델 키는 영문 camelCase** 이고 템플릿이 같은 이름으로 부른다
- 화면은 껍데기 조각을 쓴다 — `~{fragments/shell :: layout(title, shape, current, content)}`
- ⛔ **프로젝트 이름·번호·알림을 화면마다 담지 마라.** `ProjectContextInterceptor` 가 한 자리에서 얹는다.
  화면이 열 개라 손으로 채우면 빠뜨릴 자리가 쉰이 되고, **알림은 빠뜨려도 조용히 빈 채로 뜬다**
- ⛔ **템플릿 주석은 파서 주석 `<!--/* … */-->`** 을 쓴다. 보통 주석은 **브라우저까지 나간다** —
  내부 설계 메모가 사용자에게 보인다(2026-08-13 에 실제로 나갔고 테스트가 잡았다)
- **PRG** — POST 가 성공하면 redirect 한다. 실패하면 친 값을 그대로 두고 다시 그린다

## 예외

| 무엇 | 무엇을 던지나 |
|---|---|
| **사람이 고칠 수 있는 것** (빈 문서명 · 적용 구분 미선택) | `IllegalArgumentException` — 컨트롤러가 잡아 **화면에 한글로** 되돌려준다. ⛔ 500 이 아니다 |
| **없는 자원** (낡은 링크 · 남의 프로젝트) | `ResponseStatusException(NOT_FOUND)` |
| **있을 수 없는 상태** (계약 위반) | `IllegalStateException` — 이건 버그다 |

⛔ **예외를 삼키지 마라.** 잡으면 **무엇을 할지**가 있어야 한다 — 로그만 찍고 흘려보내면
같은 실패가 조용히 반복된다.

## 테스트

- **DB 를 쓰는 테스트는 `AbstractDbTest` 를 물려받는다** — zonky 임베디드 PostgreSQL 이라 Docker 가 필요 없다
- ⛔ **`@Transactional` 을 지우지 마라.** zonky 는 DB 를 **컨텍스트마다 한 번** 띄우고 테스트 사이에
  안 되돌린다 — 없으면 한 테스트가 바꾼 것이 다음으로 새어 **실행 순서에 따라 통과와 실패가 갈린다**
- ⛔ **기본키를 글자로 박지 마라**(`"0000001"`). 부팅 슈퍼계정이 이미 그 번호를 가져갔다 — `ids.다음(...)` 을 쓴다
- **테스트 메서드 이름은 한글**, 그 안의 변수와 도구 메서드는 영문이다(위 예외 1)
- **화면은 렌더해서 파일로 뽑는다** — `ShellTest` 가 `target/rendered/` 에 둔다.
  ⚠️ **선언만 보고 끝내지 말고 계산된 값을 재라** — 메뉴가 모든 화면에 열쇠 전부를 그리므로
  `contains("이름")` 은 **그 화면에 안 들어가도 늘 참**이다

## 돌리는 법

```bash
export JAVA_HOME="/c/Tools/jdks/openjdk17.0.12"
./mvnw -o test
```

⚠️ **클로드의 Bash 셸은 기계 수준 `JAVA_HOME` 을 못 물려받는다** — 「자바가 없다」로 보이는 것은
셸 탓이지 기계 탓이 아니다. ⚠️ 콘솔이 cp949 라 한글 로그가 깨져 보인다 — 결과는
`target/surefire-reports/` 를 읽는 편이 정확하다.

---

## 언제 이 문서를 고치나

- 새 자리가 생겨 「영문이냐 한글이냐」가 안 정해질 때 — **여기 한 줄을 더한다.** 코드에서 정하지 않는다
- 예외를 새로 만들 때 — **왜 예외인지를 같이 적는다.** 근거 없는 예외는 다음 사람이 규칙으로 읽는다
