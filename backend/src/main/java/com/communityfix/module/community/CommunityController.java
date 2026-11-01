package com.communityfix.module.community;

import com.communityfix.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class CommunityController {
    private final CommunityService service;private final Db db;
    public CommunityController(CommunityService service,Db db) {this.service=service;this.db=db;}
    public record Binding(@NotNull @Positive Long roomId) {}
    public record Review(@NotNull Boolean approved,@Size(max=500) String reason) {}
    public record Reason(@NotBlank @Size(max=500) String reason) {}
    @GetMapping("/communities") ApiResponse<?> communities(){return ApiResponse.ok(service.communities());}
    @GetMapping("/buildings") ApiResponse<?> buildings(@RequestParam long communityId){return ApiResponse.ok(service.buildings(communityId));}
    @GetMapping("/rooms") ApiResponse<?> rooms(@RequestParam long buildingId){return ApiResponse.ok(service.rooms(buildingId));}
    @GetMapping("/repair/categories") ApiResponse<?> categories(){return ApiResponse.ok(db.rows("SELECT id,code,name FROM repair_category WHERE status='ENABLED' ORDER BY sort_order,id"));}
    @PostMapping("/room-bindings") ApiResponse<?> bind(@Valid @RequestBody Binding body){return ApiResponse.ok(service.bind(body.roomId()));}
    @GetMapping("/room-bindings") ApiResponse<?> bindings(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.bindings(page,size));}
    @PostMapping("/room-bindings/{id}/review") ApiResponse<?> review(@PathVariable long id,@Valid @RequestBody Review body){return ApiResponse.ok(service.review(id,body.approved(),false,body.reason()));}
    @PostMapping("/room-bindings/{id}/revoke") ApiResponse<?> revoke(@PathVariable long id,@Valid @RequestBody Reason body){return ApiResponse.ok(service.review(id,false,true,body.reason()));}
}
