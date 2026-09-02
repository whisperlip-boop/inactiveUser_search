package co.bskim.jira.inactiveuser.jql;

import co.bskim.jira.inactiveuser.service.UserLookupService;

/**
 * {@code inactiveUser("jmkim")} — 퇴사(비활성·삭제) 계정만 찾는다.
 *
 * <pre>
 * reporter in inactiveUser("jmkim")            사용자명으로
 * reporter in inactiveUser("홍길동")            표시이름 일부로
 * assignee in inactiveUser("jmkim", "jykim")   여러 명 한 번에
 * reporter in inactiveUser()                   퇴사자 전원
 * </pre>
 */
public class InactiveUserFunction extends AbstractUserLookupFunction {

    @Override
    protected UserLookupService.Mode mode() {
        return UserLookupService.Mode.INACTIVE_ONLY;
    }
}
