package co.bskim.jira.inactiveuser.service;

/**
 * 조회된 사용자 1건. JQL 리터럴로 나가는 값은 {@link #username} 하나뿐이고,
 * 나머지 필드는 REST 조회 화면에서 "이 사람이 맞나" 확인하는 용도다.
 */
public class UserMatch {

    /** JQL 리터럴로 내보내는 값. Jira의 UserResolver가 app_user 테이블로 키를 찾아준다. */
    public final String username;

    /** app_user.user_key. 디렉터리에서 지워진 계정도 이 값은 남아 있다. */
    public final String key;

    public final String displayName;
    public final String emailAddress;

    /** 디렉터리 기준 활성 여부. 디렉터리에 없는 유령 계정은 false. */
    public final boolean active;

    /** 사용자 디렉터리(LDAP/내부)에 실제 계정이 남아 있는지. false면 이슈에만 흔적이 남은 상태. */
    public final boolean existsInDirectory;

    public UserMatch(String username, String key, String displayName, String emailAddress,
                     boolean active, boolean existsInDirectory) {
        this.username = username;
        this.key = key;
        this.displayName = displayName;
        this.emailAddress = emailAddress;
        this.active = active;
        this.existsInDirectory = existsInDirectory;
    }

    /** 퇴사자 판정: 비활성이거나, 디렉터리에서 아예 사라진 계정. */
    public boolean isInactive() {
        return !active || !existsInDirectory;
    }

    @Override
    public String toString() {
        return username + "(" + key + ", active=" + active + ", inDirectory=" + existsInDirectory + ")";
    }
}
