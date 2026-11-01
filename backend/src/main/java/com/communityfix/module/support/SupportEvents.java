package com.communityfix.module.support;

import com.communityfix.common.*;
import com.communityfix.security.*;
import jakarta.annotation.PreDestroy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SupportEvents {
 private final TokenService tokens;private final IdentityService identities;private final SupportService service;
 private final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2);
 private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
 private final Set<Watch> watches=ConcurrentHashMap.newKeySet();
 private static class Watch {String auth;long id,after,until;DeferredResult<Object> result;volatile boolean running;}
 public SupportEvents(TokenService tokens,IdentityService identities,SupportService service){this.tokens=tokens;this.identities=identities;this.service=service;timer.scheduleWithFixedDelay(this::tick,250,250,TimeUnit.MILLISECONDS);}
 public DeferredResult<Object> watch(String auth,long id,long after,boolean wait){
  if(watches.size()>=500)throw new ApiException(429,"SUPPORT_BUSY","连接较多，请稍后重试");
  var w=new Watch();w.auth=auth;w.id=id;w.after=after;w.until=System.currentTimeMillis()+(wait?10000:0);w.result=new DeferredResult<>(12000L);
  w.result.onCompletion(()->watches.remove(w));w.result.onTimeout(()->{w.until=0;check(w);});watches.add(w);check(w);return w.result;
 }
 private void tick(){for(var w:watches)if(!w.running&&!w.result.isSetOrExpired()){w.running=true;workers.submit(()->{try{check(w);}finally{w.running=false;}});}}
 @SuppressWarnings("unchecked") private void check(Watch w){
  try{var token=tokens.parse(w.auth.substring(7));var u=identities.load(token.userId(),token.version());var result=(Map<String,Object>)service.events(u,w.id,w.after);
   if(((Number)result.get("cursor")).longValue()!=w.after||System.currentTimeMillis()>=w.until)w.result.setResult(ApiResponse.ok(result));
  }catch(ApiException e){w.result.setErrorResult(ResponseEntity.status(e.status).body(ApiResponse.error(e.status,e.errorCode,e.getMessage())));}
  catch(io.jsonwebtoken.JwtException|IllegalArgumentException e){w.result.setErrorResult(ResponseEntity.status(401).body(ApiResponse.error(401,"SESSION_EXPIRED","登录已失效")));}
  catch(Exception e){w.result.setErrorResult(ResponseEntity.status(503).body(ApiResponse.error(503,"SUPPORT_UNAVAILABLE","客服连接暂不可用，请重试")));}
 }
 @PreDestroy public void stop(){timer.shutdownNow();workers.shutdownNow();}
}
