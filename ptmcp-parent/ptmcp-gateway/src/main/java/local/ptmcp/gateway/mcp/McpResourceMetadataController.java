package local.ptmcp.gateway.mcp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
final class McpResourceMetadataController {
    private final McpResourceMetadata metadata;

    McpResourceMetadataController(McpResourceMetadata metadata) {
        this.metadata = metadata;
    }

    @GetMapping(
            value = {McpResourceMetadata.ROOT_PATH, McpResourceMetadata.ROOT_PATH + "/mcp"},
            produces = "application/json")
    ResponseEntity<Map<String, Object>> metadata() {
        return ResponseEntity.status(metadata.document().isEmpty() ? 404 : 200)
                .header("Cache-Control", "no-store")
                .body(metadata.document());
    }
}
