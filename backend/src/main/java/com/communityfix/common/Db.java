package com.communityfix.common;

import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class Db {
    private final SqlMapper mapper;
    public Db(SqlMapper mapper) { this.mapper=mapper; }
    public List<Map<String,Object>> rows(String sql,Object... args) {
        return mapper.query(new SqlCall(sql,args)).stream().map(raw->{
            Map<String,Object> row=new LinkedHashMap<>();
            raw.forEach((key,value)->{
                StringBuilder name=new StringBuilder(); boolean upper=false;
                for(char c:key.toLowerCase(Locale.ROOT).toCharArray()) { if(c=='_') upper=true; else { name.append(upper?Character.toUpperCase(c):c); upper=false; } }
                if(value instanceof Timestamp t) value=t.toInstant();
                if(value instanceof LocalDateTime t) value=t.toInstant(ZoneOffset.UTC);
                row.put(name.toString(),value);
            }); return row;
        }).toList();
    }
    public Map<String,Object> row(String sql,Object... args) { var rows=rows(sql,args); return rows.isEmpty()?null:rows.getFirst(); }
    public Map<String,Object> required(String sql,Object... args) { var row=row(sql,args); if(row==null) throw ApiException.missing(); return row; }
    public long count(String sql,Object... args) { String aliased=sql.replaceFirst("(?i)^SELECT COUNT\\(([^)]*)\\)","SELECT COUNT($1) AS total_count");var row=required(aliased,args);return ((Number)row.get("totalCount")).longValue(); }
    public int execute(String sql,Object... args) { return mapper.execute(new SqlCall(sql,args)); }
    public long insert(String table,Map<String,Object> fields) {
        if(!table.matches("[a-z_]+") || fields.keySet().stream().anyMatch(k->!k.matches("[a-z_]+"))) throw new IllegalArgumentException("Invalid internal identifier");
        String sql="INSERT INTO "+table+" ("+String.join(",",fields.keySet())+") VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+")";
        SqlCall call=new SqlCall(sql,fields.values().toArray()); mapper.insert(call); return call.id;
    }
    public static Map<String,Object> fields(Object... pairs) { Map<String,Object> map=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]); return map; }
    public static long id(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public static int integer(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
}
