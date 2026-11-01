package com.communityfix;

import com.communityfix.common.Db;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"communityfix.wechat.order-template-id=","communityfix.ai.endpoint="})
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class StatisticsIntegrationTest {
    @Autowired Db db;
    @Autowired TestRestTemplate rest;
    @Value("${communityfix.bootstrap-password}") String password;

    @Test void scopedStatisticsPreserveIndependentTimeWindowsAndChartTotals() {
        var login=rest.postForEntity("/api/auth/login",Map.of("username","property","password",password),JsonNode.class);
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(login.getBody().path("data").path("token").asText());
        String path="/api/statistics/overview?start=2040-01-01&end=2040-01-03";
        int pending=stats(path,headers).path("pendingOrders").asInt();
        var location=db.required("SELECT r.id AS room_id,b.community_id,u.id AS resident_id FROM sys_user u "+
            "JOIN resident_room_binding rb ON rb.user_id=u.id JOIN room r ON r.id=rb.room_id "+
            "JOIN unit un ON un.id=r.unit_id JOIN building b ON b.id=un.building_id WHERE u.username='resident' AND rb.status='APPROVED' LIMIT 1");
        long worker=Db.id(db.required("SELECT w.id FROM worker_profile w JOIN sys_user u ON u.id=w.user_id WHERE u.username='worker'"),"id");
        List<Long> ids=new ArrayList<>();
        try {
            ids.add(order(location,1,"CLOSED","2039-12-31 16:00:00","2040-01-01 16:00:00"));
            ids.add(order(location,1,"CLOSED","2040-01-01 15:59:59","2040-01-02 16:00:00"));
            ids.add(order(location,2,"SUBMITTED","2040-01-01 16:00:00",null));
            ids.add(order(location,2,"CLOSED","2039-12-30 16:00:00","2040-01-01 00:00:00"));
            ids.add(order(location,2,"SUBMITTED","2040-01-02 16:00:00",null));
            var foreign=new HashMap<>(location);
            foreign.put("communityId",db.required("SELECT id FROM community WHERE id<>? LIMIT 1",location.get("communityId")).get("id"));
            ids.add(order(foreign,2,"CLOSED","2040-01-01 16:00:00","2040-01-02 00:00:00"));
            for(int level:List.of(2,3))db.execute("INSERT INTO sla_warning(order_id,round_no,phase,level,deadline_at,created_at) VALUES (?,1,'RESPONSE',?,'2040-01-01 00:00:00','2040-01-01 16:00:00')",ids.getFirst(),level);
            for(int i:List.of(0,5))db.execute("INSERT INTO repair_evaluation(order_id,user_id,worker_id,score,created_at) VALUES (?,?,?,?, '2040-01-01 16:00:00')",ids.get(i),location.get("residentId"),worker,i==0?5:1);
            var actual=stats(path,headers);
            assertEquals(3,actual.path("newOrders").asInt());
            assertEquals(2,actual.path("closedOrders").asInt());
            assertEquals(pending+2,actual.path("pendingOrders").asInt());
            assertEquals(1,actual.path("overdueOrders").asInt());
            assertEquals(100800,actual.path("averageProcessingSeconds").asDouble(),0.01);
            assertEquals(1,actual.path("ratings").path("sampleCount").asInt());
            assertEquals(5,actual.path("ratings").path("averageScore").asInt());
            assertEquals(2,actual.path("trend").size());
            assertEquals("2040-01-01",actual.path("trend").get(0).path("day").asText());
            assertEquals(2,actual.path("trend").get(0).path("count").asInt());
            assertEquals("2040-01-02",actual.path("trend").get(1).path("day").asText());
            assertEquals(1,actual.path("trend").get(1).path("count").asInt());
            assertEquals(2,actual.path("categories").size());
            assertEquals(2,actual.path("categories").get(0).path("count").asInt());
            assertEquals(1,actual.path("categories").get(1).path("count").asInt());
            assertEquals(403,rest.exchange(path+"&communityId="+foreign.get("communityId"),HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getStatusCode().value());
        } finally {
            for(long id:ids){
                db.execute("DELETE FROM repair_evaluation WHERE order_id=?",id);
                db.execute("DELETE FROM sla_warning WHERE order_id=?",id);
                db.execute("DELETE FROM repair_order WHERE id=?",id);
            }
        }
    }

    private JsonNode stats(String path,HttpHeaders headers){
        var response=rest.exchange(path,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);
        assertEquals(200,response.getStatusCode().value());return response.getBody().path("data");
    }
    private long order(Map<String,Object> location,long category,String status,String created,String closed){
        long id=db.insert("repair_order",Db.fields("order_no",UUID.randomUUID().toString().replace("-",""),
            "resident_id",location.get("residentId"),"community_id",location.get("communityId"),"room_id",location.get("roomId"),
            "category_id",category,"title","Statistics boundary fixture","description","Synthetic only",
            "priority","NORMAL","status",status,"status_changed_at",created,"response_started_at",created,
            "sla_rule_id",db.required("SELECT id FROM sla_rule WHERE priority='NORMAL'").get("id"),
            "response_minutes_snapshot",120,"completion_minutes_snapshot",1440,"created_at",created,"closed_at",closed));
        return id;
    }
}
