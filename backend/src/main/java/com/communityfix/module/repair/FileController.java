package com.communityfix.module.repair;

import com.communityfix.common.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController @RequestMapping("/api/files")
public class FileController {
    private final FileService service;
    public FileController(FileService service){this.service=service;}
    @PostMapping ApiResponse<?> upload(@RequestParam MultipartFile file){return ApiResponse.ok(service.upload(file));}
    @GetMapping("/{id}/access-url") ApiResponse<?> access(@PathVariable long id){return ApiResponse.ok(service.access(id,ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()));}
    @GetMapping("/{id}/content") ResponseEntity<byte[]> content(@PathVariable long id,@RequestParam long expires,@RequestParam String signature){var content=service.content(id,expires,signature);return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.type())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(content.bytes());}
}
