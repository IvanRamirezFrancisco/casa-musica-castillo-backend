package com.security.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/** PostgreSQL text folding for catalog predicates and ranking, independent of installed extensions. */
public final class CatalogSearchFunctions implements FunctionContributor {
    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        var stringType = contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING);
        contributions.getFunctionRegistry().registerPattern("catalog_fold",
                "pg_catalog.lower(pg_catalog.regexp_replace(pg_catalog.normalize(cast(?1 as text), 'NFD'), "
                        + "U&'[\\0300-\\036F\\1AB0-\\1AFF\\1DC0-\\1DFF\\20D0-\\20FF\\FE20-\\FE2F]', '', 'g'))",
                stringType);
    }
}
