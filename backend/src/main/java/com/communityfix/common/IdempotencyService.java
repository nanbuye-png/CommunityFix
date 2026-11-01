package com.communityfix.common;

import com.communityfix.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private final Db db;private final ObjectMapper json;private final Clock clock;
    public IdempotencyService(Db db,ObjectMapper json,Clock clock){this.db=db;this.json=json;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public Object run(String action,String key,Object input,Supplier<Object> operation) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}")) throw ApiException.bad("请提供8到64位的Idempotency-Key");
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));}catch(Exception ex){throw new IllegalStateException(ex);}
        long user=CurrentUser.get().id();
        db.execute("INSERT INTO idempotency_record(user_id,action,idempotency_key,request_hash,expires_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",user,action,key,hash,clock.instant().plusSeconds(86400));
        var row=db.required("SELECT * FROM idempotency_record WHERE user_id=? AND action=? AND idempotency_key=? FOR UPDATE",user,action,key);
        if(!hash.equals(row.get("requestHash")))throw new ApiException(409,"IDEMPOTENCY_CONFLICT","同一提交标识不能用于不同内容");
        if(row.get("responseJson")!=null) {
            try{return json.readValue(asJson(row.get("responseJson")),Object.class);}catch(Exception ex){throw new IllegalStateException(ex);}
        }
        Object result=operation.get();
        try{db.execute("UPDATE idempotency_record SET response_json=?,http_status=200 WHERE id=?",json.writeValueAsString(result),row.get("id"));}catch(Exception ex){throw new IllegalStateException(ex);}
        return result;
    }
    private String asJson(Object value){return value instanceof byte[] b?new String(b,StandardCharsets.UTF_8):value.toString();}
}
