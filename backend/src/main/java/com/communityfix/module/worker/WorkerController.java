package com.communityfix.module.worker;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController @RequestMapping("/api/workers")
public class WorkerController {
    private final Db db;
    private final PasswordEncoder passwords;
    public WorkerController(Db db,PasswordEncoder passwords){this.db=db;this.passwords=passwords;}
    public record Worker(@NotNull @Positive Long userId,@NotBlank @Size(max=50) String realName,@NotEmpty List<@NotNull @Positive Long> communityIds,@NotEmpty List<@NotNull @Positive Long> categoryIds,@NotNull @Min(1) @Max(100) Integer maxActiveOrders,@Positive Long ownerCommunityId){}
    public record Account(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{3,50}") String username,@NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(max=50) String realName,@Size(max=20) String phone,@NotNull @Positive Long ownerCommunityId,@NotEmpty List<@NotNull @Positive Long> communityIds,@NotEmpty List<@NotNull @Positive Long> categoryIds,@NotNull @Min(1) @Max(100) Integer maxActiveOrders){}
    public record Update(@Pattern(regexp="AVAILABLE|ON_LEAVE|DISABLED") String workStatus,@Min(1) @Max(100) Integer maxActiveOrders,@Size(min=1) List<@NotNull @Positive Long> categoryIds){}
    public record AccountUpdate(@Size(min=1,max=50) String realName,@Size(max=20) String phone,@Size(min=12,max=72) String password,@Pattern(regexp="ENABLED|DISABLED") String status){}
    public record Services(@NotNull List<@NotNull @Positive Long> communityIds){}
    public record Owner(@NotNull @Positive Long communityId){}
    private boolean root(){return CurrentUser.get().hasRole("SUPER_ADMIN");}
    private void manager(){if(!root())CurrentUser.get().role("ADMIN");}
    private Set<Long> services(long id){Set<Long> result=new HashSet<>();for(var row:db.rows("SELECT community_id FROM worker_service_community WHERE worker_id=?",id))result.add(Db.id(row,"communityId"));return result;}
    private boolean owns(Map<String,Object> w){return w.get("ownerCommunityId")!=null&&CurrentUser.get().communities().contains(Db.id(w,"ownerCommunityId"));}
    private boolean accountEditable(Map<String,Object> w){
        // Properties cannot reset credentials of privileged multi-role accounts.
        return root()||(owns(w)&&db.count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code<>'WORKER'",w.get("userId"))==0);
    }
    private Map<String,Object> locked(long id){manager();var row=db.required("SELECT * FROM worker_profile WHERE id=? FOR UPDATE",id);if(!root()&&!owns(row)&&Collections.disjoint(services(id),CurrentUser.get().communities()))throw ApiException.missing();return row;}
    private Map<String,Object> owned(long id){var row=locked(id);if(!root()&&!owns(row))throw ApiException.forbidden();return row;}
    private void enabledCommunities(Collection<Long> ids){for(long id:ids)if(db.count("SELECT COUNT(*) FROM community WHERE id=? AND status='ENABLED'",id)!=1)throw ApiException.bad("请选择有效的服务小区");}
    private void categories(Collection<Long> ids){for(long id:ids)if(db.count("SELECT COUNT(*) FROM repair_category WHERE id=? AND status='ENABLED'",id)!=1)throw ApiException.bad("请选择有效的维修技能");}
    private void validateNew(Long owner,List<Long> communities,List<Long> categories){manager();if(owner==null||!communities.contains(owner))throw ApiException.bad("负责小区必须包含在服务范围内");if(!root()&&!CurrentUser.get().communities().containsAll(communities))throw ApiException.forbidden();enabledCommunities(communities);categories(categories);}
    private void password(String value){if(value.isBlank()||value.codePointCount(0,value.length())<12||value.getBytes(StandardCharsets.UTF_8).length>72)throw ApiException.bad("密码至少12个字符，且不能超过72个UTF-8字节");}
    private long profile(long uid,String name,Long owner,List<Long> communities,List<Long> categories,int capacity){long id=db.insert("worker_profile",Db.fields("user_id",uid,"real_name",name.trim(),"owner_community_id",owner,"max_active_orders",capacity));for(long c:new HashSet<>(communities))db.execute("INSERT INTO worker_service_community(worker_id,community_id) VALUES (?,?)",id,c);skills(id,categories);return id;}
    private void audit(String action,long id){db.insert("sys_operation_log",Db.fields("operator_id",CurrentUser.get().id(),"action",action,"target_type","worker","target_id",id,"result","SUCCESS","request_id",MDC.get("requestId")));}
    @GetMapping("/me") ApiResponse<?> me(){
        var user=CurrentUser.get();user.role("WORKER");
        return ApiResponse.ok(db.required("SELECT id,real_name,work_status FROM worker_profile WHERE user_id=?",user.id()));
    }

    @GetMapping ApiResponse<?> list(@RequestParam(required=false) Long communityId){
        manager();var user=CurrentUser.get();List<Object> args=new ArrayList<>();String scope="1=1";
        if(!root()){
            if(communityId!=null)user.community(communityId);
            var ids=user.communities();String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
            scope=ids.isEmpty()?"1=0":"(w.owner_community_id IN ("+marks+") OR w.id IN (SELECT worker_id FROM worker_service_community WHERE community_id IN ("+marks+")))";
            args.addAll(ids);args.addAll(ids);
        }
        if(communityId!=null){scope+=" AND w.id IN (SELECT worker_id FROM worker_service_community WHERE community_id=?)";args.add(communityId);}
        var rows=db.rows("SELECT w.id,w.user_id,w.real_name,w.work_status,w.max_active_orders,w.owner_community_id,c.name AS owner_community_name,u.username,u.phone,u.status AS account_status,(SELECT COUNT(*) FROM repair_order o WHERE o.worker_id=w.id AND o.status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS')) AS active_orders FROM worker_profile w JOIN sys_user u ON u.id=w.user_id LEFT JOIN community c ON c.id=w.owner_community_id WHERE "+scope+" ORDER BY w.id",args.toArray());
        for(var row:rows){long id=Db.id(row,"id");row.put("categoryIds",db.rows("SELECT category_id FROM worker_skill WHERE worker_id=?",id).stream().map(r->r.get("categoryId")).toList());var visible=services(id);if(!root())visible.retainAll(user.communities());row.put("communityIds",visible);row.put("canManageAccount",accountEditable(row));row.put("canManageProfile",root()||owns(row));row.put("canManageServices",true);row.put("canTransferOwner",root());if(!root()&&!owns(row))row.remove("ownerCommunityName");}
        return ApiResponse.ok(rows);
    }
    @PostMapping("/accounts") @Transactional ApiResponse<?> createAccount(@Valid @RequestBody Account body){
        validateNew(body.ownerCommunityId(),body.communityIds(),body.categoryIds());password(body.password());
        long uid=db.insert("sys_user",Db.fields("username",body.username(),"password",passwords.encode(body.password()),"nickname",body.realName().trim(),"phone",body.phone()));
        db.execute("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE role_code='WORKER'",uid);
        long id=profile(uid,body.realName(),body.ownerCommunityId(),body.communityIds(),body.categoryIds(),body.maxActiveOrders());audit("WORKER_ACCOUNT_CREATE",id);return ApiResponse.ok(Db.fields("id",id,"userId",uid));
    }
    @GetMapping("/account-candidates") ApiResponse<?> candidates(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        CurrentUser.get().role("SUPER_ADMIN");String from=" FROM sys_user u WHERE EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.role_code='WORKER') AND NOT EXISTS (SELECT 1 FROM worker_profile w WHERE w.user_id=u.id) AND (u.username LIKE ? OR u.nickname LIKE ?)";String term="%"+q+"%";
        return ApiResponse.ok(Pages.result(db.rows("SELECT u.id,u.username,u.nickname"+from+" ORDER BY u.id DESC LIMIT ? OFFSET ?",term,term,Pages.size(size),Pages.offset(page,size)),db.count("SELECT COUNT(*)"+from,term,term),page,size));
    }
    @PostMapping @Transactional ApiResponse<?> create(@Valid @RequestBody Worker body){
        CurrentUser.get().role("SUPER_ADMIN");
        Long owner=body.ownerCommunityId()!=null?body.ownerCommunityId():new HashSet<>(body.communityIds()).size()==1?body.communityIds().get(0):null;
        validateNew(owner,body.communityIds(),body.categoryIds());db.required("SELECT id FROM sys_user WHERE id=? FOR UPDATE",body.userId());
        if(db.count("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_code='WORKER'",body.userId())==0)throw ApiException.bad("请选择具有维修人员角色的账号");
        long id=profile(body.userId(),body.realName(),owner,body.communityIds(),body.categoryIds(),body.maxActiveOrders());audit("WORKER_LINK",id);return ApiResponse.ok(Db.fields("id",id));
    }
    @PatchMapping("/{id}") @Transactional ApiResponse<?> update(@PathVariable long id,@Valid @RequestBody Update body){
        owned(id);if(body.workStatus()!=null)db.execute("UPDATE worker_profile SET work_status=? WHERE id=?",body.workStatus(),id);if(body.maxActiveOrders()!=null)db.execute("UPDATE worker_profile SET max_active_orders=? WHERE id=?",body.maxActiveOrders(),id);if(body.categoryIds()!=null){categories(body.categoryIds());db.execute("DELETE FROM worker_skill WHERE worker_id=?",id);skills(id,body.categoryIds());}audit("WORKER_PROFILE_UPDATE",id);return ApiResponse.ok(null);
    }
    @PatchMapping("/{id}/account") @Transactional ApiResponse<?> updateAccount(@PathVariable long id,@Valid @RequestBody AccountUpdate body){
        var w=owned(id);long uid=Db.id(w,"userId");db.required("SELECT id FROM sys_user WHERE id=? FOR UPDATE",uid);if(!accountEditable(w))throw ApiException.forbidden();
        if(body.realName()!=null){if(body.realName().isBlank())throw ApiException.bad("姓名不能为空");db.execute("UPDATE sys_user SET nickname=? WHERE id=?",body.realName().trim(),uid);db.execute("UPDATE worker_profile SET real_name=? WHERE id=?",body.realName().trim(),id);}
        if(body.phone()!=null)db.execute("UPDATE sys_user SET phone=? WHERE id=?",body.phone().trim(),uid);
        if(body.password()!=null){password(body.password());db.execute("UPDATE sys_user SET password=? WHERE id=?",passwords.encode(body.password()),uid);}
        if(body.status()!=null){if(uid==CurrentUser.get().id()&&body.status().equals("DISABLED"))throw ApiException.bad("不能停用当前账号");db.execute("UPDATE sys_user SET status=? WHERE id=?",body.status(),uid);}
        if(body.password()!=null||body.status()!=null)db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id=?",uid);
        audit("WORKER_ACCOUNT_UPDATE",id);return ApiResponse.ok(null);
    }
    @PatchMapping("/{id}/services") @Transactional ApiResponse<?> updateServices(@PathVariable long id,@Valid @RequestBody Services body){
        var w=locked(id);Set<Long> old=services(id),next=new HashSet<>(body.communityIds());
        if(!root()){Set<Long> scope=CurrentUser.get().communities();if(!scope.containsAll(next))throw ApiException.forbidden();if(!owns(w)&&!old.containsAll(next))throw ApiException.forbidden();for(long c:old)if(!scope.contains(c))next.add(c);}
        Set<Long> added=new HashSet<>(next);added.removeAll(old);enabledCommunities(added);
        if(w.get("ownerCommunityId")!=null&&!next.contains(Db.id(w,"ownerCommunityId")))throw ApiException.bad("不能移除负责小区，请先由系统管理员变更归属");
        for(long c:old)if(!next.contains(c)){if(db.count("SELECT COUNT(*) FROM repair_order WHERE worker_id=? AND community_id=? AND status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS','PENDING_CONFIRM')",id,c)>0)throw ApiException.bad("该小区还有未完成任务，请先完成或改派");db.execute("DELETE FROM worker_service_community WHERE worker_id=? AND community_id=?",id,c);}
        for(long c:added)db.execute("INSERT INTO worker_service_community(worker_id,community_id) VALUES (?,?)",id,c);
        audit("WORKER_SERVICES_UPDATE",id);return ApiResponse.ok(null);
    }
    @PatchMapping("/{id}/owner") @Transactional ApiResponse<?> owner(@PathVariable long id,@Valid @RequestBody Owner body){
        CurrentUser.get().role("SUPER_ADMIN");locked(id);enabledCommunities(List.of(body.communityId()));if(!services(id).contains(body.communityId()))throw ApiException.bad("请先将负责小区加入服务范围");db.execute("UPDATE worker_profile SET owner_community_id=? WHERE id=?",body.communityId(),id);audit("WORKER_OWNER_UPDATE",id);return ApiResponse.ok(null);
    }
    private void skills(long id,List<Long> categories){for(long category:new HashSet<>(categories))db.insert("worker_skill",Db.fields("worker_id",id,"category_id",category,"proficiency",1));}
    @GetMapping("/history") ApiResponse<?> history(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){var user=CurrentUser.get();user.role("WORKER");if(user.workerId()==null)return ApiResponse.ok(Pages.result(List.of(),0,page,size));String from=" FROM repair_assignment a JOIN repair_order o ON o.id=a.order_id WHERE a.worker_id=?";return ApiResponse.ok(Pages.result(db.rows("SELECT a.id,a.order_id,a.round_no,a.status,a.assigned_at,a.accepted_at,a.ended_at,o.order_no,o.title"+from+" ORDER BY a.id DESC LIMIT ? OFFSET ?",user.workerId(),Pages.size(size),Pages.offset(page,size)),db.count("SELECT COUNT(*)"+from,user.workerId()),page,size));}
}
