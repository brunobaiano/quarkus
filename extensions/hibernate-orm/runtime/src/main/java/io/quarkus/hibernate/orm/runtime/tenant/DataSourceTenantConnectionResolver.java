package io.quarkus.hibernate.orm.runtime.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;

import jakarta.enterprise.inject.Default;

import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.hibernate.orm.runtime.customized.QuarkusConnectionProvider;
import io.quarkus.hibernate.orm.runtime.migration.MultiTenancyStrategy;

/**
 * Creates a database connection based on the data sources in the configuration file.
 * The tenant identifier is used as the data source name.
 *
 * @author Michael Schnell
 *
 */
public class DataSourceTenantConnectionResolver implements TenantConnectionResolver {

    private static final Logger LOG = Logger.getLogger(DataSourceTenantConnectionResolver.class);

    private String persistenceUnitName;

    private Optional<String> dataSourceName;

    private MultiTenancyStrategy multiTenancyStrategy;

    private String multiTenancySchemaDataSourceName;

    public DataSourceTenantConnectionResolver() {
    }

    public DataSourceTenantConnectionResolver(String persistenceUnitName, Optional<String> dataSourceName,
            MultiTenancyStrategy multiTenancyStrategy, String multiTenancySchemaDataSourceName) {
        this.persistenceUnitName = persistenceUnitName;
        this.dataSourceName = dataSourceName;
        this.multiTenancyStrategy = multiTenancyStrategy;
        this.multiTenancySchemaDataSourceName = multiTenancySchemaDataSourceName;
    }

    @Override
    public ConnectionProvider resolve(String tenantId) {
        LOG.debugv("resolve((persistenceUnitName={0}, tenantIdentifier={1})", persistenceUnitName, tenantId);
        LOG.debugv("multitenancy strategy: {0}", multiTenancyStrategy);

        AgroalDataSource dataSource = tenantDataSource(dataSourceName, tenantId, multiTenancyStrategy,
                multiTenancySchemaDataSourceName);
        if (dataSource == null) {
            throw new IllegalStateException(
                    String.format(Locale.ROOT, "No instance of datasource found for persistence unit '%1$s' and tenant '%2$s'",
                            persistenceUnitName, tenantId));
        }
        if (multiTenancyStrategy == MultiTenancyStrategy.SCHEMA) {
            return new SchemaTenantConnectionProvider(tenantId, dataSource);
        }
        return new QuarkusConnectionProvider(dataSource);
    }

    private static AgroalDataSource tenantDataSource(Optional<String> dataSourceName, String tenantId,
            MultiTenancyStrategy strategy, String multiTenancySchemaDataSourceName) {
        if (strategy != MultiTenancyStrategy.SCHEMA) {
            return Arc.container().instance(AgroalDataSource.class, new DataSource.DataSourceLiteral(tenantId)).get();
        }

        if (multiTenancySchemaDataSourceName == null) {
            // The datasource name should always be present when using a multi-tenancy other than DATABASE;
            // we perform checks in HibernateOrmProcessor during the build.
            return getDataSource(dataSourceName.get());
        }

        return getDataSource(multiTenancySchemaDataSourceName);
    }

    private static AgroalDataSource getDataSource(String dataSourceName) {
        if (DataSourceUtil.isDefault(dataSourceName)) {
            return Arc.container().instance(AgroalDataSource.class, Default.Literal.INSTANCE).get();
        } else {
            return Arc.container().instance(AgroalDataSource.class, new DataSource.DataSourceLiteral(dataSourceName)).get();
        }
    }

    private static class SchemaTenantConnectionProvider extends QuarkusConnectionProvider {

        private final String tenantId;

        public SchemaTenantConnectionProvider(String tenantId, AgroalDataSource dataSource) {
            super(dataSource);
            this.tenantId = tenantId;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = super.getConnection();
            conn.setSchema(tenantId);
            LOG.debugv("Set tenant {0} for connection: {1}", tenantId, conn);
            return conn;
        }

    }

}
