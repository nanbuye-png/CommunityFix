package com.communityfix.module.community;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/notices")
public class NoticeController {
    private final Db db;
    public NoticeController(Db db){this.db=db;}
    public record Notice(@NotNull @Positive Long communityId,@NotBlank @Size(max=100) String title,@NotBlank @Size(max=10000) String content){}
    @GetMapping ApiResponse<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){var user=CurrentUser.get();List<Long> scope;if(user.hasRole("ADMIN"))scope=new ArrayList<>(user.communities());else scope=db.rows("SELECT DISTINCT b.community_id FROM resident_room_binding br JOIN room r ON r.id=br.room_id JOIN unit u ON u.id=r.unit_id JOIN building b ON b.id=u.building_id WHERE br.user_id=? AND br.status='APPROVED'",user.id()).stream().map(r->Db.id(r,"communityId")).toList();String condition=scope.isEmpty()?"1=0":"community_id IN ("+String.join(",",Collections.nCopies(scope.size(),"?"))+")";if(!user.hasRole("ADMIN"))condition+=" AND status='PUBLISHED'";List<Object> args=new ArrayList<>(scope);long total=db.count("SELECT COUNT(*) FROM notice WHERE "+condition,args.toArray());args.add(Pages.size(size));args.add(Pages.offset(page,size));return ApiResponse.ok(Pages.result(db.rows("SELECT * FROM notice WHERE "+condition+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()),total,page,size));}
    @PostMapping @Transactional ApiResponse<?> create(@Valid @RequestBody Notice body){CurrentUser.get().community(body.communityId());long id=db.insert("notice",Db.fields("community_id",body.communityId(),"publisher_id",CurrentUser.get().id(),"title",body.title(),"content",body.content()));return ApiResponse.ok(db.required("SELECT * FROM notice WHERE id=?",id));}
    @PostMapping("/{id}/{action:publish|offline}") @Transactional ApiResponse<?> action(@PathVariable long id,@PathVariable String action){var notice=db.required("SELECT * FROM notice WHERE id=? FOR UPDATE",id);CurrentUser.get().community(Db.id(notice,"communityId"));String status=(String)notice.get("status");if(action.equals("publish")&&!Set.of("DRAFT","OFFLINE").contains(status)||action.equals("offline")&&!status.equals("PUBLISHED"))throw ApiException.conflict();db.execute("UPDATE notice SET status=?,published_at=CASE WHEN ?='PUBLISHED' THEN UTC_TIMESTAMP(3) ELSE published_at END,updated_at=UTC_TIMESTAMP(3) WHERE id=?",action.equals("publish")?"PUBLISHED":"OFFLINE",action.equals("publish")?"PUBLISHED":"OFFLINE",id);return ApiResponse.ok(db.required("SELECT * FROM notice WHERE id=?",id));}
}
