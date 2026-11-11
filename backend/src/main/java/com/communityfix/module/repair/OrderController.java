package com.communityfix.module.repair;

import com.communityfix.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController @RequestMapping("/api/repair/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service){this.service=service;}
    public record Create(@NotNull @Positive Long roomId,@NotNull @Positive Long categoryId,@NotBlank @Size(max=100) String title,@NotBlank @Size(max=2000) String description,@NotNull @Pattern(regexp="NORMAL|HIGH|URGENT") String priority,@Size(max=9) List<@NotNull @Positive Long> attachmentIds,Instant appointmentStartAt,Instant appointmentEndAt,@Positive Long analysisId,@Pattern(regexp="PUBLIC|PRIVATE") String facilityScope,@Size(max=200) String locationText){}
    public record Action(@Pattern(regexp="PUBLIC|PRIVATE") String confirmedFacilityScope,@NotNull @Min(0) Integer version,Boolean approved,@Size(max=500) String reason,@Positive Long workerId,@Pattern(regexp="MANUAL|SMART") String dispatchType,@Positive Long recommendationId,@Size(max=2000) String content,@Size(max=9) List<@NotNull @Positive Long> attachmentIds){}
    public record Content(@NotBlank @Size(max=2000) String content){}
    @PostMapping ApiResponse<?> create(@Valid @RequestBody Create body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.create(body,key));}
    @GetMapping ApiResponse<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status,@RequestParam(required=false) String search){return ApiResponse.ok(service.list(page,size,status,search));}
    @GetMapping("/{id}") ApiResponse<?> detail(@PathVariable long id){return ApiResponse.ok(service.read(id));}
    @PostMapping("/{id}/{action:review|cancel|assign|accept|reject|revoke|start|finish|confirm|rework}") ApiResponse<?> action(@PathVariable long id,@PathVariable String action,@Valid @RequestBody Action body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.action(id,action,body,key));}
    @GetMapping("/{id}/logs") ApiResponse<?> logs(@PathVariable long id){return ApiResponse.ok(service.logs(id));}
    @GetMapping("/{id}/work-records") ApiResponse<?> records(@PathVariable long id){return ApiResponse.ok(service.records(id));}
    @PostMapping("/{id}/work-records") ApiResponse<?> progress(@PathVariable long id,@Valid @RequestBody Content body){return ApiResponse.ok(service.progress(id,body.content()));}
    @GetMapping("/{id}/media") ApiResponse<?> media(@PathVariable long id){return ApiResponse.ok(service.media(id));}
}
