package com.communityfix.module.billing;

import com.communityfix.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

/** Called inside the order transaction; the order row is locked by the caller. */
@Service
public class AutomaticQuoteService {
    @org.springframework.beans.factory.annotation.Autowired private com.communityfix.module.notification.NotificationService notifications;
    private final Db db;private final ObjectMapper json;
    public AutomaticQuoteService(Db db,ObjectMapper json){this.db=db;this.json=json;}
    public Map<String,Object> reference(Map<String,Object> order){
        return db.row("SELECT * FROM repair_reference_price WHERE community_id=? AND category_id=?",order.get("communityId"),order.get("categoryId"));
    }
    public void create(Map<String,Object> order){
        if(Db.integer(order,"billingVersion")!=1||!"PRIVATE".equals(order.get("facilityScope"))||!"PENDING_DISPATCH".equals(order.get("status")))return;
        long id=Db.id(order,"id");
        if(db.count("SELECT COUNT(*) FROM repair_quote WHERE order_id=? AND status<>'SUPERSEDED'",id)>0)return;
        int revision=((Number)db.required("SELECT COALESCE(MAX(revision),0) AS revision FROM repair_quote WHERE order_id=?",id).get("revision")).intValue()+1;
        var price=reference(order);if(price==null||Boolean.FALSE.equals(price.get("enabled"))||price.get("enabled") instanceof Number n&&n.intValue()==0)return;
        long labor=Db.id(price,"laborFen"),material=Db.id(price,"materialFen");
        List<Map<String,Object>> items=new ArrayList<>();
        if(labor>0)items.add(Db.fields("name","人工费","quantity",1,"unitPriceFen",labor));
        if(material>0)items.add(Db.fields("name","材料费","quantity",1,"unitPriceFen",material));
        try{
            long quote=db.insert("repair_quote",Db.fields("order_id",id,"revision",revision,"items_json",json.writeValueAsString(items),"amount_fen",Math.addExact(labor,material),"reason","自动报价，参考依据："+price.get("basis"),"created_by",price.get("updatedBy")));
            notifications.quote(order,quote,Math.addExact(labor,material));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
}
