package co.bskim.jira.inactiveuser.service;

import com.atlassian.jira.bc.user.search.UserSearchParams;
import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.UserKeyService;
import com.atlassian.jira.user.util.UserManager;
import org.ofbiz.core.entity.GenericValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 활성 여부와 무관하게 사용자를 찾아주는 조회기.
 *
 * <p>Jira가 퇴사자를 못 찾는 지점은 두 곳이다.
 * <ol>
 *   <li>표시이름·이메일로 찾는 경로({@link UserSearchService})가 기본값으로 활성 사용자만 본다 →
 *       {@code reporter = "홍길동"} 류가 실패한다.</li>
 *   <li>사용자 디렉터리(LDAP 등)에서 계정이 통째로 삭제되면 {@link UserManager#getUserByName}이
 *       null을 돌려준다 → 이슈에는 reporter가 남아 있는데 사용자 객체가 없다.</li>
 * </ol>
 * 1번은 {@code includeInactive(true)}로, 2번은 app_user 테이블(사용자 키 저장소)을 직접 보는 것으로
 * 각각 뚫는다. app_user에는 Jira가 한 번이라도 알았던 계정의 (소문자 사용자명 → 사용자 키) 매핑이
 * 영구히 남기 때문에, 디렉터리에서 사라진 계정도 여기서는 복원된다.
 */
public class UserLookupService {

    private static final Logger log = LoggerFactory.getLogger(UserLookupService.class);

    /** 검색 대상 범위. */
    public enum Mode {
        /** 비활성 + 디렉터리에서 삭제된 계정만. */
        INACTIVE_ONLY,
        /** 활성/비활성/삭제 전부. 인수인계자가 계정 상태를 모를 때 쓴다. */
        ANY
    }

    /**
     * 한 번의 함수 호출이 만들어낼 수 있는 최대 사용자 수.
     * Lucene BooleanQuery의 기본 maxClauseCount가 1024라, 그보다 많은 리터럴을 내보내면
     * 검색 자체가 TooManyClauses로 터진다. 여유를 두고 자른다.
     */
    public static final int MAX_RESULTS = 900;

    /**
     * 전수 스캔(모든 사용자 순회)을 허용하는 사용자 수 상한.
     * 이 규모를 넘는 인스턴스에서는 부분일치 폴백을 포기하고 정확 일치까지만 지원한다.
     */
    private static final int SCAN_LIMIT = 20000;

    private UserManager userManager() {
        return ComponentAccessor.getUserManager();
    }

    private UserKeyService userKeyService() {
        return ComponentAccessor.getComponent(UserKeyService.class);
    }

    private UserSearchService userSearchService() {
        return ComponentAccessor.getComponent(UserSearchService.class);
    }

    private OfBizDelegator ofBizDelegator() {
        return ComponentAccessor.getOfBizDelegator();
    }

    /**
     * 검색어들을 사용자 목록으로 바꾼다. 검색어가 비어 있으면 해당 모드의 전체 사용자를 돌려준다
     * ({@code reporter in inactiveUser()} → 퇴사자 전원).
     */
    public List<UserMatch> lookup(Collection<String> terms, Mode mode) {
        // username 기준 중복 제거 + 입력 순서 유지
        final Map<String, UserMatch> found = new LinkedHashMap<String, UserMatch>();

        if (terms == null || terms.isEmpty()) {
            collectAll(mode, found);
        } else {
            for (String term : terms) {
                if (found.size() >= MAX_RESULTS) {
                    break;
                }
                matchTerm(term, mode, found);
            }
        }

        List<UserMatch> result = new ArrayList<UserMatch>(found.values());
        if (result.size() > MAX_RESULTS) {
            result = result.subList(0, MAX_RESULTS);
        }
        return result;
    }

    /** {@link #lookup}의 결과에서 JQL 리터럴로 쓸 사용자명만 뽑는다. */
    public List<String> usernames(Collection<String> terms, Mode mode) {
        List<UserMatch> matches = lookup(terms, mode);
        List<String> names = new ArrayList<String>(matches.size());
        for (UserMatch match : matches) {
            names.add(match.username);
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 검색어 1건 처리
    // ------------------------------------------------------------------

    private void matchTerm(String rawTerm, Mode mode, Map<String, UserMatch> out) {
        if (rawTerm == null) {
            return;
        }
        final String term = rawTerm.trim();
        if (term.isEmpty()) {
            return;
        }
        // 사용자가 습관적으로 붙이는 와일드카드는 어차피 부분일치라 떼어낸다.
        final String needle = term.replace("*", "").toLowerCase(Locale.ENGLISH);
        if (needle.isEmpty()) {
            return;
        }
        // 폴백 여부는 "이 검색어가" 뭔가 찾았는지로 판단해야 한다. out은 여러 검색어가 함께 쓰는
        // 누적 맵이라, 앞 검색어의 결과 때문에 뒤 검색어의 폴백이 통째로 안 도는 버그가 나기 쉽다.
        final int sizeBefore = out.size();

        // 1) 정확한 사용자명. 디렉터리에 남아 있으면 여기서 끝난다.
        ApplicationUser byName = userManager().getUserByName(term);
        if (byName != null) {
            add(out, fromUser(byName), mode);
        }

        // 2) 디렉터리에서 지워졌어도 app_user에 매핑이 남아 있으면 복원한다.
        String keyForName = userKeyService().getKeyForUsername(needle);
        if (keyForName != null) {
            add(out, fromKeyStore(needle, keyForName), mode);
        }

        // 3) 사용자 키(JIRAUSER10100 …)를 그대로 넣은 경우.
        String nameForKey = userKeyService().getUsernameForKey(term);
        if (nameForKey != null) {
            ApplicationUser byKey = userManager().getUserByName(nameForKey);
            add(out, byKey != null ? fromUser(byKey) : fromKeyStore(nameForKey, term), mode);
        }

        // 4) 표시이름/이메일/사용자명 부분일치. 여기서 includeInactive(true)가 핵심이다.
        searchDirectory(term, mode, out);

        // 5) 그래도 없으면 전수 스캔. UserSearchService는 단어 앞부분 일치라서
        //    "길동"처럼 이름 가운데를 넣으면 못 찾는데, 이 폴백이 그걸 받아준다.
        if (out.size() == sizeBefore) {
            scanAllUsers(needle, mode, out);
            scanUserKeyStore(needle, mode, out);
        }
    }

    private void searchDirectory(String term, Mode mode, Map<String, UserMatch> out) {
        UserSearchParams params = UserSearchParams.builder()
                .allowEmptyQuery(false)
                .includeActive(mode == Mode.ANY)
                .includeInactive(true)
                .canMatchEmail(true)
                // 검색하는 사람이 '사용자 찾아보기' 전역 권한이 없어도 인수인계 조회는 돼야 한다.
                // 여기서 나가는 건 사용자명뿐이고, 실제 이슈 노출은 이슈 권한이 따로 거른다.
                .ignorePermissionCheck(true)
                .maxResults(MAX_RESULTS)
                .sorted(true)
                .build();
        try {
            for (ApplicationUser user : userSearchService().findUsers(term, params)) {
                add(out, fromUser(user), mode);
            }
        } catch (RuntimeException e) {
            log.warn("[inactive-user-search] 사용자 디렉터리 검색 실패 (term={}): {}", term, e.toString());
        }
    }

    /** 모든 사용자를 돌면서 사용자명/표시이름/이메일에 needle이 포함되는지 본다. */
    private void scanAllUsers(String needle, Mode mode, Map<String, UserMatch> out) {
        if (userManager().getTotalUserCount() > SCAN_LIMIT) {
            log.debug("[inactive-user-search] 사용자 수가 {}명을 넘어 전수 스캔을 건너뛴다", SCAN_LIMIT);
            return;
        }
        for (ApplicationUser user : userManager().getAllApplicationUsers()) {
            if (out.size() >= MAX_RESULTS) {
                return;
            }
            if (contains(user.getName(), needle)
                    || contains(user.getDisplayName(), needle)
                    || contains(user.getEmailAddress(), needle)) {
                add(out, fromUser(user), mode);
            }
        }
    }

    /**
     * app_user 테이블만 부분일치로 훑는다. 디렉터리에서 완전히 삭제돼 표시이름조차 남지 않은
     * 계정은 여기 소문자 사용자명밖에 단서가 없다.
     */
    private void scanUserKeyStore(String needle, Mode mode, Map<String, UserMatch> out) {
        try {
            Map<String, Object> criteria = Collections.<String, Object>singletonMap(
                    "lowerUserName", "%" + needle + "%");
            for (GenericValue row : ofBizDelegator().findByLike("ApplicationUser", criteria)) {
                if (out.size() >= MAX_RESULTS) {
                    return;
                }
                String username = row.getString("lowerUserName");
                String key = row.getString("userKey");
                if (username == null || key == null) {
                    continue;
                }
                ApplicationUser user = userManager().getUserByName(username);
                add(out, user != null ? fromUser(user) : fromKeyStore(username, key), mode);
            }
        } catch (RuntimeException e) {
            log.warn("[inactive-user-search] app_user 조회 실패 (needle={}): {}", needle, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 인수 없이 호출했을 때: 해당 모드의 전체 사용자
    // ------------------------------------------------------------------

    private void collectAll(Mode mode, Map<String, UserMatch> out) {
        if (userManager().getTotalUserCount() > SCAN_LIMIT) {
            log.warn("[inactive-user-search] 사용자 수가 {}명을 넘어 인수 없는 호출을 지원하지 않는다", SCAN_LIMIT);
            return;
        }
        for (ApplicationUser user : userManager().getAllApplicationUsers()) {
            if (out.size() >= MAX_RESULTS) {
                return;
            }
            add(out, fromUser(user), mode);
        }
        if (mode == Mode.INACTIVE_ONLY) {
            // 디렉터리에서 사라진 계정은 위 순회에 안 잡히므로 app_user에서 따로 긁어온다.
            try {
                for (GenericValue row : ofBizDelegator().findAll("ApplicationUser")) {
                    if (out.size() >= MAX_RESULTS) {
                        return;
                    }
                    String username = row.getString("lowerUserName");
                    String key = row.getString("userKey");
                    if (username == null || key == null || out.containsKey(username)) {
                        continue;
                    }
                    if (userManager().getUserByName(username) == null) {
                        add(out, fromKeyStore(username, key), mode);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[inactive-user-search] app_user 전체 조회 실패: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private void add(Map<String, UserMatch> out, UserMatch match, Mode mode) {
        if (match == null || match.username == null) {
            return;
        }
        if (mode == Mode.INACTIVE_ONLY && !match.isInactive()) {
            return;
        }
        if (out.size() >= MAX_RESULTS) {
            return;
        }
        // 이미 담긴 항목이 디렉터리 정보를 가진 쪽이면 그대로 두고, 아니면 더 풍부한 쪽으로 교체한다.
        UserMatch existing = out.get(match.username);
        if (existing == null || (!existing.existsInDirectory && match.existsInDirectory)) {
            out.put(match.username, match);
        }
    }

    private UserMatch fromUser(ApplicationUser user) {
        return new UserMatch(
                user.getName(),
                user.getKey(),
                user.getDisplayName(),
                user.getEmailAddress(),
                user.isActive(),
                true);
    }

    /** 디렉터리에는 없고 app_user에만 남은 계정. */
    private UserMatch fromKeyStore(String username, String key) {
        return new UserMatch(username, key, username, null, false, false);
    }

    private boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ENGLISH).contains(needle);
    }
}
