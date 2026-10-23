package tz.go.tirdo.iccp.platform.db;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Transaction manager that tells PostgreSQL who is acting, at the start of every transaction.
 *
 * <p>Sets the transaction-local settings {@code iccp.actor}, {@code iccp.staff_id} and
 * {@code iccp.fin_scope}. They are reset automatically at commit or rollback, so pooled
 * connections never leak one user's identity to the next request.
 */
public class DbSessionTransactionManager extends JdbcTransactionManager {

    private static final String SQL =
            "SELECT set_config('iccp.actor', ?, true), set_config('iccp.staff_id', ?, true), set_config('iccp.fin_scope', ?, true)";

    public DbSessionTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition) throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        DbSessionContext ctx = DbSessionContext.current();
        try (PreparedStatement ps = con.prepareStatement(SQL)) {
            ps.setString(1, ctx == null ? "anonymous" : ctx.actor());
            ps.setString(2, ctx == null || ctx.staffId() == null ? "" : ctx.staffId().toString());
            ps.setString(3, ctx != null && ctx.finScope() ? "ALL" : "");
            ps.execute();
        }
    }
}
