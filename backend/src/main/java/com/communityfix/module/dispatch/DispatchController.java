package com.communityfix.module.dispatch;
import com.communityfix.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
public class DispatchController {
    private final DispatchService service;
    public DispatchController(DispatchService service){this.service=service;}
    @PostMapping("/api/repair/orders/{id}/recommendations") ApiResponse<?> recommend(@PathVariable long id){return ApiResponse.ok(service.recommend(id));}
}
