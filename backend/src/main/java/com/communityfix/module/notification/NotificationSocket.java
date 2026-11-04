package com.communityfix.module.notification;

import com.communityfix.common.Db;
import com.communityfix.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import java.util.concurrent.ConcurrentHashMap;

/** Pushes only inbox metadata; authorized HTTP APIs remain the source of message content. */
@Component
public class NotificationSocket extends TextWebSocketHandler {
    private final Db db;private final TokenService tokens;private final IdentityService identities;private final ObjectMapper json;
    private final ConcurrentHashMap<String,Connection> connections=new ConcurrentHashMap<>();
    private static class Connection {
        final WebSocketSession session;final long opened=System.nanoTime();
        volatile String token;volatile long user;
        Connection(WebSocketSession session){this.session=new ConcurrentWebSocketSessionDecorator(session,2000,16384);}
    }
    public NotificationSocket(Db db,TokenService tokens,IdentityService identities,ObjectMapper json){this.db=db;this.tokens=tokens;this.identities=identities;this.json=json;}
    @Override public synchronized void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if(connections.size()>=1000){session.close(new CloseStatus(1013,"CAPACITY"));return;}
        session.setTextMessageSizeLimit(4096);session.setBinaryMessageSizeLimit(1);
        connections.put(session.getId(),new Connection(session));
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){
        Connection c=connections.get(session.getId());if(c==null)return;
        synchronized(c){
            if(c.token!=null){close(c,1008,"READ_ONLY");return;}
            try {
                var body=json.readTree(message.getPayload());
                if(!"auth".equals(body.path("type").asText()))throw new IllegalArgumentException();
                String token=body.path("token").asText();var identity=tokens.parse(token);
                var user=identities.load(identity.userId(),identity.version());
                synchronized(this){
                    if(connections.values().stream().filter(x->x.token!=null&&x.user==user.id()).count()>=5){close(c,1013,"USER_CAPACITY");return;}
                    c.user=user.id();c.token=token;
                }
                snapshot(c);
            }catch(Exception e){close(c,1008,"AUTH_REQUIRED");}
        }
    }
    @Scheduled(fixedDelayString="${communityfix.notifications.socket-scan-ms:3000}")
    public void refresh(){
        for(Connection c:connections.values())synchronized(c){
            if(c.token==null){if(System.nanoTime()-c.opened>5_000_000_000L)close(c,1008,"AUTH_TIMEOUT");continue;}
            try {
                var identity=tokens.parse(c.token);identities.load(identity.userId(),identity.version());
                snapshot(c);
            }catch(io.jsonwebtoken.JwtException|com.communityfix.common.ApiException|IllegalArgumentException e){close(c,1008,"SESSION_EXPIRED");}
            catch(Exception e){close(c,1011,"TEMPORARILY_UNAVAILABLE");}
        }
    }
    private void snapshot(Connection c) throws Exception {
        var row=db.required("SELECT COALESCE(MAX(id),0) AS latest_id,COALESCE(SUM(CASE WHEN read_status=0 THEN 1 ELSE 0 END),0) AS unread FROM notification WHERE user_id=?",c.user);
        c.session.sendMessage(new TextMessage(json.writeValueAsString(Db.fields("type","notifications","latestId",row.get("latestId").toString(),"unread",((Number)row.get("unread")).longValue()))));
    }
    private void close(Connection c,int code,String reason){connections.remove(c.session.getId());try{c.session.close(new CloseStatus(code,reason));}catch(Exception ignored){}}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){connections.remove(session.getId());}
    @Override public void handleTransportError(WebSocketSession session,Throwable exception){Connection c=connections.get(session.getId());if(c!=null)close(c,1011,"TRANSPORT_ERROR");}
}
