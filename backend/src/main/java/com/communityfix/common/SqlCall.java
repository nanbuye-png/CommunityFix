package com.communityfix.common;

public class SqlCall {
    public final String sql;
    public final Object[] args;
    public Long id;
    public SqlCall(String sql,Object... args) { this.sql=sql; this.args=args; }
}
