package team.themoment.everygsm.server.v2.domain.admin.dto.response;

import java.util.List;

public record DatagsmSyncResDto(int totalCount, int successCount, List<Long> failedProjectIds) {
}
