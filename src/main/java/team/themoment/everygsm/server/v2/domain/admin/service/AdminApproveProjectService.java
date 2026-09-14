package team.themoment.everygsm.server.v2.domain.admin.service;

import static team.themoment.everygsm.server.v2.domain.project.entity.constant.Status.APPROVED;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import team.themoment.datagsm.sdk.openapi.DataGsmOpenApiClient;
import team.themoment.datagsm.sdk.openapi.client.ProjectApi;
import team.themoment.datagsm.sdk.openapi.model.Project;
import team.themoment.datagsm.sdk.openapi.model.ProjectResponse;
import team.themoment.datagsm.sdk.openapi.model.ProjectStatus;
import team.themoment.everygsm.server.v2.domain.project.dto.response.ProjectResDto;
import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.DatagsmProjectStatus;
import team.themoment.everygsm.server.v2.domain.project.mapper.ProjectMapper;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.global.exception.error.ExpectedException;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.DatagsmApiClient;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.ClubListResDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.DatagsmApiResponse;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.DatagsmProjectResDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.ProjectReqDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.QueryClubReqDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.QueryStudentReqDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.StudentListResDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.UpdateProjectReqDto;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminApproveProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectMapper projectMapper;
    private final DatagsmApiClient datagsmApiClient;
    private final DataGsmOpenApiClient dataGsmOpenApiClient;

    @Transactional
    public ProjectResDto execute(Long projectId) {
        ProjectJpaEntity project = projectRepository.findProjectWithCollectionsById(projectId)
                .orElseThrow(() -> new ExpectedException("해당 프로젝트가 존재하지 않습니다.", HttpStatus.NOT_FOUND));

        if (project.getOriginalProjectId() != null) {
            ProjectJpaEntity original = projectRepository.findProjectWithCollectionsById(project.getOriginalProjectId())
                    .orElseThrow(() -> new ExpectedException("원본 프로젝트가 존재하지 않습니다.", HttpStatus.NOT_FOUND));
            original.applyFrom(project);
            if (original.getExternalProjectId() == null) {
                registerToDatagsm(original);
            } else {
                updateInDatagsm(original);
            }
            original.updateStatus(APPROVED, null);
            project.markInactive();
            return projectMapper.toRes(original, false);
        }

        if (project.getExternalProjectId() == null) {
            registerToDatagsm(project);
        } else {
            updateInDatagsm(project);
        }

        project.updateStatus(APPROVED, null);
        return projectMapper.toRes(project, false);
    }

    private void registerToDatagsm(ProjectJpaEntity project) {
        Long existingId = findExistingExternalId(project.getTitle(), project.getStartYear());
        if (existingId != null) {
            assignIfUnoccupied(project, existingId);
            return;
        }

        Long clubId = resolveClubId(project.getAffiliation());
        List<Long> participantIds = resolveParticipantIds(project);

        ProjectReqDto reqDto = ProjectReqDto.builder().name(project.getTitle()).description(project.getDescription())
                .startYear(project.getStartYear()).clubId(clubId).participantIds(participantIds)
                .repositories(List.copyOf(project.getRepoUrls())).techStacks(List.copyOf(project.getStackNames()))
                .build();

        DatagsmApiResponse<DatagsmProjectResDto> response = datagsmApiClient.createProject(reqDto);
        if (response == null || response.getData() == null || response.getData().getId() == null) {
            Long recoveredId = findExistingExternalId(project.getTitle(), project.getStartYear());
            if (recoveredId != null) {
                log.warn("datagsm 등록 응답을 읽지 못했으나 이름 조회로 id를 회수했습니다. title={}, externalProjectId={}",
                        project.getTitle(),
                        recoveredId);
                assignIfUnoccupied(project, recoveredId);
                return;
            }

            String cause = (response != null && response.getMessage() != null)
                    ? response.getMessage()
                    : "응답 바디가 비어있거나 id가 누락되었습니다.";
            throw new ExpectedException("datagsm 프로젝트 등록에 실패했습니다. 원인=" + cause + ", title=" + project.getTitle(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        assignIfUnoccupied(project, response.getData().getId());
        project.updateDatagsmState(DatagsmProjectStatus.ACTIVE, null);
    }

    private void updateInDatagsm(ProjectJpaEntity project) {
        Long clubId = resolveClubId(project.getAffiliation());
        List<Long> participantIds = resolveParticipantIds(project);
        CurrentDatagsmState currentState = fetchCurrentState(project.getExternalProjectId())
                .orElseThrow(() -> new ExpectedException(
                        "datagsm 현재 상태 조회에 실패해 수정 요청을 중단합니다. externalProjectId=" + project.getExternalProjectId(),
                        HttpStatus.INTERNAL_SERVER_ERROR));

        UpdateProjectReqDto reqDto = UpdateProjectReqDto.builder().name(project.getTitle())
                .description(project.getDescription()).startYear(project.getStartYear()).clubId(clubId)
                .participantIds(participantIds).status(currentState.status()).endYear(currentState.endYear())
                .repositories(List.copyOf(project.getRepoUrls())).techStacks(List.copyOf(project.getStackNames()))
                .build();

        DatagsmApiResponse<DatagsmProjectResDto> response = datagsmApiClient
                .updateProject(project.getExternalProjectId(), reqDto);
        if (response == null || response.getData() == null || response.getData().getId() == null) {
            String cause = (response != null && response.getMessage() != null)
                    ? response.getMessage()
                    : "응답 바디가 비어있거나 id가 누락되었습니다.";
            throw new ExpectedException(
                    "datagsm 프로젝트 수정에 실패했습니다. 원인=" + cause + ", externalProjectId=" + project.getExternalProjectId(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        project.updateDatagsmState(toEntityStatus(currentState.status()), currentState.endYear());
    }

    /**
     * datagsm이 관리하는 status/endYear는 EveryGSM에 저장되지 않으므로, 수정 API 호출 시 이 값을 임의로
     * ACTIVE/null로 덮어쓰지 않도록 기존 값을 조회해 그대로 유지한다. 조회에 실패하면 잘못된 기본값(ACTIVE)으로 덮어쓰지 않도록
     * 빈 Optional을 반환해 호출부가 수정 자체를 중단하게 한다.
     */
    private Optional<CurrentDatagsmState> fetchCurrentState(Long externalProjectId) {
        try {
            Project current = dataGsmOpenApiClient.projects().getProject(externalProjectId);
            if (current != null && current.getStatus() != null) {
                return Optional.of(new CurrentDatagsmState(current.getStatus(), current.getEndYear().orElse(null)));
            }
            log.warn("datagsm 프로젝트 현재 상태 응답이 비어있어 수정을 중단합니다. externalProjectId={}", externalProjectId);
        } catch (RuntimeException e) {
            log.warn("datagsm 프로젝트 현재 상태 조회에 실패해 수정을 중단합니다. externalProjectId={}", externalProjectId, e);
        }
        return Optional.empty();
    }

    private DatagsmProjectStatus toEntityStatus(ProjectStatus status) {
        return status == ProjectStatus.ENDED ? DatagsmProjectStatus.ENDED : DatagsmProjectStatus.ACTIVE;
    }

    private record CurrentDatagsmState(ProjectStatus status, Integer endYear) {
    }

    private void assignIfUnoccupied(ProjectJpaEntity project, Long externalProjectId) {
        projectRepository.findByExternalProjectId(externalProjectId)
                .filter(owner -> !owner.getId().equals(project.getId())).ifPresent(owner -> {
                    throw new ExpectedException(
                            "이미 다른 프로젝트에 매핑된 datagsm 프로젝트입니다. externalProjectId=" + externalProjectId
                                    + ", 점유 중인 projectId=" + owner.getId(),
                            HttpStatus.CONFLICT);
                });
        project.assignExternalProjectId(externalProjectId);
    }

    private Long findExistingExternalId(String title, Integer startYear) {
        int page = 0;
        int totalPages;
        do {
            ProjectResponse response = dataGsmOpenApiClient.projects()
                    .getProjects(new ProjectApi.ProjectRequest().projectName(title).page(page).size(100));
            if (response == null || response.getProjects() == null) {
                return null;
            }
            Long matched = response.getProjects().stream()
                    .filter(p -> title.equals(p.getName()) && Objects.equals(startYear, p.getStartYear()))
                    .map(Project::getId).findFirst().orElse(null);
            if (matched != null) {
                return matched;
            }
            totalPages = response.getTotalPages() != null ? response.getTotalPages() : 0;
            page++;
        } while (page < totalPages);
        return null;
    }

    private Long resolveClubId(String affiliation) {
        if (affiliation == null || affiliation.isBlank()) {
            return null;
        }
        ClubListResDto res = datagsmApiClient.getClubs(QueryClubReqDto.builder().clubName(affiliation).build());
        if (res == null || res.getClubs() == null || res.getClubs().isEmpty()) {
            return null;
        }
        return res.getClubs().getFirst().getId();
    }

    private List<Long> resolveParticipantIds(ProjectJpaEntity project) {
        Set<String> emails = new LinkedHashSet<>();
        if (project.getUser() != null) {
            emails.add(project.getUser().getEmail());
        }
        if (project.getParticipants() != null) {
            project.getParticipants().forEach(participant -> emails.add(participant.getEmail()));
        }

        List<Long> studentIds = new ArrayList<>();
        for (String email : emails) {
            Long studentId = resolveStudentId(email);
            if (studentId != null) {
                studentIds.add(studentId);
            } else {
                log.warn("datagsm에서 참여자 학생을 찾지 못해 동기화에서 제외합니다. email={}, projectId={}", email, project.getId());
            }
        }
        return studentIds;
    }

    private Long resolveStudentId(String email) {
        StudentListResDto res = datagsmApiClient.getStudents(QueryStudentReqDto.builder().email(email).build());
        if (res == null || res.getStudents() == null || res.getStudents().isEmpty()) {
            return null;
        }
        return res.getStudents().get(0).getId();
    }
}
