package co.bskim.jira.inactiveuser.rest.dto;

import co.bskim.jira.inactiveuser.service.UserMatch;

/** REST 응답용 사용자 1건. */
public class UserMatchDto {

    public String username;
    public String key;
    public String displayName;
    public String emailAddress;
    public boolean active;
    public boolean existsInDirectory;

    /** JQL에 그대로 붙여넣을 수 있는 형태. */
    public String jqlValue;

    public UserMatchDto() {
    }

    public UserMatchDto(UserMatch match) {
        this.username = match.username;
        this.key = match.key;
        this.displayName = match.displayName;
        this.emailAddress = match.emailAddress;
        this.active = match.active;
        this.existsInDirectory = match.existsInDirectory;
        this.jqlValue = "\"" + match.username + "\"";
    }
}
