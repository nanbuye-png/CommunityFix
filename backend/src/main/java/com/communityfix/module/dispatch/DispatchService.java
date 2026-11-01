package com.communityfix.module.dispatch;

import com.communityfix.common.*;
import com.communityfix.module.repair.OrderService;
import com.communityfix.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class DispatchService {
    private final Db db;private final OrderService orders;private final ObjectMapper json;
    public DispatchService(Db db,OrderService orders,ObjectMapper json){this.db=db;this.orders=orders;this.json=json;}
    public static double score(double skill,int active,int capacity,double rating){return 100*(.4*skill+.3*Math.max(0,1-(double)active/capacity)+.2+.1*rating);}
    @Transactional public Object recommend(long id){var order=orders.read(id);CurrentUser.get().community(Db.id(order,"communityId"));if(!"PENDING_DISPATCH".equals(order.get("status")))throw ApiException.conflict();
        var candidates=db.rows("SELECT w.id AS worker_id,w.real_name AS name,w.max_active_orders,s.proficiency,(SELECT COUNT(*) FROM repair_order o WHERE o.worker_id=w.id AND o.status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS')) AS active_orders,(SELECT AVG(e.score)/5 FROM repair_evaluation e WHERE e.worker_id=w.id) AS rating,(SELECT COUNT(*) FROM repair_evaluation e WHERE e.worker_id=w.id) AS rating_count FROM worker_profile w JOIN sys_user u ON u.id=w.user_id JOIN worker_skill s ON s.worker_id=w.id WHERE s.category_id=? AND w.work_status='AVAILABLE' AND u.status='ENABLED' AND EXISTS(SELECT 1 FROM worker_service_community sc WHERE sc.worker_id=w.id AND sc.community_id=?)",order.get("categoryId"),order.get("communityId"));
        List<Map<String,Object>> eligible=new ArrayList<>();for(var row:candidates){int active=Db.integer(row,"activeOrders"),capacity=Db.integer(row,"maxActiveOrders");if(active>=capacity)continue;double skill=((Number)row.get("proficiency")).doubleValue(),rating=row.get("rating")==null?.6:((Number)row.get("rating")).doubleValue();row.put("score",score(skill,active,capacity,rating));row.put("factorScores",Db.fields("skill",skill,"load",1-(double)active/capacity,"area",1,"rating",rating));row.put("reason","技能匹配 · 当前"+active+"单 / 容量"+capacity+"单");eligible.add(row);}
        eligible.sort(Comparator.<Map<String,Object>>comparingDouble(r->-((Number)r.get("score")).doubleValue()).thenComparingInt(r->Db.integer(r,"activeOrders")).thenComparingLong(r->Db.id(r,"workerId")));
        String batch=UUID.randomUUID().toString();List<Map<String,Object>> top=eligible.stream().limit(5).toList();
        for(var row:top){try{long rid=db.insert("dispatch_recommendation",Db.fields("order_id",id,"worker_id",row.get("workerId"),"order_version",order.get("version"),"score",row.get("score"),"factor_scores",json.writeValueAsString(row.get("factorScores")),"reason",row.get("reason"),"strategy_version","weighted-v1","batch_id",batch));row.put("recommendationId",rid);}catch(Exception ex){throw new IllegalStateException(ex);}}
        return top;
    }
}
