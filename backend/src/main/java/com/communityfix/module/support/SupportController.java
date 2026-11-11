package com.communityfix.module.support;

import com.communityfix.common.*;
import com.communityfix.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController @RequestMapping("/api/support")
public class SupportController {
 private final SupportService service;private final SupportKnowledge knowledge;private final SupportEvents events;
 public SupportController(SupportService service,SupportKnowledge knowledge,SupportEvents events){this.service=service;this.knowledge=knowledge;this.events=events;}
 public record Create(Long communityId,Long orderId,@NotNull @Pattern(regexp="RESIDENT|WORKER")String role){}
 public record Message(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}")String clientId,@NotBlank @Size(max=2000)String content){}
 public record Action(@Min(0)int version){}
 public record Read(@Min(0)long seq){}
 public record Heartbeat(boolean online){}
 public record Knowledge(@Size(max=2000) String aliases,Long communityId,@NotBlank @Size(max=100)String title,@NotNull @Size(max=300)String keywords,@NotBlank @Size(max=500)String question,@NotBlank @Size(max=2000)String answer,@NotNull @Pattern(regexp="ALL|RESIDENT|WORKER")String audience,@NotNull @Pattern(regexp="DRAFT|PUBLISHED")String status,@Min(0)int version){}
 @GetMapping("/options") Object options(){return ApiResponse.ok(service.options(CurrentUser.get()));}
 @PostMapping("/sessions") Object create(@Valid @RequestBody Create b){return ApiResponse.ok(service.create(CurrentUser.get(),b.communityId(),b.orderId(),b.role()));}
 @GetMapping("/sessions") Object list(@RequestParam(required=false)String status,@RequestParam(defaultValue="false")boolean mine,@RequestParam(defaultValue="1")int page){return ApiResponse.ok(service.list(CurrentUser.get(),status,mine,page));}
 @GetMapping("/sessions/{id}") Object detail(@PathVariable long id){return ApiResponse.ok(service.detail(CurrentUser.get(),id));}
 @GetMapping("/sessions/{id}/events") DeferredResult<Object> events(@PathVariable long id,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="true")boolean wait,@RequestHeader("Authorization")String auth){return events.watch(auth,id,Math.max(0,after),wait);}
 @PostMapping("/sessions/{id}/messages") Object send(@PathVariable long id,@Valid @RequestBody Message b){return ApiResponse.ok(service.send(CurrentUser.get(),id,b.clientId(),b.content()));}
 @PostMapping("/sessions/{id}/{action:handoff|cancel-wait|claim|takeover|close}") Object action(@PathVariable long id,@PathVariable String action,@Valid @RequestBody Action b){return ApiResponse.ok(service.action(CurrentUser.get(),id,action,b.version()));}
 @PostMapping("/sessions/{id}/read") Object read(@PathVariable long id,@Valid @RequestBody Read b){service.read(CurrentUser.get(),id,b.seq());return ApiResponse.ok(null);}
 @PostMapping("/heartbeat") Object heartbeat(@RequestBody Heartbeat b){service.heartbeat(CurrentUser.get(),b.online());return ApiResponse.ok(null);}
 @GetMapping("/knowledge/statistics") Object statistics(){return ApiResponse.ok(knowledge.statistics(CurrentUser.get()));}
 @GetMapping("/knowledge") Object knowledge(@RequestParam(required=false)Long communityId,@RequestParam(defaultValue="false")boolean editing,@RequestParam(defaultValue="1")int page){return ApiResponse.ok(knowledge.list(CurrentUser.get(),communityId,editing,page));}
 @PostMapping("/knowledge") Object add(@Valid @RequestBody Knowledge b){return ApiResponse.ok(knowledge.save(CurrentUser.get(),null,b));}
 @PatchMapping("/knowledge/{id}") Object edit(@PathVariable long id,@Valid @RequestBody Knowledge b){return ApiResponse.ok(knowledge.save(CurrentUser.get(),id,b));}
}
