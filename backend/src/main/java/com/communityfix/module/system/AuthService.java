package com.communityfix.module.system;

import com.communityfix.common.*;
import com.communityfix.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {
    private final Db db; private final PasswordEncoder passwords; private final TokenService tokens; private final IdentityService identities; private final ObjectMapper json;
    private final String appId,appSecret;
    public AuthService(Db db,PasswordEncoder passwords,TokenService tokens,IdentityService identities,ObjectMapper json,@Value("${communityfix.wechat.app-id}") String appId,@Value("${communityfix.wechat.app-secret}") String appSecret) {
        this.db=db;this.passwords=passwords;this.tokens=tokens;this.identities=identities;this.json=json;this.appId=appId;this.appSecret=appSecret;
    }
    public Map<String,Object> login(String username,String password) {
        var row=db.row("SELECT id,password,status,token_version FROM sys_user WHERE username=?",username);
        if(row==null || row.get("password")==null || !passwords.matches(password,(String)row.get("password")) || !"ENABLED".equals(row.get("status"))) throw new ApiException(401,"LOGIN_FAILED","账号或密码错误");
        return session(Db.id(row,"id"),Db.integer(row,"tokenVersion"));
    }
    public Map<String,Object> session(long id,int version) {
        var user=identities.load(id,version);
        String workStatus=user.workerId()==null?null:(String)db.required("SELECT work_status FROM worker_profile WHERE id=?",user.workerId()).get("workStatus");
        return Db.fields("token",tokens.issue(id,version),"expiresAt",tokens.expiresAt(),"user",Db.fields("id",id,"nickname",user.nickname(),"workerId",user.workerId(),"workStatus",workStatus),"roles",user.roles(),"permissions",user.permissions(),"communityIds",user.communities());
    }
    public List<Map<String,Object>> registrationCommunities() { return db.rows("SELECT id,name FROM community WHERE status='ENABLED' ORDER BY id"); }
    @Transactional public Map<String,Object> register(String username,String password,String nickname) {
        return register(username,password,nickname,"RESIDENT",null);
    }
    @Transactional public Map<String,Object> register(String username,String password,String nickname,String role,Long communityId) {
        if(password.codePointCount(0,password.length())<12||password.getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.bad("密码至少12个字符，且不能超过72个UTF-8字节");
        String selected=role==null?"RESIDENT":role;
        if(!selected.equals("RESIDENT")&&!selected.equals("WORKER"))throw ApiException.bad("只能注册居民或维修人员");
        if(selected.equals("WORKER")&&(communityId==null||db.count("SELECT COUNT(*) FROM community WHERE id=? AND status='ENABLED'",communityId)!=1))throw ApiException.bad("请选择有效的申请小区");
        long id=db.insert("sys_user",Db.fields("username",username,"password",passwords.encode(password),"nickname",nickname));
        db.execute("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code=?",id,selected);
        if(selected.equals("WORKER")) {
            long worker=db.insert("worker_profile",Db.fields("user_id",id,"real_name",nickname.trim(),"owner_community_id",communityId,"work_status","DISABLED","max_active_orders",5));
            db.execute("INSERT INTO worker_service_community(worker_id,community_id) VALUES (?,?)",worker,communityId);
        }
        return session(id,0);
    }
    @Transactional public Map<String,Object> wechat(String code) {
        if(appId.isBlank() || appSecret.isBlank()) throw new ApiException(503,"WECHAT_NOT_CONFIGURED","微信登录尚未配置，请联系管理员");
        String openid;
        try {
            String uri="https://api.weixin.qq.com/sns/jscode2session?appid="+encode(appId)+"&secret="+encode(appSecret)+"&js_code="+encode(code)+"&grant_type=authorization_code";
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var response=client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(8)).GET().build(),HttpResponse.BodyHandlers.ofString());
            var data=json.readTree(response.body());
            if(response.statusCode()!=200 || !data.hasNonNull("openid")) throw new ApiException(401,"WECHAT_LOGIN_FAILED","微信登录失败，请重新授权");
            openid=data.get("openid").asText();
        } catch(ApiException ex) { throw ex; } catch(Exception ex) { throw new ApiException(503,"WECHAT_UNAVAILABLE","微信登录暂时不可用"); }
        var row=db.row("SELECT id,token_version FROM sys_user WHERE wechat_openid=?",openid);
        if(row!=null) return session(Db.id(row,"id"),Db.integer(row,"tokenVersion"));
        long id=db.insert("sys_user",Db.fields("wechat_openid",openid,"nickname","社区居民"));
        db.execute("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,1)",id);return session(id,0);
    }
    private String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    @Transactional public void logout() { db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id=?",CurrentUser.get().id()); }
}
