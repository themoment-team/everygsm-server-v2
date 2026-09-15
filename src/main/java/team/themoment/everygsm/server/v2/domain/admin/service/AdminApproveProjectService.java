package team.themoment.everygsm.server.v2.domain.admin.service;

import static team.themoment.everygsm.server.v2.domain.project.entity.constant.Status.APPROVED;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import team.themoment.everygsm.server.v2.domain.project.dto.response.ProjectResDto;
import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.mapper.ProjectMapper;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.global.exception.error.ExpectedException;

@Service
@RequiredArgsConstructor
public class AdminApproveProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectMapper projectMapper;
    private final DatagsmProjectSyncSupport datagsmProjectSyncSupport;

    @Transactional
    public ProjectResDto execute(Long projectId) {
        ProjectJpaEntity project = projectRepository.findProjectWithCollectionsById(projectId)
                .orElseThrow(() -> new ExpectedException("해당 프로젝트가 존재하지 않습니다.", HttpStatus.NOT_FOUND));

        if (project.getOriginalProjectId() != null) {
            ProjectJpaEntity original = projectRepository.findProjectWithCollectionsById(project.getOriginalProjectId())
                    .orElseThrow(() -> new ExpectedException("원본 프로젝트가 존재하지 않습니다.", HttpStatus.NOT_FOUND));
            original.applyFrom(project);
            if (original.getExternalProjectId() == null) {
                datagsmProjectSyncSupport.registerToDatagsm(original);
            } else {
                datagsmProjectSyncSupport.updateInDatagsm(original);
            }
            original.updateStatus(APPROVED, null);
            project.markInactive();
            return projectMapper.toRes(original, false);
        }

        if (project.getExternalProjectId() == null) {
            datagsmProjectSyncSupport.registerToDatagsm(project);
        } else {
            datagsmProjectSyncSupport.updateInDatagsm(project);
        }

        project.updateStatus(APPROVED, null);
        return projectMapper.toRes(project, false);
    }
}
