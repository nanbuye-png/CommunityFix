package com.communityfix.module.repair;

import com.communityfix.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repair/orders/{orderId}/aftercare")
public class AftercareController {
 private final AftercareService service;
 public AftercareController(AftercareService service){this.service=service;}
 public record Create(@NotBlank @Size(max=1000) String content) {}
 public record Action(@NotNull @Min(0) Integer version,
   @NotNull @Pattern(regexp="accept|reject|resolve|confirm|reopen") String action,
   @NotBlank @Size(max=1000) String content) {}
 @GetMapping Object list(@PathVariable long orderId){return ApiResponse.ok(service.list(orderId));}
 @PostMapping Object create(@PathVariable long orderId,@Valid @RequestBody Create body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.create(orderId,body,key));}
 @PostMapping("/{id}") Object action(@PathVariable long orderId,@PathVariable long id,@Valid @RequestBody Action body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.action(orderId,id,body,key));}
}
