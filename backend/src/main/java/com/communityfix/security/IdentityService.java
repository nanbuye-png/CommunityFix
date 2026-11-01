package com.communityfix.security;

import com.communityfix.common.*;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IdentityService {
    private final Db db;
    public IdentityService(Db db) { this.db=db; }
    public CurrentUser load(long id,int tokenVersion) {
        var row=db.row("SELECT id,nickname,status,token_version FROM sys_user WHERE id=?",id);
        if(row==null || !"ENABLED".equals(row.get("status")) || Db.integer(row,"tokenVersion")!=tokenVersion) throw new ApiException(401,"SESSION_EXPIRED","登录已失效，请重新登录");
        Set<String> roles=db.rows("SELECT r.role_code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=?",id).stream().map(r->(String)r.get("roleCode")).collect(Collectors.toSet());
        Set<String> permissions=db.rows("SELECT DISTINCT p.permission_code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id=p.id JOIN sys_user_role ur ON ur.role_id=rp.role_id WHERE ur.user_id=?",id).stream().map(r->(String)r.get("permissionCode")).collect(Collectors.toSet());
        Set<Long> communities=db.rows("SELECT community_id FROM admin_community WHERE user_id=?",id).stream().map(r->Db.id(r,"communityId")).collect(Collectors.toSet());
        var worker=db.row("SELECT id FROM worker_profile WHERE user_id=?",id);
        return new CurrentUser(id,(String)row.get("nickname"),roles,permissions,communities,worker==null?null:Db.id(worker,"id"));
    }
}
