package co.bskim.jira.inactiveuser.rest;

import co.bskim.jira.inactiveuser.rest.dto.UserMatchDto;
import co.bskim.jira.inactiveuser.service.UserLookupService;
import co.bskim.jira.inactiveuser.service.UserMatch;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.user.ApplicationUser;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 조회 보조 엔드포인트. JQL 함수가 실제로 누구를 집어내는지 눈으로 확인할 때 쓴다.
 *
 * <pre>
 * GET /rest/inactive-user-search/1.0/lookup?q=jmkim&amp;mode=inactive
 * </pre>
 */
@Path("/lookup")
@Produces(MediaType.APPLICATION_JSON)
public class LookupResource {

    private final UserLookupService lookupService = new UserLookupService();

    @GET
    public Response lookup(@QueryParam("q") String query, @QueryParam("mode") String mode) {
        ApplicationUser caller = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (caller == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        UserLookupService.Mode lookupMode = "any".equalsIgnoreCase(mode)
                ? UserLookupService.Mode.ANY
                : UserLookupService.Mode.INACTIVE_ONLY;

        List<String> terms = (query == null || query.trim().isEmpty())
                ? Collections.<String>emptyList()
                : Arrays.asList(query.split(","));

        List<UserMatchDto> results = new ArrayList<UserMatchDto>();
        for (UserMatch match : lookupService.lookup(terms, lookupMode)) {
            results.add(new UserMatchDto(match));
        }
        return Response.ok(results).build();
    }
}
