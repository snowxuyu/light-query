package com.lightquery.spring;

import com.lightquery.LightQuery;
import com.lightquery.LightQuerySession;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Auto-configuration of light-query. When the context has a single candidate
 * {@link DataSource}, this registers:
 *
 * <ul>
 *   <li>a {@link LightQuerySession} bean whose statements participate in
 *       Spring transactions (via {@link SpringConnectionProvider}), and</li>
 *   <li>the same data source as the primary of the static {@link LightQuery}
 *       facade, so {@code LightQuery.queryable(...)} calls resolve against it.</li>
 * </ul>
 *
 * <p>Defining your own {@code LightQuerySession} bean skips this
 * configuration. Applications with several data sources wire their sessions
 * manually via {@code LightQuery.datasource(name, dataSource, provider)}.</p>
 */
@AutoConfiguration
@ConditionalOnClass(LightQuery.class)
@ConditionalOnSingleCandidate(DataSource.class)
public class LightQueryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LightQuerySession lightQuerySession(DataSource dataSource) {
        return LightQuery.primary(dataSource, new SpringConnectionProvider(dataSource));
    }
}
