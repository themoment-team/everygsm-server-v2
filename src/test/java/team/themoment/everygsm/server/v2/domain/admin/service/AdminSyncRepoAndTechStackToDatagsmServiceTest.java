package team.themoment.everygsm.server.v2.domain.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import team.themoment.everygsm.server.v2.domain.admin.dto.response.DatagsmSyncResDto;
import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.Status;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.global.exception.error.ExpectedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("datagsm 리포지토리·기술스택 일괄 재동기화 서비스 테스트")
class AdminSyncRepoAndTechStackToDatagsmServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DatagsmProjectSyncSupport datagsmProjectSyncSupport;

    @InjectMocks
    private AdminSyncRepoAndTechStackToDatagsmService adminSyncRepoAndTechStackToDatagsmService;

    private ProjectJpaEntity projectWithExternalId(Long id, long externalId) {
        ProjectJpaEntity project = ProjectJpaEntity.builder().title("프로젝트" + id).description("설명").logo("logo.png")
                .prodUrl("https://a.b").startYear(2026).status(Status.APPROVED).externalProjectId(externalId).build();
        setId(project, id);
        return project;
    }

    private ProjectJpaEntity projectWithoutExternalId(Long id) {
        ProjectJpaEntity project = ProjectJpaEntity.builder().title("프로젝트" + id).description("설명").logo("logo.png")
                .prodUrl("https://a.b").startYear(2026).status(Status.APPROVED).build();
        setId(project, id);
        return project;
    }

    private void setId(ProjectJpaEntity project, Long id) {
        try {
            Field idField = ProjectJpaEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(project, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void injectSelf() throws Exception {
        Field self = AdminSyncRepoAndTechStackToDatagsmService.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(adminSyncRepoAndTechStackToDatagsmService, adminSyncRepoAndTechStackToDatagsmService);
    }

    @Nested
    @DisplayName("execute 메서드는")
    class Describe_execute {

        @Nested
        @DisplayName("externalProjectId가 없는 승인된 프로젝트가 섞여 있는 경우")
        class Context_with_projects_missing_external_id {

            @Test
            @DisplayName("externalProjectId가 있는 프로젝트만 재동기화 대상으로 삼는다")
            void it_targets_only_mapped_projects() {
                ProjectJpaEntity mapped = projectWithExternalId(1L, 100L);
                ProjectJpaEntity unmapped = projectWithoutExternalId(2L);
                given(projectRepository.findAllByStatusWithCollections(Status.APPROVED))
                        .willReturn(List.of(mapped, unmapped));
                given(projectRepository.findProjectWithCollectionsById(1L)).willReturn(Optional.of(mapped));

                DatagsmSyncResDto result = adminSyncRepoAndTechStackToDatagsmService.execute();

                verify(datagsmProjectSyncSupport).updateInDatagsm(mapped);
                assertEquals(1, result.totalCount());
                assertEquals(1, result.successCount());
                assertEquals(List.of(), result.failedProjectIds());
            }
        }

        @Nested
        @DisplayName("일부 프로젝트의 재동기화가 실패한 경우")
        class Context_with_partial_failure {

            @Test
            @DisplayName("실패한 프로젝트를 건너뛰고 나머지는 계속 처리하며 결과에 실패 목록을 담는다")
            void it_continues_and_reports_failures() {
                ProjectJpaEntity succeeding = projectWithExternalId(1L, 100L);
                ProjectJpaEntity failing = projectWithExternalId(2L, 200L);
                given(projectRepository.findAllByStatusWithCollections(Status.APPROVED))
                        .willReturn(List.of(succeeding, failing));
                given(projectRepository.findProjectWithCollectionsById(1L)).willReturn(Optional.of(succeeding));
                given(projectRepository.findProjectWithCollectionsById(2L)).willReturn(Optional.of(failing));

                doNothing().when(datagsmProjectSyncSupport).updateInDatagsm(succeeding);
                doThrow(new ExpectedException("실패", HttpStatus.INTERNAL_SERVER_ERROR)).when(datagsmProjectSyncSupport)
                        .updateInDatagsm(failing);

                DatagsmSyncResDto result = adminSyncRepoAndTechStackToDatagsmService.execute();

                assertEquals(2, result.totalCount());
                assertEquals(1, result.successCount());
                assertEquals(List.of(2L), result.failedProjectIds());
            }
        }

        @Nested
        @DisplayName("재동기화 대상이 없는 경우")
        class Context_with_no_target {

            @Test
            @DisplayName("아무 것도 호출하지 않고 빈 결과를 반환한다")
            void it_returns_empty_result() {
                given(projectRepository.findAllByStatusWithCollections(Status.APPROVED)).willReturn(List.of());

                DatagsmSyncResDto result = adminSyncRepoAndTechStackToDatagsmService.execute();

                verify(datagsmProjectSyncSupport, never()).updateInDatagsm(any());
                assertEquals(0, result.totalCount());
                assertEquals(0, result.successCount());
            }
        }
    }
}
