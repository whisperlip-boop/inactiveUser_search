# inactive-user-search — 퇴사자 계정 JQL 검색 플러그인

## 목표

퇴사로 비활성화(또는 디렉터리에서 삭제)된 계정을 JQL에서 지목할 수 있게 한다. 인수인계 받은 사람이
전임자가 남긴 이슈를 찾지 못하는 문제를 푼다. 기능 설명과 사용법은 `README.md` 참고.

## 환경

| 항목 | 값 |
|---|---|
| 개발 OS | Windows + WSL2 (Ubuntu) |
| Atlassian SDK | 8.2.7 (AMPS 8.1.2) — `/opt/atlassian-plugin-sdk` |
| JDK (빌드) | OpenJDK 8 — `/usr/lib/jvm/java-8-openjdk-amd64` |
| 컴파일 대상 | **Jira 8.13.0** (`pom.xml`의 `jira.version`) |
| 운영 대상 | **Jira 8.17.1** — 8.13으로 컴파일한 jar를 그대로 설치 |
| groupId / artifactId | `co.bskim.jira` / `inactive-user-search` |
| 플러그인 키 | `co.bskim.jira.inactive-user-search` |

낮은 버전(8.13)으로 컴파일하는 이유: 여기서 쓰는 JQL/User API는 8.13~8.17 사이 시그니처 변화가 없고,
낮은 쪽 바이너리는 높은 쪽에서 동작하지만 반대는 보장되지 않는다.

### 테스트 인스턴스

- WSL2 Docker Jira **8.13.0** — http://localhost:18080 (컨테이너 `jira`, 네트워크 `atlassian`)
- `atlas-run`은 쓰지 않는다. 항상 `./build.sh` 후 `./deploy.sh`로 UPM REST 업로드.
- 관리자 계정: `bskim` — **비밀번호는 세션마다 사용자에게 물어볼 것**(저장 금지)
- 개발이 끝나면 `docker stop jira confluence`로 내려서 WSL 메모리를 아낀다.

## 설계 근거 (바이트코드로 직접 확인한 사실)

`javap`로 jira-api/jira-core 8.17.1을 뜯어서 확인한 내용. 추측이 아니라 확인된 동작이다.

1. **`UserResolverImpl.getIdsFromName()`은 `UserKeyService.getKeyForUsername()`을 먼저 본다.**
   이건 `app_user` 테이블 조회라 비활성 계정도, 디렉터리에서 삭제된 계정도 사용자 키를 돌려준다.
   → 사용자명으로 하는 검색(`reporter = hong.gildong`)은 원래 되는 게 정상이다.
2. **사용자명으로 못 찾으면 `getUsersFromFullNameOrEmail()`로 넘어가고, 이건 `UserSearchService`를
   쓴다.** 이 서비스의 기본 파라미터가 활성 사용자만 조회한다.
   → 표시이름·이메일로 하는 검색이 퇴사자에게 실패하는 진짜 원인이 여기다.
3. **`AbstractUserValidator`의 값 검증은 WARNING 레벨이다.** 값이 없어도 검색은 수행된다(오류로
   막지 않는다). 그래서 우리 함수도 일치 대상이 없을 때 오류가 아니라 경고를 낸다 — 오류로 올리면
   검색 자체가 막힌다.
4. **`CurrentUserFunction`/`MembersOfFunction`은 `QueryLiteral`에 `ApplicationUser.getName()`
   (사용자명)을 담는다.** 사용자 키가 아니다.
   → 우리 함수도 사용자명을 내보내야 한다. 키를 내보내면 위 1번 해석 단계에서 매칭에 실패한다.
   **이 규칙을 깨지 말 것.**

5. **진짜 병목은 JQL 검색이 아니라 자동완성이었다.** 실측(8.13, 계정 `jung.kim`/비활성):
   `reporter = "jung.kim"`은 **되는데**, 자동완성에 `jung.kim`을 다 쳐도 제안이 **0건**이다.
   사용자는 전임자의 영문 계정명을 모르고 자동완성으로 찾는데 거기 안 뜨니 지목할 방법이 없었던 것.
   그래서 JQL 함수보다 **자동완성 응답 보강**이 이 플러그인의 핵심 기능이다.
6. **프런트 계약**: `JQLAutoComplete.js:126`이
   `GET /rest/api/2/jql/autocompletedata/suggestions?fieldName=&fieldValue=`를 호출하고
   `{"results":[{"value":..,"displayName":..}]}`를 받는다. `displayName`은 `<li>`에 **HTML 그대로**
   삽입되므로(같은 파일 157행) 모든 조각을 이스케이프해야 한다. 코어 포맷은
   `풀네임 - 이메일  (사용자명)`, 일치 부분 `<b>` 강조.

## 구조

```
web/InactiveUserSuggestionFilter  자동완성 응답에 퇴사자 추가 — 핵심 기능
web/CapturingResponseWrapper      하위 응답 본문을 버퍼에 받아두는 래퍼
jql/AbstractUserLookupFunction    JqlFunction 공통 뼈대 (USER 타입, list=true, 최소 인수 0)
jql/InactiveUserFunction          inactiveUser(...)  — 비활성·삭제 계정만
jql/AnyUserFunction               anyUser(...)       — 상태 무관
service/UserLookupService         실제 조회 로직 (5단계 폴백)
service/UserMatch                 조회 결과 1건
rest/LookupResource               GET /rest/inactive-user-search/1.0/lookup?q=&mode=
```

호스트 컴포넌트는 Spring 주입이 아니라 `ComponentAccessor`로 가져온다. JQL 함수 클래스는 모듈
디스크립터가 직접 인스턴스화해서 스프링 스캐너 주입이 얽히기 쉬운데, `ComponentAccessor`는 그
문제가 아예 없다.

## 함정 (직접 밟고 고친 것들)

- **`<jql-function>`의 `fname`/`list`는 속성이 아니라 자식 엘리먼트다.**
  ```xml
  <jql-function key="..." class="..."><fname>inactiveUser</fname><list>true</list></jql-function>
  ```
  속성으로 쓰면 UPM에서 모듈이 `enabled=true`로 멀쩡히 뜨는데 JQL에서는
  "Unable to find JQL function"이 난다 — `JqlFunctionModuleDescriptorImpl.init()`이
  `element.element("fname")`으로 **자식 엘리먼트**를 읽기 때문. 로그에도 아무 경고가 안 남는다.
  모듈 등록 여부는 `/rest/api/2/jql/autocompletedata`의 `visibleFunctionNames`로 확인할 것.
- **UPM `plugin-icon`에 SVG를 주면 200 OK에 0바이트를 서빙한다.** 반드시 래스터 이미지(PNG)여야 한다.
- **응답 래핑에는 servlet-api 3.1이 필요하다.** 2.5로 컴파일한 `ServletOutputStream` 구현체는
  `isReady()`/`setWriteListener()`가 없어서 Tomcat 9 런타임에서 `AbstractMethodError` 위험이 있다.

## 주의사항

- **리터럴 상한 900명.** Lucene `BooleanQuery.maxClauseCount` 기본값이 1024라, 그보다 많은 값을
  내보내면 검색이 `TooManyClauses`로 터진다. `UserLookupService.MAX_RESULTS`를 올릴 거면 Jira의
  `jira.search.maxclauses`도 같이 올려야 한다.
- **전수 스캔은 폴백에서만.** 매 JQL 실행마다 전체 사용자를 도는 건 비싸다. 앞 단계에서 아무것도
  못 찾았을 때만 돈다. 이 판단은 검색어 **하나 단위**로 해야 한다(누적 맵 크기로 판단하면 앞
  검색어 결과 때문에 뒤 검색어 폴백이 통째로 안 도는 버그가 난다 — 실제로 한 번 냈다가 고쳤음).
- **i18n 한글은 `\uXXXX` 이스케이프.** Jira(Java 8)가 .properties를 ISO-8859-1로 읽는다.
  `.properties`를 직접 고치지 말고 `tools/make-i18n.py`를 고쳐서 재생성할 것.
- `ignorePermissionCheck(true)`로 사용자 검색을 돌린다. '사용자 찾아보기' 권한이 없는 사람도
  인수인계 조회는 돼야 하기 때문. 함수가 내보내는 건 사용자명뿐이고, **이슈 노출은 이슈 권한이
  그대로 거른다** — 권한 상승이 아니다.
- **자동완성 필터는 검색용 엔드포인트에만 붙인다.** 사용자 피커(`/rest/api/2/user/picker` 등)까지
  손대면 퇴사자에게 이슈를 새로 할당할 수 있게 된다 — 그건 버그다. 회귀 테스트로 피커 응답이
  비활성 계정을 계속 제외하는지 확인할 것.
- 필터는 보강에 실패하면 **원본 응답을 그대로 흘려보낸다**. 자동완성은 부가 기능이고, 여기서
  예외가 나서 검색창이 죽으면 안 된다.

## 검증 방법

Docker Jira에 배포한 뒤 아래로 확인한다(전부 실측 통과).

```bash
# 자동완성: jung / 퇴사 / 사자(중간 일치) / nact / jung.kim → 전부 제안돼야 함
curl -s -u bskim:<pw> -G --data-urlencode "fieldName=reporter" --data-urlencode "fieldValue=퇴사" \
  http://localhost:18080/rest/api/2/jql/autocompletedata/suggestions

# 함수 등록 여부
curl -s -u bskim:<pw> http://localhost:18080/rest/api/2/jql/autocompletedata | grep -o inactiveUser

# 회귀: 사용자 피커는 비활성 계정을 계속 제외해야 함 (users: [] 이어야 정상)
curl -s -u bskim:<pw> 'http://localhost:18080/rest/api/2/user/picker?query=jung'
```

테스트 데이터: 프로젝트 `TEST`의 `TEST-1`이 비활성 계정 `jung.kim`을 reporter로 갖고 있다.
(Jira는 비활성 사용자를 reporter로 **새로 지정하는 것을 거부**하므로, 만들 때는 계정을 잠시
활성화 → 이슈 생성 → 다시 비활성화하는 순서를 써야 한다. 실제 퇴사 시나리오와 같은 상태가 된다.)

## 운영 인스턴스의 퇴사자 계정 형태

실제 계정 정보는 개인정보라 여기 적지 않는다. 형태만 옮기면 이렇다(값은 가상).

```
Username     hong.gildong
Full name    홍길동 / Gildong Hong (퇴사자) [X]
Email        hong.gildong@example.com
Directory    Active Directory server
Status       Inactive (application access 없음, 그룹 없음)
```

디렉터리에는 남아 있고 비활성 상태다. 표시이름 뒤에 `(퇴사자) [X]`를 붙이는 사내 관례가 있어서
`inactiveUser("퇴사자")`로 퇴사자 전체를 훑는 것도 실제로 쓸모가 있다.
