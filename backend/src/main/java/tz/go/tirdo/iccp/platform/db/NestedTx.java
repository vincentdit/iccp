package tz.go.tirdo.iccp.platform.db;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Executes work inside a savepoint. If the database rejects it, only the savepoint is rolled back
 * and the error is returned, so the surrounding transaction (e.g. a Finance import) can quarantine
 * the row and continue.
 */
public final class NestedTx {

    private final TransactionTemplate template;

    NestedTx(TransactionTemplate template) {
        this.template = template;
    }

    /** @return empty on success, or the database error that rolled the savepoint back */
    public Optional<DataAccessException> tryRun(Runnable work) {
        try {
            template.executeWithoutResult(status -> work.run());
            return Optional.empty();
        } catch (DataAccessException e) {
            return Optional.of(e);
        }
    }

    public <T> T run(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
