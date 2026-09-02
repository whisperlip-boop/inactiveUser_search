# inactive-user-search

퇴사(비활성·삭제) 계정도 JQL로 찾을 수 있게 해주는 Jira Server/Data Center 플러그인.

## 문제

퇴사자 계정을 비활성화하면 그 사람이 남긴 이슈를 인수인계 받은 사람이 찾지 못한다. 기록을 남기는
이유가 나중에 찾기 위해서인데 정작 검색이 안 된다.

핵심은 **자동완성**이다. 사용자는 전임자의 영문 계정명(`gildong.hong`)까지 기억하지 못한다. 보통
한글 이름 일부를 치고 드롭다운에서 고르는데, Jira의 값 제안은 활성 사용자만 내놓는다. 관리자라면
사용자 관리 화면에서 계정명을 찾아낼 수 있지만 일반 사용자는 그 사람을 **지목할 방법 자체가 없다.**

Jira 8.13 실측 (`홍길동 / gildong.hong`, 계정명 `gildong.hong`, 비활성):

| 검색 방법 | 설치 전 | 설치 후 |
|---|---|---|
| 자동완성에 `gildong` 입력 | 제안 0건 | **제안됨** |
| 자동완성에 `홍길` 입력 (한글 이름) | 제안 0건 | **제안됨** |
| 자동완성에 정확한 계정명 `gildong.hong` 입력 | **제안 0건** | **제안됨** |
| `reporter = "gildong.hong"` | 1건 (되긴 함) | 1건 |
| `reporter = "홍길동"` (표시이름) | 0건 + 경고 | `inactiveUser()`로 1건 |

정확한 계정명을 다 쳐도 자동완성이 0건인 게 문제의 본질이다. 검색 자체는 되는데 아무도 그 값을
알아낼 수 없다.

## 해결

### 1. 자동완성에 퇴사자를 되살린다 (핵심)

JQL 편집기가 호출하는 값 제안 응답에 비활성 사용자를 덧붙인다. **새 문법을 배울 필요 없이** 원래
쓰던 방식 그대로, 이름 일부만 쳐도 퇴사자가 목록에 뜨고 고르면 된다.

```
reporter = 퇴사          →  홍길동 / gildong.hong - gildong.hong@example.com  (gildong.hong) [비활성]
reporter = 사자          →  단어 중간 일치도 된다 (Jira 기본 검색은 활성 사용자에게도 못 하는 것)
reporter = nact          →  영문 이름·이메일 중간 일치
```

활성 사용자와 구분되도록 `[비활성]`(디렉터리에서 삭제된 계정은 `[삭제된 계정]`) 꼬리표가 붙는다.

**사용자 피커는 건드리지 않는다.** 대상은 검색용 자동완성 엔드포인트 하나뿐이라, 이슈 편집 화면에서
퇴사자에게 새로 담당자를 지정하는 일은 여전히 불가능하다.

### 2. JQL 함수 (한 번에 여러 명, 퇴사자 전원 조회)

```jql
reporter in inactiveUser("gildong.hong")              -- 사용자명
reporter in inactiveUser("홍길동")                 -- 표시이름 일부
reporter in inactiveUser("gildong.hong@example.com")      -- 이메일
reporter in inactiveUser("JIRAUSER10100")         -- 사용자 키
reporter in inactiveUser("홍길동", "dong")         -- 여러 명 한 번에
reporter in inactiveUser()                        -- 퇴사자 전원
reporter in anyUser("dong")                       -- 계정 상태 무관

assignee in inactiveUser("홍길동")                 -- reporter 말고 다른 사용자 필드도 동일
```

`reporter`·`assignee`·`creator`는 물론 **사용자 타입 커스텀 필드**에도 쓸 수 있다(절 이름을 필드로
되짚어 `UserField` 구현인지 확인하므로 자동으로 걸린다). 필터 저장, 대시보드 가젯, `ORDER BY`도 정상.

일치하는 사람이 없으면 오류가 아니라 **경고**를 낸다 — 오류로 올리면 검색 자체가 막히기 때문이다.

### 어떻게 찾는가

검색어 하나에 대해 아래 순서로 훑는다.

1. **정확한 사용자명** — `UserManager.getUserByName` (비활성 포함)
2. **`app_user` 테이블 직접 조회** — 디렉터리(AD/LDAP)에서 삭제된 계정도 (소문자 사용자명 → 사용자
   키) 매핑이 여기 영구히 남는다. Jira가 한 번이라도 알았던 계정은 전부 복원 가능
3. **사용자 키**(`JIRAUSER10100`)로 지정한 경우
4. **표시이름/이메일/사용자명 부분일치** — `UserSearchService` + `includeInactive(true)`
5. 위에서 못 찾으면 **전수 스캔 폴백** — 4번은 단어 앞부분 일치라서 "사자"처럼 가운데를 넣으면 못
   찾는데, 이 폴백이 받아준다 (사용자 20,000명 이하 인스턴스)

## 확인용 REST API

함수와 자동완성이 실제로 누구를 집어내는지 볼 수 있다.

```bash
curl -u <id>:<pw> 'http://<jira>/rest/inactive-user-search/1.0/lookup?q=홍길동&mode=inactive'
```

```json
[{"username":"gildong.hong","key":"JIRAUSER10100","displayName":"홍길동 / gildong.hong",
  "emailAddress":"gildong.hong@example.com","active":false,"existsInDirectory":true,
  "jqlValue":"\"gildong.hong\""}]
```

`mode`는 `inactive`(기본) 또는 `any`. 로그인한 사용자만 호출할 수 있다.

## 제한

- 자동완성 보강은 코어와 같이 **1글자부터** 동작하고, **최대 10명**까지 덧붙인다. 결과는 60초
  캐시한다(자동완성은 키 입력마다 호출된다).
- JQL 함수 한 번이 만들어내는 사용자 수는 **900명**까지다. Lucene `BooleanQuery`의 기본
  `maxClauseCount`가 1024라 그보다 많으면 검색이 터진다. 초과 시 경고 후 자른다.
- 사용자 20,000명 초과 인스턴스에서는 부분일치 폴백과 `inactiveUser()`(인수 없는 호출)를 지원하지 않는다.
- 검색 결과의 이슈는 **검색하는 사람의 이슈 권한**으로 그대로 걸러진다. 이 플러그인이 늘리는 것은
  "사용자를 지목하는 능력"이지 "이슈를 볼 권한"이 아니다.

## 빌드 / 설치

```bash
./build.sh                      # 빌드 + jar를 Windows Downloads로 복사
JIRA_PASS='...' ./deploy.sh     # WSL2 Docker Jira에 UPM REST 업로드
```

컴파일 대상은 Jira **8.13.0**(WSL2 테스트 인스턴스)이다. 여기서 쓰는 JQL/User/Servlet API는
8.13~8.17 사이 시그니처 변화가 없어서, 낮은 쪽으로 컴파일한 jar가 운영 **8.17.1**에도 그대로
설치·동작한다.
