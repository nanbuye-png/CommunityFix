package com.communityfix.common;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** Internal, parameter-bound SQL. SQL text is never accepted from an HTTP request. */
@Mapper
public interface SqlMapper {
    @SelectProvider(type=Provider.class,method="sql") List<Map<String,Object>> query(SqlCall call);
    @UpdateProvider(type=Provider.class,method="sql") int execute(SqlCall call);
    @InsertProvider(type=Provider.class,method="sql") @Options(useGeneratedKeys=true,keyProperty="id") int insert(SqlCall call);
    class Provider {
        public static String sql(SqlCall call) {
            StringBuilder out=new StringBuilder(); int index=0;
            for (char c:call.sql.toCharArray()) { if(c=='?') out.append("#{args[").append(index++).append("],javaType=java.lang.Object}"); else out.append(c); }
            if(index!=call.args.length) throw new IllegalArgumentException("SQL argument count mismatch");
            return out.toString();
        }
    }
}
