package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.datasource.DatasourceJobService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/admin/jobs/{id}: job state without inventing progress totals. */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/jobs")
public class JobAdminController {
    private final DatasourceJobService jobs;

    public JobAdminController(DatasourceJobService jobs) {
        this.jobs = jobs;
    }

    @AdminAction("ptmcp:job:read")
    @GetMapping("/{id}")
    public DatasourceJobService.JobView get(@PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return jobs.get(id);
    }
}
