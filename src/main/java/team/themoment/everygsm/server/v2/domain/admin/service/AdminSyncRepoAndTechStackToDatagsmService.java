package team.themoment.everygsm.server.v2.domain.admin.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import team.themoment.everygsm.server.v2.domain.admin.dto.response.DatagsmSyncResDto;
import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.Status;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.global.exception.error.ExpectedException;

/**
 * 이미 승인되어 datagsm에 등록된(externalProjectId 보유) 프로젝트의 리포지토리 링크·기술스택을 관리자가 수동으로
 * datagsm에 재동기화하기 위한 일괄 처리. cron 동기화(SyncProjectService)가 아직 이 두 필드를 검증하지 못하는
 * 동안, 웹훅 유실 등으로 벌어진 불일치를 즉시 밀어넣는 용도로 사용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSyncRepoAndTechStackToDatagsmService {

    private final ProjectRepository projectRepository;
    private final DatagsmProjectSyncSupport datagsmProjectSyncSupport;

    @Autowired
    @Lazy
    private AdminSyncRepoAndTechStackToDatagsmService self;

    public DatagsmSyncResDto execute() {
        List<ProjectJpaEntity> targets = projectRepository.findAllByStatusWithCollections(Status.APPROVED).stream()
                .filter(project -> project.getExternalProjectId() != null).toList();

        List<Long> failedProjectIds = new ArrayList<>();
        for (ProjectJpaEntity project : targets) {
            try {
                self.syncOne(project.getId());
            } catch (RuntimeException e) {
                log.error("datagsm 리포지토리/기술스택 재동기화에 실패했습니다. projectId={}", project.getId(), e);
                failedProjectIds.add(project.getId());
            }
        }

        int successCount = targets.size() - failedProjectIds.size();
        log.info("datagsm 리포지토리/기술스택 일괄 재동기화 완료. total={}, success={}, failed={}",
                targets.size(),
                successCount,
                failedProjectIds.size());
        return new DatagsmSyncResDto(targets.size(), successCount, failedProjectIds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncOne(Long projectId) {
        ProjectJpaEntity project = projectRepository.findProjectWithCollectionsById(projectId).orElseThrow(
                () -> new ExpectedException("해당 프로젝트가 존재하지 않습니다. projectId=" + projectId, HttpStatus.NOT_FOUND));
        datagsmProjectSyncSupport.updateInDatagsm(project);
    }
}
