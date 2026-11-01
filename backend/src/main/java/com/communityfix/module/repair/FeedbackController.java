package com.communityfix.module.repair;

import com.communityfix.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class FeedbackController {
    private final FeedbackService service;
    public FeedbackController(FeedbackService service){this.service=service;}
    public record Evaluation(@NotNull @Min(1) @Max(5) Integer score,@Size(max=500) String content){}
    public record Complaint(@NotBlank @Size(max=1000) String content){}
    public record Process(@NotNull @Min(0) Integer version,@NotNull @Pattern(regexp="PROCESSING|CLOSED") String status,@NotBlank @Size(max=1000) String resolution){}
    @PostMapping("/repair/orders/{id}/evaluations") ApiResponse<?> evaluate(@PathVariable long id,@Valid @RequestBody Evaluation body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.evaluate(id,body,key));}
    @GetMapping("/repair/orders/{id}/evaluations") ApiResponse<?> evaluation(@PathVariable long id){return ApiResponse.ok(service.evaluation(id));}
    @PostMapping("/repair/orders/{id}/complaints") ApiResponse<?> complain(@PathVariable long id,@Valid @RequestBody Complaint body,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.complain(id,body,key));}
    @GetMapping("/complaints") ApiResponse<?> complaints(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.complaints(page,size));}
    @GetMapping("/complaints/{id}") ApiResponse<?> complaint(@PathVariable long id){return ApiResponse.ok(service.complaint(id));}
    @PostMapping("/complaints/{id}/process") ApiResponse<?> process(@PathVariable long id,@Valid @RequestBody Process body){return ApiResponse.ok(service.process(id,body));}
    @GetMapping("/complaints/{id}/logs") ApiResponse<?> logs(@PathVariable long id){return ApiResponse.ok(service.complaintLogs(id));}
}
