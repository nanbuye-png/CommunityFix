package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.security.TokenService;
import com.communityfix.module.notification.NotificationSocket;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="CF_INTEGRATION",matches="true")
class NotificationSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired Db db;@Autowired TokenService tokens;@Autowired NotificationSocket sockets;
    private final ObjectMapper json=new ObjectMapper();
    private final List<Long> users=new ArrayList<>();private final List<Client> clients=new ArrayList<>();
    private long user(String role){
        long id=db.insert("sys_user",Db.fields("nickname","socket-test"));users.add(id);
        db.execute("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?",id,role);return id;
    }
    private long notification(long user){return db.insert("notification",Db.fields("user_id",user,"event_key",UUID.randomUUID().toString(),"title","private title","content","private body"));}
    class Client implements WebSocket.Listener {
        WebSocket socket;final BlockingQueue<String> messages=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();final StringBuilder partial=new StringBuilder();
        public void onOpen(WebSocket socket){socket.request(1);}
        public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){partial.append(text);if(last){messages.add(partial.toString());partial.setLength(0);}socket.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(code);return null;}
        void auth(long id){socket.sendText("{\"type\":\"auth\",\"token\":\""+tokens.issue(id,0)+"\",\"userId\":\"999\"}",true).join();}
        JsonNode snapshot(long expected) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<deadline){String text=messages.poll(1,TimeUnit.SECONDS);if(text==null)continue;var n=json.readTree(text);if(n.path("latestId").asLong()==expected)return n;}
            throw new AssertionError("No expected notification snapshot");
        }
    }
    private Client connect() throws Exception {
        var c=new Client();c.socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Origin","http://localhost:5173")
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/notifications/live"),c).get(5,TimeUnit.SECONDS);clients.add(c);return c;
    }
    @AfterEach void cleanup(){for(var c:clients)c.socket.abort();for(long id:users){db.execute("DELETE FROM notification WHERE user_id=?",id);db.execute("DELETE FROM sys_user_role WHERE user_id=?",id);db.execute("DELETE FROM sys_user WHERE id=?",id);}}
    @Test void fourRolesReceiveOnlyTheirOwnInbox() throws Exception {
        for(String role:List.of("SUPER_ADMIN","ADMIN","RESIDENT","WORKER")){
            long id=user(role);long note=notification(id);var client=connect();client.auth(id);
            var snapshot=client.snapshot(note);assertEquals(1,snapshot.path("unread").asInt());
            assertFalse(snapshot.toString().contains("private"));assertEquals(3,snapshot.size());
        }
        long next=notification(users.get(0));sockets.refresh();assertEquals(2,clients.get(0).snapshot(next).path("unread").asInt());
        for(int i=1;i<4;i++){clients.get(i).messages.clear();}sockets.refresh();
        for(int i=1;i<4;i++)assertNotEquals(next,json.readTree(clients.get(i).messages.poll(3,TimeUnit.SECONDS)).path("latestId").asLong());
    }
    @Test void reconnectIncludesOfflineMessagesAndReadCount() throws Exception {
        long id=user("RESIDENT");var first=connect();first.auth(id);first.snapshot(0);first.socket.abort();
        long note=notification(id);var next=connect();next.auth(id);assertEquals(1,next.snapshot(note).path("unread").asInt());
        db.execute("UPDATE notification SET read_status=1 WHERE id=?",note);next.messages.clear();sockets.refresh();
        assertEquals(0,next.snapshot(note).path("unread").asInt());
    }
    @Test void revokedLoginClosesExistingSocket() throws Exception {
        long id=user("WORKER");var client=connect();client.auth(id);client.snapshot(0);
        db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id=?",id);sockets.refresh();
        assertEquals(1008,client.closed.get(5,TimeUnit.SECONDS));
    }
    @Test void invalidAuthenticationNeverReceivesInbox() throws Exception {
        var client=connect();assertNull(client.messages.poll(200,TimeUnit.MILLISECONDS));
        client.socket.sendText("{\"type\":\"auth\",\"token\":\"invalid\"}",true).join();
        assertEquals(1008,client.closed.get(5,TimeUnit.SECONDS));assertTrue(client.messages.isEmpty());
    }
    @Test void crossOriginHandshakeIsRejected(){
        assertThrows(ExecutionException.class,()->HttpClient.newHttpClient().newWebSocketBuilder().header("Origin","https://evil.invalid")
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/api/notifications/live"),new Client()).get(5,TimeUnit.SECONDS));
    }
}
