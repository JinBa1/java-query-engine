package com.github.jinba1.cuckoodb.server.web;

import com.github.jinba1.cuckoodb.server.query.ApiPrincipalResolver;
import com.github.jinba1.cuckoodb.server.query.QueryService;
import com.github.jinba1.cuckoodb.server.query.QueryServiceResult;
import com.github.jinba1.cuckoodb.server.web.dto.QueryRequest;
import com.github.jinba1.cuckoodb.server.web.dto.QueryResponse;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code POST /queries}: plan and synchronously execute one read-only SQL query, returning a
 * structured JSON result (or EXPLAIN plan text). All governance — budget, concurrency, audit —
 * lives behind {@link QueryService}; this controller only adapts HTTP to that call.
 */
@RestController
@RequestMapping("/queries")
public class QueryController {

    private final QueryService queryService;
    private final ApiPrincipalResolver principalResolver;

    public QueryController(QueryService queryService, ApiPrincipalResolver principalResolver) {
        this.queryService = queryService;
        this.principalResolver = principalResolver;
    }

    @PostMapping
    public QueryResponse query(@RequestBody QueryRequest request, HttpServletRequest httpRequest) {
        String principal = principalResolver.resolve(httpRequest);
        QueryServiceResult result = queryService.execute(
                request.sql(), request.maxTuples(), request.timeoutMs(), principal);
        return result.isExplain()
                ? QueryResponse.fromExplain(result.explainText())
                : QueryResponse.fromResultSet(result.resultSet());
    }
}
