package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.metadata.MetadataCatalogService;
import local.ptmcp.admin.web.StrictJson;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/admin/metadata/objects. Management reads expose structure only; query execution still
 * requires the user portal READ grant and the guard pipeline.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/metadata/objects")
public class MetadataAdminController {
    private final MetadataCatalogService catalog;

    public MetadataAdminController(MetadataCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public MetadataCatalogService.Page list(
            @RequestParam long datasourceId,
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return catalog.list(datasourceId, namespaceId, keyword, kind, cursor, pageSize);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MetadataCatalogService.Detail> get(
            @PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        MetadataCatalogService.Detail detail = catalog.detail(id);
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(detail.dictionary().version())))
                .body(detail);
    }

    @PutMapping("/{id}/dictionary")
    public ResponseEntity<MetadataCatalogService.Detail> replaceDictionary(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        MetadataCatalogService.DictionaryWrite write =
                StrictJson.parse(body, MetadataCatalogService.DictionaryWrite.class);
        MetadataCatalogService.Detail detail =
                catalog.replaceDictionary(id, expectedVersion, write, principal.userId());
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(detail.dictionary().version())))
                .body(detail);
    }
}
