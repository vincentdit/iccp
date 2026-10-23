package tz.go.tirdo.iccp.platform.db;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement
public class DbConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DbSessionTransactionManager(dataSource);
    }

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /** Runs a block in a new transaction (used by jobs that run under a system context). */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    /** Savepoint inside the current transaction: one bad import row does not roll back the batch. */
    @Bean
    public NestedTx nestedTx(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return new NestedTx(t);
    }
}
