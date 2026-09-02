package co.bskim.jira.inactiveuser.jql;

import co.bskim.jira.inactiveuser.service.UserLookupService;

/**
 * {@code anyUser("jmkim")} — 활성·비활성·삭제 계정을 가리지 않고 찾는다.
 *
 * <p>인수인계 받은 사람은 보통 그 계정이 아직 살아 있는지조차 모른다. 계정 상태를 신경 쓰지 않고
 * 한 번에 훑고 싶을 때 이 함수를 쓰면 된다.
 *
 * <pre>
 * reporter in anyUser("jmkim")
 * reporter in anyUser("홍길동")
 * </pre>
 */
public class AnyUserFunction extends AbstractUserLookupFunction {

    @Override
    protected UserLookupService.Mode mode() {
        return UserLookupService.Mode.ANY;
    }
}
