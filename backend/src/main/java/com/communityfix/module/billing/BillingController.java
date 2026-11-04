package com.communityfix.module.billing;

import com.communityfix.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/repair/orders/{id}/billing")
public class BillingController {
    private final BillingService service;public BillingController(BillingService service){this.service=service;}
    public record Item(@NotBlank @Size(max=100) String name,@Min(1) @Max(1000) int quantity,@Min(1) @Max(10000000) long unitPriceFen){}
    public record Quote(@Min(0) int revision,@NotEmpty @Size(max=30) List<@Valid Item> items,@NotBlank @Size(max=500) String reason){}
    public record Decision(@Positive long quoteId,boolean accepted){}
    public record Scope(@NotNull @Pattern(regexp="PUBLIC|PRIVATE") String facilityScope,@NotBlank @Size(max=200) String locationText,@NotBlank @Size(max=500) String reason){}
    public record Pay(@Positive long billId){}
    @GetMapping ApiResponse<?> view(@PathVariable long id){return ApiResponse.ok(service.view(id));}
    @PostMapping("/scope") ApiResponse<?> scope(@PathVariable long id,@Valid @RequestBody Scope body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.scope(id,body,key));}
    @PostMapping("/quotes") ApiResponse<?> quote(@PathVariable long id,@Valid @RequestBody Quote body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.quote(id,body,key));}
    @PostMapping("/decision") ApiResponse<?> decision(@PathVariable long id,@Valid @RequestBody Decision body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.decision(id,body,key));}
    @PostMapping("/mock-pay") ApiResponse<?> pay(@PathVariable long id,@Valid @RequestBody Pay body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.pay(id,body,key));}
}
