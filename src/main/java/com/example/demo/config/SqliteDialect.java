package com.example.demo.config;

import org.springframework.data.relational.core.dialect.AbstractDialect;
import org.springframework.data.relational.core.dialect.LimitClause;
import org.springframework.data.relational.core.dialect.LockClause;
import org.springframework.data.relational.core.sql.LockOptions;

public class SqliteDialect extends AbstractDialect {
    public static final SqliteDialect INSTANCE = new SqliteDialect();

    private SqliteDialect() {}

    @Override
    public LimitClause limit() {
        return new LimitClause() {
            @Override
            public String getLimit(long limit) {
                return "LIMIT " + limit;
            }

            @Override
            public String getOffset(long offset) {
                return "OFFSET " + offset;
            }

            @Override
            public String getLimitOffset(long limit, long offset) {
                return "LIMIT " + limit + " OFFSET " + offset;
            }

            @Override
            public Position getClausePosition() {
                return Position.AFTER_ORDER_BY;
            }
        };
    }

    @Override
    public LockClause lock() {
        return new LockClause() {
            @Override
            public String getLock(LockOptions lockOptions) {
                return ""; // SQLite不支持标准的锁语法
            }

            @Override
            public Position getClausePosition() {
                return Position.AFTER_ORDER_BY;
            }
        };
    }
}