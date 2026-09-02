package co.bskim.jira.inactiveuser.web;

import co.bskim.jira.inactiveuser.service.UserLookupService;
import co.bskim.jira.inactiveuser.service.UserMatch;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.UserField;
import com.atlassian.jira.issue.search.managers.SearchHandlerManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JQL 편집기의 값 자동완성 응답에 퇴사(비활성) 사용자를 덧붙인다.
 *
 * <p>이 플러그인이 푸는 진짜 문제가 여기 있다. {@code reporter = jung.kim} 처럼 사용자명을 정확히
 * 치면 검색 자체는 되지만, 인수인계 받은 사람은 전임자의 영문 계정명을 모른다. 보통은 한글 이름
 * 일부를 치고 자동완성 목록에서 고르는데, Jira의 값 제안은 활성 사용자만 내놓는다
 * (실측: 비활성 계정은 정확한 사용자명 {@code jung.kim}을 다 쳐도 제안 0건).
 * 그래서 일반 사용자에게는 그 사람을 지목할 방법 자체가 없다.
 *
 * <p>프런트({@code JQLAutoComplete.js})는
 * {@code GET /rest/api/2/jql/autocompletedata/suggestions?fieldName=reporter&fieldValue=jung} 을 호출하고
 * {@code {"results":[{"value":..,"displayName":..}]}} 를 받는다. 이 필터는 그 응답을 받아 비활성
 * 사용자를 같은 형식으로 덧붙인다. 새 문법을 배울 필요 없이 원래 쓰던 방식 그대로 퇴사자가 목록에
 * 나타난다.
 *
 * <p><b>사용자 피커(이슈 편집·담당자 지정)는 건드리지 않는다.</b> 대상은 검색용 자동완성
 * 엔드포인트 하나뿐이다. 퇴사자에게 이슈를 새로 할당할 수 있게 되면 그건 버그다.
 */
public class InactiveUserSuggestionFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(InactiveUserSuggestionFilter.class);

    /** 이 경로의 응답만 손댄다. */
    private static final String TARGET_PATH = "/jql/autocompletedata/suggestions";

    /** 덧붙일 최대 인원. 드롭다운을 퇴사자로 도배하지 않기 위한 상한. */
    private static final int MAX_ADDITIONS = 10;

    /**
     * 보강을 시작하는 최소 입력 길이.
     *
     * <p>코어와 반드시 같아야 한다. 실측하면 Jira는 활성 사용자를 <b>1글자</b>부터 제안한다
     * ({@code b} → bskim). 여기를 2로 두면 퇴사자만 한 글자 더 쳐야 나타나서, 활성 사용자보다
     * 불리하게 동작한다 — 이 플러그인이 없애려는 바로 그 비대칭이다.
     */
    private static final int MIN_QUERY_LENGTH = 1;

    /** 조회 결과 캐시 수명(ms). 자동완성은 키 입력마다 호출되므로 짧게라도 캐시가 필요하다. */
    private static final long CACHE_TTL_MS = 60_000L;

    private static final int CACHE_MAX_ENTRIES = 500;

    private final UserLookupService lookupService = new UserLookupService();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<String, CacheEntry>();

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
        cache.clear();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String fieldValue = httpRequest.getParameter("fieldValue");
        if (!isTargetRequest(httpRequest)
                || fieldValue == null
                || fieldValue.trim().length() < MIN_QUERY_LENGTH
                || !isUserField(httpRequest.getParameter("fieldName"))) {
            chain.doFilter(request, response);
            return;
        }

        CapturingResponseWrapper wrapper = new CapturingResponseWrapper(httpResponse);
        chain.doFilter(request, wrapper);

        byte[] original = wrapper.captured();
        byte[] body = original;
        try {
            if (wrapper.getStatus() == HttpServletResponse.SC_OK) {
                String augmented = augment(new String(original, wrapper.charset()), fieldValue.trim());
                if (augmented != null) {
                    body = augmented.getBytes(wrapper.charset());
                }
            }
        } catch (RuntimeException e) {
            // 자동완성 보강은 부가 기능이다. 실패하면 원본 응답을 그대로 흘려보낸다.
            log.warn("[inactive-user-search] 자동완성 응답 보강 실패: {}", e.toString());
            body = original;
        }

        httpResponse.setContentLength(body.length);
        httpResponse.getOutputStream().write(body);
        httpResponse.getOutputStream().flush();
    }

    // ------------------------------------------------------------------
    // 대상 판별
    // ------------------------------------------------------------------

    private boolean isTargetRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.endsWith(TARGET_PATH);
    }

    /**
     * 사용자 값을 받는 절인지 본다. 절 이름(reporter, assignee, "QA 담당자" …)을 필드로 되짚어
     * {@link UserField} 구현인지 확인하므로, 시스템 필드뿐 아니라 사용자 커스텀 필드도 자동으로 걸린다.
     */
    private boolean isUserField(String clauseName) {
        if (clauseName == null || clauseName.trim().isEmpty()) {
            return false;
        }
        try {
            SearchHandlerManager searchHandlerManager =
                    ComponentAccessor.getComponent(SearchHandlerManager.class);
            FieldManager fieldManager = ComponentAccessor.getFieldManager();
            for (String fieldId : searchHandlerManager.getFieldIds(clauseName.trim())) {
                Field field = fieldManager.getField(fieldId);
                if (field instanceof UserField) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            log.debug("[inactive-user-search] 필드 판별 실패 (clause={}): {}", clauseName, e.toString());
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 응답 보강
    // ------------------------------------------------------------------

    /** 고친 JSON을 돌려준다. 덧붙일 게 없거나 형식이 예상과 다르면 null. */
    private String augment(String json, String term) {
        JsonElement parsed = new JsonParser().parse(json);
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement resultsElement = root.get("results");
        if (resultsElement == null || !resultsElement.isJsonArray()) {
            return null;
        }
        JsonArray results = resultsElement.getAsJsonArray();

        // 코어가 이미 내놓은 값은 건너뛴다.
        Set<String> existing = new HashSet<String>();
        for (JsonElement element : results) {
            if (element.isJsonObject()) {
                JsonElement value = element.getAsJsonObject().get("value");
                if (value != null && value.isJsonPrimitive()) {
                    existing.add(value.getAsString());
                }
            }
        }

        int added = 0;
        for (UserMatch match : lookupCached(term)) {
            if (added >= MAX_ADDITIONS) {
                break;
            }
            if (existing.contains(match.username)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("value", match.username);
            entry.addProperty("displayName", displayName(match, term));
            results.add(entry);
            added++;
        }
        return added == 0 ? null : root.toString();
    }

    /**
     * 코어와 같은 모양으로 만든다: {@code 풀네임 - 이메일  (사용자명)}, 일치 부분은 굵게.
     * 뒤에 비활성 표시를 붙여 활성 사용자와 구분되게 한다.
     * displayName은 프런트에서 HTML로 그대로 삽입되므로 모든 조각을 이스케이프해야 한다.
     */
    private String displayName(UserMatch match, String term) {
        StringBuilder sb = new StringBuilder();
        sb.append(highlight(match.displayName != null ? match.displayName : match.username, term));
        if (match.emailAddress != null && !match.emailAddress.isEmpty()) {
            sb.append(" - ").append(highlight(match.emailAddress, term));
        }
        sb.append("  (").append(highlight(match.username, term)).append(")");
        sb.append(" ").append(marker(match));
        return sb.toString();
    }

    private String marker(UserMatch match) {
        String text = match.existsInDirectory
                ? i18n("inactiveuser.suggestion.marker.inactive", "비활성")
                : i18n("inactiveuser.suggestion.marker.deleted", "삭제된 계정");
        return "[" + escapeHtml(text) + "]";
    }

    private String i18n(String key, String fallback) {
        try {
            String text = ComponentAccessor.getJiraAuthenticationContext().getI18nHelper().getText(key);
            // 번들에 키가 없으면 Jira는 키 문자열을 그대로 돌려준다.
            return key.equals(text) ? fallback : text;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** term과 일치하는 부분만 굵게. 입력·본문 모두 이스케이프한 뒤 태그를 넣는다. */
    private String highlight(String text, String term) {
        if (text == null) {
            return "";
        }
        int index = text.toLowerCase(Locale.ENGLISH).indexOf(term.toLowerCase(Locale.ENGLISH));
        if (index < 0) {
            return escapeHtml(text);
        }
        return escapeHtml(text.substring(0, index))
                + "<b>" + escapeHtml(text.substring(index, index + term.length())) + "</b>"
                + escapeHtml(text.substring(index + term.length()));
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ------------------------------------------------------------------
    // 캐시 — 자동완성은 키 입력마다 호출되므로 같은 검색어를 반복 조회하지 않는다.
    // ------------------------------------------------------------------

    private List<UserMatch> lookupCached(String term) {
        String key = term.toLowerCase(Locale.ENGLISH);
        long now = System.currentTimeMillis();

        CacheEntry entry = cache.get(key);
        if (entry != null && now - entry.storedAt < CACHE_TTL_MS) {
            return entry.matches;
        }
        List<UserMatch> matches = lookupService.lookup(
                Collections.singletonList(term), UserLookupService.Mode.INACTIVE_ONLY);

        if (cache.size() >= CACHE_MAX_ENTRIES) {
            // 정교한 축출 정책을 둘 만한 규모가 아니다. 통째로 비운다.
            cache.clear();
        }
        cache.put(key, new CacheEntry(matches, now));
        return matches;
    }

    private static final class CacheEntry {
        final List<UserMatch> matches;
        final long storedAt;

        CacheEntry(List<UserMatch> matches, long storedAt) {
            this.matches = matches;
            this.storedAt = storedAt;
        }
    }
}
