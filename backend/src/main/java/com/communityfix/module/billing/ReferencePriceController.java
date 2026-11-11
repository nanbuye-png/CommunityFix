package com.communityfix.module.billing;
import com.communityfix.common.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
@RestController @RequestMapping("/api/reference-prices")
public class ReferencePriceController {
 private final ReferencePriceService service;private final IdempotencyService idem;
 public ReferencePriceController(ReferencePriceService service,IdempotencyService idem){this.service=service;this.idem=idem;}
 @GetMapping Object list(@RequestParam long communityId){return ApiResponse.ok(service.list(communityId));}
 @GetMapping("/history") Object history(@RequestParam long communityId,@RequestParam long categoryId){return ApiResponse.ok(service.history(communityId,categoryId));}
 @PostMapping @Transactional Object save(@RequestParam long communityId,@RequestParam long categoryId,@Valid @RequestBody BillingController.ReferencePrice b,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(idem.run("reference-price:"+communityId+":"+categoryId,key,b,()->{service.save(communityId,categoryId,b);return service.list(communityId);}));}
}
