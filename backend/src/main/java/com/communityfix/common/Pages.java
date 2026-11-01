package com.communityfix.common;

import java.util.Map;

public final class Pages {
    private Pages() {}
    public static int size(int size) { if(size<1||size>100) throw ApiException.bad("每页数量必须在1到100之间");return size; }
    public static int offset(int page,int size) { if(page<1 || page>1000000) throw ApiException.bad("页码无效");return (page-1)*size(size); }
    public static Map<String,Object> result(Object rows,long total,int page,int size) { return Db.fields("records",rows,"total",Math.toIntExact(total),"page",page,"size",size); }
}
