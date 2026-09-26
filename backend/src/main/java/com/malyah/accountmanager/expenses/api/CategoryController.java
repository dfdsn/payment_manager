package com.malyah.accountmanager.expenses.api;
import java.net.URI; import java.security.Principal; import java.util.List; import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
import com.malyah.accountmanager.expenses.application.CategoryService; import com.malyah.accountmanager.expenses.application.CategoryView;
@RestController @RequestMapping("/categories")
final class CategoryController {
    private final ObjectProvider<CategoryService> services; CategoryController(ObjectProvider<CategoryService> services){this.services=services;}
    private CategoryService service(){return services.getObject();}
    @GetMapping List<CategoryView> list(Principal p,@RequestParam(defaultValue="false") boolean includeArchived){return service().list(p.getName(),includeArchived);}
    @PostMapping ResponseEntity<CategoryView> create(Principal p,@RequestBody NameRequest r){var v=service().create(p.getName(),r.name());return ResponseEntity.created(URI.create("/api/v1/categories/"+v.id())).body(v);}
    @PutMapping("/{id}") CategoryView rename(Principal p,@PathVariable UUID id,@RequestBody RenameRequest r){return service().rename(p.getName(),id,r.version(),r.name());}
    @PostMapping("/{id}/archive") CategoryView archive(Principal p,@PathVariable UUID id,@RequestBody VersionRequest r){return service().archive(p.getName(),id,r.version());}
    record NameRequest(String name){} record RenameRequest(String name,long version){} record VersionRequest(long version){}
}
