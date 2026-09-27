package com.malyah.accountmanager.expenses.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.malyah.accountmanager.expenses.application.AttachmentUseCase;
import com.malyah.accountmanager.expenses.application.AttachmentView;

@RestController
@RequestMapping("/expenses/{expenseId}/attachments")
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(AttachmentUseCase.class)
class AttachmentController {
    private final AttachmentUseCase useCase;
    AttachmentController(AttachmentUseCase useCase){this.useCase=useCase;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AttachmentView> upload(Principal p,@PathVariable UUID expenseId,@RequestHeader("Idempotency-Key") UUID key,@RequestPart("file") MultipartFile file) throws java.io.IOException {
        var result=useCase.upload(p.getName(),expenseId,key,file.getOriginalFilename(),file.getBytes());
        return ResponseEntity.status(201).body(result);
    }
    @GetMapping List<AttachmentView> list(Principal p,@PathVariable UUID expenseId){return useCase.list(p.getName(),expenseId);}
    @GetMapping("/{attachmentId}") ResponseEntity<byte[]> download(Principal p,@PathVariable UUID expenseId,@PathVariable UUID attachmentId){
        var result=useCase.download(p.getName(),expenseId,attachmentId);
        var encoded=URLEncoder.encode(result.metadata().name(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"download\"; filename*=UTF-8''"+encoded)
                .header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(result.metadata().mediaType())).contentLength(result.bytes().length).body(result.bytes());
    }
    @DeleteMapping("/{attachmentId}") ResponseEntity<Void> remove(Principal p,@PathVariable UUID expenseId,@PathVariable UUID attachmentId){useCase.remove(p.getName(),expenseId,attachmentId);return ResponseEntity.noContent().build();}
}
