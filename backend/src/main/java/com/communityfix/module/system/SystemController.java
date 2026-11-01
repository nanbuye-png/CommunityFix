package com.communityfix.module.system;

import com.communityfix.common.*;
import com.communityfix.module.community.CommunityService;
import com.communityfix.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/system")
public class SystemController {
    private final Db db;private final PasswordEncoder passwords;private final CommunityService communities;
    public SystemController(Db db,PasswordEncoder passwords,CommunityService communities){this.db=db;this.passwords=passwords;this.communities=communities;}
    public record UserCreate(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{3,50}") String username,@NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(max=50) String nickname,@NotEmpty List<@NotBlank String> roles){}
    public record UserUpdate(@Pattern(regexp="ENABLED|DISABLED") String status,@Size(min=12,max=72) String password,@Size(max=50) String nickname,@Size(max=20) String phone,List<String> roles){}
    public record Move(@NotNull @Positive Long oldBindingId,@NotNull @Positive Long newRoomId,@NotBlank @Size(max=497) String reason){}
    public record Role(@NotBlank @Pattern(regexp="[A-Z_]{3,50}") String roleCode,@NotBlank @Size(max=50) String roleName,@NotNull List<String> permissions){}
    public record Grant(@NotNull @Positive Long userId,@NotNull @Positive Long communityId){}
    @GetMapping("/users") ApiResponse<?> users(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){CurrentUser.get().permission("system:user");var rows=db.rows("SELECT id,username,nickname,phone,status,created_at FROM sys_user ORDER BY id DESC LIMIT ? OFFSET ?",Pages.size(size),Pages.offset(page,size));for(var row:rows){row.put("roles",db.rows("SELECT r.role_code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=?",row.get("id")).stream().map(r->r.get("roleCode")).toList());row.put("addresses",addresses(Db.id(row,"id")));}return ApiResponse.ok(Pages.result(rows,db.count("SELECT COUNT(*) FROM sys_user"),page,size));}
    @GetMapping("/community-users") ApiResponse<?> communityUsers(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        var operator=CurrentUser.get();operator.role("ADMIN");
        String from=" FROM sys_user u WHERE EXISTS (SELECT 1 FROM resident_room_binding br JOIN room r ON r.id=br.room_id JOIN unit un ON un.id=r.unit_id JOIN building b ON b.id=un.building_id JOIN admin_community ac ON ac.community_id=b.community_id WHERE br.user_id=u.id AND ac.user_id=? AND br.status IN ('PENDING','APPROVED')) AND NOT EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role role ON role.id=ur.role_id WHERE ur.user_id=u.id AND role.role_code<>'RESIDENT')";
        var rows=db.rows("SELECT u.id,u.username,u.nickname,u.phone,u.status,u.created_at"+from+" ORDER BY u.id DESC LIMIT ? OFFSET ?",operator.id(),Pages.size(size),Pages.offset(page,size));
        for(var row:rows)row.put("addresses",db.rows("SELECT c.name AS community_name,b.building_no,r.room_no FROM resident_room_binding br JOIN room r ON r.id=br.room_id JOIN unit un ON un.id=r.unit_id JOIN building b ON b.id=un.building_id JOIN community c ON c.id=b.community_id JOIN admin_community ac ON ac.community_id=c.id WHERE br.user_id=? AND ac.user_id=? AND br.status='APPROVED'",row.get("id"),operator.id()));
        return ApiResponse.ok(Pages.result(rows,db.count("SELECT COUNT(*)"+from,operator.id()),page,size));
    }
    @PatchMapping("/community-users/{id}") @Transactional ApiResponse<?> updateCommunityUser(@PathVariable long id,@Valid @RequestBody UserUpdate body){
        var operator=CurrentUser.get();operator.role("ADMIN");
        if(body.roles()!=null)throw ApiException.forbidden();
        db.required("SELECT id FROM sys_user WHERE id=? FOR UPDATE",id);
        if(db.count("SELECT COUNT(*) FROM resident_room_binding br JOIN room r ON r.id=br.room_id JOIN unit un ON un.id=r.unit_id JOIN building b ON b.id=un.building_id JOIN admin_community ac ON ac.community_id=b.community_id WHERE br.user_id=? AND ac.user_id=? AND br.status IN ('PENDING','APPROVED')",id,operator.id())==0
            ||db.count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role role ON role.id=ur.role_id WHERE ur.user_id=? AND role.role_code<>'RESIDENT'",id)>0)throw ApiException.missing();
        if(body.nickname()!=null){OrderName.validate(body.nickname());db.execute("UPDATE sys_user SET nickname=? WHERE id=?",body.nickname().trim(),id);}
        if(body.phone()!=null)db.execute("UPDATE sys_user SET phone=? WHERE id=?",body.phone().isBlank()?null:body.phone().trim(),id);
        if(body.password()!=null){if(body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw ApiException.bad("密码不能超过72个UTF-8字节");db.execute("UPDATE sys_user SET password=? WHERE id=?",passwords.encode(body.password()),id);}
        if(body.status()!=null)db.execute("UPDATE sys_user SET status=? WHERE id=?",body.status(),id);
        if(body.password()!=null||body.status()!=null)db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id=?",id);
        audit("COMMUNITY_USER_UPDATE","user",id);return ApiResponse.ok(null);
    }
    private List<Map<String,Object>> addresses(long userId){return db.rows("SELECT br.id AS binding_id,r.id AS room_id,c.name AS community_name,b.building_no,u.unit_no,r.room_no FROM resident_room_binding br JOIN room r ON r.id=br.room_id JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id JOIN community c ON c.id=b.community_id WHERE br.user_id=? AND br.status='APPROVED' ORDER BY br.id",userId);}
    @PostMapping("/users") @Transactional ApiResponse<?> createUser(@Valid @RequestBody UserCreate body){CurrentUser.get().permission("system:user");long id=db.insert("sys_user",Db.fields("username",body.username(),"nickname",body.nickname(),"password",passwords.encode(body.password())));roles(id,body.roles());audit("USER_CREATE","user",id);return ApiResponse.ok(Db.fields("id",id));}
    @PatchMapping("/users/{id}") @Transactional ApiResponse<?> updateUser(@PathVariable long id,@Valid @RequestBody UserUpdate body){CurrentUser.get().permission("system:user");db.required("SELECT id FROM sys_user WHERE id=? FOR UPDATE",id);if(id==CurrentUser.get().id()&&(body.roles()!=null||"DISABLED".equals(body.status())))throw ApiException.bad("不能停用或移除当前账号的角色");if(body.status()!=null)db.execute("UPDATE sys_user SET status=? WHERE id=?",body.status(),id);if(body.nickname()!=null){OrderName.validate(body.nickname());db.execute("UPDATE sys_user SET nickname=? WHERE id=?",body.nickname().trim(),id);}if(body.phone()!=null)db.execute("UPDATE sys_user SET phone=? WHERE id=?",body.phone().isBlank()?null:body.phone().trim(),id);if(body.password()!=null)db.execute("UPDATE sys_user SET password=? WHERE id=?",passwords.encode(body.password()),id);if(body.roles()!=null){if(body.roles().isEmpty())throw ApiException.bad("请至少选择一个角色");db.execute("DELETE FROM sys_user_role WHERE user_id=?",id);roles(id,body.roles());}if(body.status()!=null||body.password()!=null||body.roles()!=null)db.execute("UPDATE sys_user SET token_version=token_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);else db.execute("UPDATE sys_user SET updated_at=UTC_TIMESTAMP(3) WHERE id=?",id);audit("USER_UPDATE","user",id);return ApiResponse.ok(null);}
    @PostMapping("/users/{id}/move") @Transactional ApiResponse<?> move(@PathVariable long id,@Valid @RequestBody Move body){
        var operator=CurrentUser.get();operator.permission("system:user");
        db.required("SELECT id FROM sys_user WHERE id=? FOR UPDATE",id);
        if(db.count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='RESIDENT'",id)==0)throw ApiException.bad("只有居民账号可以变更房屋");
        var old=db.required("SELECT id,room_id,status FROM resident_room_binding WHERE id=? AND user_id=? FOR UPDATE",body.oldBindingId(),id);
        if(!"APPROVED".equals(old.get("status")))throw ApiException.conflict();
        if(Db.id(old,"roomId")==body.newRoomId())throw ApiException.bad("新房屋不能与原房屋相同");
        communities.enabled(communities.room(body.newRoomId()));
        var target=db.row("SELECT id,status FROM resident_room_binding WHERE user_id=? AND room_id=? FOR UPDATE",id,body.newRoomId());
        if(target!=null&&!Set.of("REJECTED","REVOKED").contains(target.get("status")))throw ApiException.conflict();
        String reason="搬家："+body.reason().trim();
        db.execute("UPDATE resident_room_binding SET status='REVOKED',reviewer_id=?,reviewed_at=UTC_TIMESTAMP(3),review_reason=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",operator.id(),reason,body.oldBindingId());
        db.insert("resident_room_binding_log",Db.fields("binding_id",body.oldBindingId(),"old_status","APPROVED","new_status","REVOKED","operator_id",operator.id(),"reason",reason));
        long nextId;String previous;
        if(target==null){nextId=db.insert("resident_room_binding",Db.fields("user_id",id,"room_id",body.newRoomId(),"status","APPROVED","reviewer_id",operator.id(),"reviewed_at",java.time.Instant.now(),"review_reason",reason));previous=null;}
        else{nextId=Db.id(target,"id");previous=(String)target.get("status");db.execute("UPDATE resident_room_binding SET status='APPROVED',reviewer_id=?,reviewed_at=UTC_TIMESTAMP(3),review_reason=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",operator.id(),reason,nextId);}
        db.insert("resident_room_binding_log",Db.fields("binding_id",nextId,"old_status",previous,"new_status","APPROVED","operator_id",operator.id(),"reason",reason));
        db.insert("notification",Db.fields("user_id",id,"event_key","move:"+body.oldBindingId()+":"+nextId+":"+UUID.randomUUID(),"title","房屋地址已更新","content","原房屋绑定已撤销，新房屋已生效。"));
        audit("USER_MOVE","user",id);
        return ApiResponse.ok(Db.fields("oldBindingId",body.oldBindingId(),"newBindingId",nextId,"addresses",addresses(id)));
    }
    private void roles(long user,List<String> roles){for(String code:new HashSet<>(roles)){var role=db.required("SELECT id FROM sys_role WHERE role_code=?",code);db.execute("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)",user,role.get("id"));}}
    @GetMapping("/roles") ApiResponse<?> roles(){CurrentUser.get().permission("system:role");var roles=db.rows("SELECT * FROM sys_role ORDER BY id");for(var role:roles)role.put("permissions",db.rows("SELECT p.permission_code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id=p.id WHERE rp.role_id=?",role.get("id")).stream().map(p->p.get("permissionCode")).toList());return ApiResponse.ok(roles);}
    @GetMapping("/permissions") ApiResponse<?> permissions(){CurrentUser.get().permission("system:role");return ApiResponse.ok(db.rows("SELECT * FROM sys_permission ORDER BY id"));}
    @PostMapping("/roles") @Transactional ApiResponse<?> createRole(@Valid @RequestBody Role body){CurrentUser.get().permission("system:role");long id=db.insert("sys_role",Db.fields("role_code",body.roleCode(),"role_name",body.roleName()));rolePermissions(id,body.permissions());audit("ROLE_CREATE","role",id);return ApiResponse.ok(Db.fields("id",id));}
    @PatchMapping("/roles/{id}") @Transactional ApiResponse<?> updateRole(@PathVariable long id,@Valid @RequestBody Role body){CurrentUser.get().permission("system:role");var original=db.required("SELECT * FROM sys_role WHERE id=? FOR UPDATE",id);if(!body.roleCode().equals(original.get("roleCode")))throw ApiException.bad("角色编码不可修改");if("SUPER_ADMIN".equals(original.get("roleCode")))throw ApiException.bad("内置系统管理角色权限不可修改");db.execute("UPDATE sys_role SET role_name=? WHERE id=?",body.roleName(),id);db.execute("DELETE FROM sys_role_permission WHERE role_id=?",id);rolePermissions(id,body.permissions());db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id=?)",id);audit("ROLE_UPDATE","role",id);return ApiResponse.ok(null);}
    private void rolePermissions(long role,List<String> permissions){for(String code:new HashSet<>(permissions)){var permission=db.required("SELECT id FROM sys_permission WHERE permission_code=?",code);db.execute("INSERT INTO sys_role_permission(role_id,permission_id) VALUES (?,?)",role,permission.get("id"));}}
    @GetMapping("/admin-communities") ApiResponse<?> grants(){CurrentUser.get().permission("system:community");return ApiResponse.ok(db.rows("SELECT a.user_id,a.community_id,u.nickname,c.name FROM admin_community a JOIN sys_user u ON u.id=a.user_id JOIN community c ON c.id=a.community_id ORDER BY a.user_id,a.community_id"));}
    @PostMapping("/admin-communities") @Transactional ApiResponse<?> grant(@Valid @RequestBody Grant body){CurrentUser.get().permission("system:community");if(db.count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='ADMIN'",body.userId())==0)throw ApiException.bad("只能给物业管理员授权小区");db.execute("INSERT INTO admin_community(user_id,community_id) VALUES (?,?) ON DUPLICATE KEY UPDATE user_id=user_id",body.userId(),body.communityId());db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id=?",body.userId());audit("COMMUNITY_GRANT","user",body.userId());return ApiResponse.ok(null);}
    @DeleteMapping("/admin-communities/{userId}/{communityId}") @Transactional ApiResponse<?> revoke(@PathVariable long userId,@PathVariable long communityId){CurrentUser.get().permission("system:community");db.execute("DELETE FROM admin_community WHERE user_id=? AND community_id=?",userId,communityId);db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id=?",userId);audit("COMMUNITY_REVOKE","user",userId);return ApiResponse.ok(null);}
    @GetMapping("/logs") ApiResponse<?> logs(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){CurrentUser.get().permission("system:user");return ApiResponse.ok(Pages.result(db.rows("SELECT * FROM sys_operation_log ORDER BY id DESC LIMIT ? OFFSET ?",Pages.size(size),Pages.offset(page,size)),db.count("SELECT COUNT(*) FROM sys_operation_log"),page,size));}
    private void audit(String action,String type,long id){db.insert("sys_operation_log",Db.fields("operator_id",CurrentUser.get().id(),"action",action,"target_type",type,"target_id",id,"result","SUCCESS","request_id",MDC.get("requestId")));}
    private static class OrderName {static void validate(String name){if(name.isBlank())throw ApiException.bad("昵称不能为空");}}
}
