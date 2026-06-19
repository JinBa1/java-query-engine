package com.github.jinba1.cuckoodb.server.query;

import com.github.jinba1.cuckoodb.CuckooDB;
import com.github.jinba1.cuckoodb.ErrorCode;
import com.github.jinba1.cuckoodb.PlannedQuery;
import com.github.jinba1.cuckoodb.QueryBudget;
import com.github.jinba1.cuckoodb.QueryConfig;
import com.github.jinba1.cuckoodb.QueryExecutionException;
import com.github.jinba1.cuckoodb.QueryPlanner;
import com.github.jinba1.cuckoodb.QueryResultSet;
import com.github.jinba1.cuckoodb.operator.Operator;
import com.github.jinba1.cuckoodb.server.audit.AuditEvent;
import com.github.jinba1.cuckoodb.server.audit.AuditSink;

import org.springframework.stereotype.Service;

/**
 * The single choke point through which every query passes, so governance, budgeting,
 * concurrency bounding, and audit cannot be bypassed by any controller.
 *
 * <p>The pipeline per request: acquire a concurrency permit → plan the SQL (String overload) →
 * branch EXPLAIN (returns plan text, zero tuple budget) → clamp and attach a fail-closed budget
 * → drain in memory → audit. A budget is <em>always</em> attached for an executed query: the
 * engine treats a null budget as unlimited, and {@link BudgetPolicy} never yields null, so the
 * unbounded path is unreachable here.
 */
@Service
public class QueryService {

    private final BudgetPolicy budgetPolicy;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final AuditSink auditSink;

    public QueryService(BudgetPolicy budgetPolicy, ConcurrencyLimiter concurrencyLimiter,
                        AuditSink auditSink) {
        this.budgetPolicy = budgetPolicy;
        this.concurrencyLimiter = concurrencyLimiter;
        this.auditSink = auditSink;
    }

    /**
     * Plans and (unless EXPLAIN) executes one query under a clamped budget, holding a
     * concurrency permit for the whole call.
     *
     * @param sql        the SQL text (optionally EXPLAIN-prefixed); never a file path
     * @param maxTuples  client tuple bound, or null to take the configured default
     * @param timeoutMs  client time bound, or null to take the configured default
     * @param principal  caller label for the audit trail
     * @return the executed result set or the EXPLAIN plan text
     * @throws QueryExecutionException (classified by {@code ErrorCode}) on any engine failure
     */
    public QueryServiceResult execute(String sql, Long maxTuples, Long timeoutMs, String principal) {
        return concurrencyLimiter.withPermit(() -> runPlanned(sql, maxTuples, timeoutMs, principal));
    }

    private QueryServiceResult runPlanned(String sql, Long maxTuples, Long timeoutMs,
                                          String principal) {
        try {
            PlannedQuery planned = QueryPlanner.planSql(sql, QueryConfig.defaults());

            if (planned.explainText() != null) {
                auditSink.record(AuditEvent.explain(principal, sql, planned.explainText()));
                return QueryServiceResult.explain(planned.explainText());
            }

            BudgetPolicy.ClampedBudget budget = budgetPolicy.clamp(maxTuples, timeoutMs);
            Operator root = planned.root();
            root.attachBudget(new QueryBudget(budget.maxTuples(), budget.timeoutMs()));

            QueryResultSet resultSet = CuckooDB.executeToResultSet(root);
            auditSink.record(AuditEvent.success(
                    principal, sql, resultSet.rows().size(), resultSet.truncated()));
            return QueryServiceResult.of(resultSet);
        } catch (QueryExecutionException e) {
            auditSink.record(AuditEvent.error(principal, sql, e.code().name()));
            throw e;
        } catch (RuntimeException e) {
            // An unchecked failure (engine bug, or a budget-validation IllegalArgumentException)
            // must still leave an audit trail — the choke point's audit cannot be bypassed.
            auditSink.record(AuditEvent.error(principal, sql, ErrorCode.INTERNAL.name()));
            throw e;
        }
    }
}
