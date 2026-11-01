package com.communityfix.module.statistics;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api/statistics")
public class StatisticsController {
    private final Db db;private final Clock clock;
    public StatisticsController(Db db,Clock clock){this.db=db;this.clock=clock;}
    @GetMapping("/overview") ApiResponse<?> overview(@RequestParam(required=false) LocalDate start,@RequestParam(required=false) LocalDate end,@RequestParam(required=false) Long communityId){
        var user=CurrentUser.get();user.role("ADMIN");user.permission("statistics:view");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));if(start==null)start=today.withDayOfMonth(1);if(end==null)end=today.plusDays(1);if(!end.isAfter(start)||end.isAfter(start.plusDays(366)))throw ApiException.bad("统计范围须为1到366天，结束日期不包含在内");
        List<Long> ids;if(communityId!=null){user.community(communityId);ids=List.of(communityId);}else ids=new ArrayList<>(user.communities());
        String scope=ids.isEmpty()?"1=0":"o.community_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        Instant from=start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),to=end.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        List<Object> range=new ArrayList<>(ids);range.add(from);range.add(to);
        // Read the scoped orders once for closed/pending counts and duration.
        // These use different time semantics from the creation-date charts.
        List<Object> summaryArgs=new ArrayList<>(List.of(from,to,from,to));summaryArgs.addAll(ids);
        var summary=db.required("SELECT COUNT(CASE WHEN o.status='CLOSED' AND o.closed_at>=? AND o.closed_at<? THEN 1 END) AS closed_orders,"+
            "COUNT(CASE WHEN o.status NOT IN ('CLOSED','REJECTED','CANCELED') THEN 1 END) AS pending_orders,"+
            "AVG(CASE WHEN o.status='CLOSED' AND o.closed_at>=? AND o.closed_at<? THEN TIMESTAMPDIFF(SECOND,o.created_at,o.closed_at) END) AS avg_seconds "+
            "FROM repair_order o WHERE "+scope,summaryArgs.toArray());
        Map<String,Object> result=Db.fields("start",start,"end",end,"closedOrders",Db.integer(summary,"closedOrders"),
            "pendingOrders",Db.integer(summary,"pendingOrders"),"averageProcessingSeconds",summary.get("avgSeconds"),
            "overdueOrders",(int)db.count("SELECT COUNT(DISTINCT o.id) FROM repair_order o JOIN sla_warning w ON w.order_id=o.id WHERE "+scope+" AND w.created_at>=? AND w.created_at<? AND w.level>=2",range.toArray()));
        var ratings=db.required("SELECT COUNT(*) AS sample_count,AVG(e.score) AS average_score,AVG(CASE WHEN e.score>=4 THEN 1 ELSE 0 END) AS satisfaction FROM repair_evaluation e JOIN repair_order o ON o.id=e.order_id WHERE "+scope+" AND e.created_at>=? AND e.created_at<?",range.toArray());result.put("ratings",ratings);
        // Aggregate before joining the small category dictionary. Reuse the
        // bounded day/category buckets for both charts and the total count.
        var buckets=db.rows("SELECT b.day,b.category_id,c.name,b.count FROM ("+
            "SELECT DATE(CONVERT_TZ(o.created_at,'+00:00','+08:00')) AS day,o.category_id,COUNT(*) AS count "+
            "FROM repair_order o WHERE "+scope+" AND o.created_at>=? AND o.created_at<? GROUP BY day,o.category_id"+
            ") b JOIN repair_category c ON c.id=b.category_id ORDER BY b.day,b.category_id",range.toArray());
        Map<String,Map<String,Object>> days=new TreeMap<>();
        Map<Long,Map<String,Object>> categories=new LinkedHashMap<>();long total=0;
        for(var bucket:buckets){
            long count=Db.id(bucket,"count");total+=count;
            var day=days.computeIfAbsent(bucket.get("day").toString(),k->Db.fields("day",bucket.get("day"),"count",0L));
            day.put("count",Db.id(day,"count")+count);
            var category=categories.computeIfAbsent(Db.id(bucket,"categoryId"),k->Db.fields("name",bucket.get("name"),"count",0L));
            category.put("count",Db.id(category,"count")+count);
        }
        result.put("newOrders",Math.toIntExact(total));result.put("trend",new ArrayList<>(days.values()));
        result.put("categories",categories.values().stream().sorted(Comparator.comparingLong((Map<String,Object> c)->Db.id(c,"count")).reversed()).toList());
        return ApiResponse.ok(result);
    }
}
