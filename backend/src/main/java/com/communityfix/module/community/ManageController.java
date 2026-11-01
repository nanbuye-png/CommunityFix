package com.communityfix.module.community;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/manage")
public class ManageController {
    private final Db db;private final CommunityService communities;
    public ManageController(Db db,CommunityService communities){this.db=db;this.communities=communities;}
    public record Community(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=255) String address,@Size(max=1000) String description){}
    public record Building(@NotNull @Positive Long communityId,@NotBlank @Size(max=20) String buildingNo){}
    public record Unit(@NotNull @Positive Long buildingId,@NotBlank @Size(max=20) String unitNo){}
    public record Room(@NotNull @Positive Long unitId,@NotBlank @Size(max=20) String roomNo,@NotNull @Min(-10) @Max(200) Integer floor){}
    public record Status(@NotNull @Pattern(regexp="ENABLED|DISABLED") String status){}
    private List<Map<String,Object>> deleteBlockers(long id){
        String[][] references={{"building","community_id","楼栋（含下属单元、房屋）"},{"admin_community","community_id","物业授权"},{"worker_service_community","community_id","维修服务关系"},{"worker_profile","owner_community_id","负责维修人员"},{"repair_order","community_id","工单"},{"notice","community_id","公告"},{"support_session","community_id","客服会话"},{"support_knowledge","community_id","客服知识"},{"sys_operation_log","community_id","历史操作记录"}};
        var blockers=new ArrayList<Map<String,Object>>();
        for(var ref:references){long count=db.count("SELECT COUNT(*) FROM "+ref[0]+" WHERE "+ref[1]+"=?",id);if(count>0)blockers.add(Db.fields("type",ref[0],"label",ref[2],"count",count));}
        long bindings=db.count("SELECT COUNT(*) FROM resident_room_binding br JOIN room r ON r.id=br.room_id JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id WHERE b.community_id=?",id);
        if(bindings>0)blockers.add(Db.fields("type","resident_room_binding","label","居民绑定申请（含历史记录）","count",bindings));
        return blockers;
    }
    private Map<String,Object> configuration(long id){return Db.fields(
        "community",db.required("SELECT * FROM community WHERE id=?",id),
        "buildings",db.rows("SELECT * FROM building WHERE community_id=? ORDER BY id",id),
        "units",db.rows("SELECT u.* FROM unit u JOIN building b ON b.id=u.building_id WHERE b.community_id=? ORDER BY u.id",id),
        "rooms",db.rows("SELECT r.* FROM room r JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id WHERE b.community_id=? ORDER BY r.id",id),
        "grants",db.rows("SELECT * FROM admin_community WHERE community_id=? ORDER BY user_id",id));}
    private String configurationToken(Map<String,Object> data){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private List<Map<String,Object>> businessBlockers(List<Map<String,Object>> blockers){return blockers.stream().filter(b->!Set.of("building","admin_community").contains(b.get("type"))).toList();}
    private void deletePermission(){CurrentUser.get().role("SUPER_ADMIN");CurrentUser.get().permission("system:community");}
    @GetMapping("/communities/{id}/deletion-check") ApiResponse<?> deletionCheck(@PathVariable long id){
        deletePermission();var row=db.required("SELECT id,name,address FROM community WHERE id=?",id);var blockers=deleteBlockers(id);
        var data=configuration(id);var counts=new LinkedHashMap<String,Object>();for(String key:List.of("buildings","units","rooms","grants"))counts.put(key,((List<?>)data.get(key)).size());
        return ApiResponse.ok(Db.fields("community",row,"canDelete",blockers.isEmpty(),"blockers",blockers,"canCleanup",businessBlockers(blockers).isEmpty(),"businessBlockers",businessBlockers(blockers),"configurationCounts",counts,"configurationToken",configurationToken(data)));
    }
    @DeleteMapping("/communities/{id}") @Transactional ApiResponse<?> deleteCommunity(@PathVariable long id,@RequestParam(defaultValue="false")boolean cleanupEmptyConfiguration,@RequestParam(required=false)String confirmationToken){
        deletePermission();db.required("SELECT id FROM community WHERE id=? FOR UPDATE",id);
        var blockers=deleteBlockers(id);
        if(cleanupEmptyConfiguration){if(!configurationToken(configuration(id)).equals(confirmationToken))throw new ApiException(409,"COMMUNITY_CHANGED","小区配置已变化，请重新检查并确认删除");blockers=businessBlockers(blockers);}
        if(!blockers.isEmpty())throw new ApiException(409,"COMMUNITY_IN_USE","无法删除：存在"+String.join("、",blockers.stream().map(b->b.get("label")+" "+b.get("count")+" 条").toList())+"。请先处理关联配置；已有业务记录的小区请使用停用。");
        try{
            if(cleanupEmptyConfiguration){
                db.execute("UPDATE sys_user SET token_version=token_version+1 WHERE id IN (SELECT user_id FROM admin_community WHERE community_id=?)",id);
                db.execute("DELETE FROM admin_community WHERE community_id=?",id);
                db.execute("DELETE r FROM room r JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id WHERE b.community_id=?",id);
                db.execute("DELETE u FROM unit u JOIN building b ON b.id=u.building_id WHERE b.community_id=?",id);
                db.execute("DELETE FROM building WHERE community_id=?",id);
            }
            db.execute("DELETE FROM community WHERE id=?",id);
        }catch(org.springframework.dao.DataIntegrityViolationException e){throw new ApiException(409,"COMMUNITY_IN_USE","关联数据已变化，请刷新后重新检查");}
        db.insert("sys_operation_log",Db.fields("operator_id",CurrentUser.get().id(),"action","COMMUNITY_DELETE","target_type","community","target_id",id,"result","SUCCESS","request_id",Optional.ofNullable(org.slf4j.MDC.get("requestId")).orElseGet(()->UUID.randomUUID().toString())));
        return ApiResponse.ok(null);
    }
    private void manage(long community){var user=CurrentUser.get();if(user.permissions().contains("system:community"))return;user.community(community);}
    @GetMapping("/communities") ApiResponse<?> list(){var user=CurrentUser.get();if(user.permissions().contains("system:community"))return ApiResponse.ok(db.rows("SELECT * FROM community ORDER BY id"));user.role("ADMIN");if(user.communities().isEmpty())return ApiResponse.ok(List.of());return ApiResponse.ok(db.rows("SELECT * FROM community WHERE id IN ("+String.join(",",Collections.nCopies(user.communities().size(),"?"))+") ORDER BY id",user.communities().toArray()));}
    @PostMapping("/communities") @Transactional ApiResponse<?> createCommunity(@Valid @RequestBody Community body){CurrentUser.get().permission("system:community");return ApiResponse.ok(Db.fields("id",db.insert("community",Db.fields("name",body.name(),"address",body.address(),"description",body.description()))));}
    @PostMapping("/buildings") @Transactional ApiResponse<?> createBuilding(@Valid @RequestBody Building body){manage(body.communityId());return ApiResponse.ok(Db.fields("id",db.insert("building",Db.fields("community_id",body.communityId(),"building_no",body.buildingNo()))));}
    @GetMapping("/buildings") ApiResponse<?> buildings(@RequestParam long communityId){manage(communityId);return ApiResponse.ok(db.rows("SELECT * FROM building WHERE community_id=? ORDER BY id",communityId));}
    @GetMapping("/units") ApiResponse<?> units(@RequestParam long buildingId){var building=db.required("SELECT community_id FROM building WHERE id=?",buildingId);manage(Db.id(building,"communityId"));return ApiResponse.ok(db.rows("SELECT * FROM unit WHERE building_id=? ORDER BY id",buildingId));}
    @PostMapping("/units") @Transactional ApiResponse<?> createUnit(@Valid @RequestBody Unit body){manage(Db.id(db.required("SELECT community_id FROM building WHERE id=?",body.buildingId()),"communityId"));return ApiResponse.ok(Db.fields("id",db.insert("unit",Db.fields("building_id",body.buildingId(),"unit_no",body.unitNo()))));}
    @PostMapping("/rooms") @Transactional ApiResponse<?> createRoom(@Valid @RequestBody Room body){var unit=db.required("SELECT b.community_id FROM unit u JOIN building b ON b.id=u.building_id WHERE u.id=?",body.unitId());manage(Db.id(unit,"communityId"));return ApiResponse.ok(Db.fields("id",db.insert("room",Db.fields("unit_id",body.unitId(),"room_no",body.roomNo(),"floor",body.floor()))));}
    @GetMapping("/rooms") ApiResponse<?> rooms(@RequestParam long unitId){var unit=db.required("SELECT b.community_id FROM unit u JOIN building b ON b.id=u.building_id WHERE u.id=?",unitId);manage(Db.id(unit,"communityId"));return ApiResponse.ok(db.rows("SELECT * FROM room WHERE unit_id=? ORDER BY id",unitId));}
    @PatchMapping("/{resource:communities|buildings|units|rooms}/{id}") @Transactional ApiResponse<?> status(@PathVariable String resource,@PathVariable long id,@Valid @RequestBody Status body){String table=switch(resource){case "communities"->"community";case "buildings"->"building";case "units"->"unit";case "rooms"->"room";default->throw ApiException.bad("未知资源");};long community=switch(resource){case "communities"->id;case "buildings"->Db.id(db.required("SELECT community_id FROM building WHERE id=?",id),"communityId");case "units"->Db.id(db.required("SELECT b.community_id FROM unit u JOIN building b ON b.id=u.building_id WHERE u.id=?",id),"communityId");default->Db.id(communities.room(id),"communityId");};manage(community);if(db.execute("UPDATE "+table+" SET status=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",body.status(),id)!=1)throw ApiException.missing();return ApiResponse.ok(null);}
}
