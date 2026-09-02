package co.bskim.jira.inactiveuser.jql;

import co.bskim.jira.inactiveuser.service.UserLookupService;
import com.atlassian.jira.JiraDataType;
import com.atlassian.jira.JiraDataTypes;
import com.atlassian.jira.jql.operand.QueryLiteral;
import com.atlassian.jira.jql.query.QueryCreationContext;
import com.atlassian.jira.plugin.jql.function.AbstractJqlFunction;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.MessageSet;
import com.atlassian.jira.util.MessageSetImpl;
import com.atlassian.query.clause.TerminalClause;
import com.atlassian.query.operand.FunctionOperand;

import java.util.ArrayList;
import java.util.List;

/**
 * 사용자 필드(reporter/assignee/creator/사용자 커스텀필드)에 붙는 JQL 함수의 공통 뼈대.
 *
 * <p>리터럴로는 <b>사용자명</b>을 내보낸다. Jira 기본 함수인 {@code currentUser()}·{@code membersOf()}가
 * 그렇게 하고 있고, 사용자 필드의 절 처리기가 그 문자열을 UserResolver에 넘겨 app_user 테이블에서
 * 사용자 키를 찾아 인덱스와 대조하기 때문이다. 사용자 키를 직접 리터럴로 내보내면 이 해석 단계에서
 * 매칭에 실패한다.
 */
public abstract class AbstractUserLookupFunction extends AbstractJqlFunction {

    private final UserLookupService lookupService = new UserLookupService();

    /** 이 함수가 훑을 사용자 범위. */
    protected abstract UserLookupService.Mode mode();

    @Override
    public JiraDataType getDataType() {
        return JiraDataTypes.USER;
    }

    @Override
    public boolean isList() {
        return true;
    }

    /** 인수 없이 부르면 "해당 범위 전원"을 뜻하므로 최소 인수는 0이다. */
    @Override
    public int getMinimumNumberOfExpectedArguments() {
        return 0;
    }

    @Override
    public MessageSet validate(ApplicationUser searcher, FunctionOperand operand, TerminalClause terminalClause) {
        MessageSet messages = new MessageSetImpl();
        List<String> matched = lookupService.usernames(operand.getArgs(), mode());
        if (matched.isEmpty()) {
            // 오류로 올리면 검색 자체가 막힌다. 결과가 0건인 것과 오타인 것을 사용자가 구분할 수
            // 있게 경고만 남기고 검색은 그대로 수행시킨다.
            messages.addWarningMessage(getI18n().getText(
                    "inactiveuser.function.warn.nomatch",
                    operand.getName(),
                    join(operand.getArgs())));
        } else if (matched.size() >= UserLookupService.MAX_RESULTS) {
            messages.addWarningMessage(getI18n().getText(
                    "inactiveuser.function.warn.truncated",
                    operand.getName(),
                    String.valueOf(UserLookupService.MAX_RESULTS)));
        }
        return messages;
    }

    @Override
    public List<QueryLiteral> getValues(QueryCreationContext queryCreationContext,
                                        FunctionOperand operand,
                                        TerminalClause terminalClause) {
        List<String> usernames = lookupService.usernames(operand.getArgs(), mode());
        List<QueryLiteral> literals = new ArrayList<QueryLiteral>(usernames.size());
        for (String username : usernames) {
            literals.add(new QueryLiteral(operand, username));
        }
        return literals;
    }

    private String join(List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(arg);
        }
        return sb.toString();
    }
}
