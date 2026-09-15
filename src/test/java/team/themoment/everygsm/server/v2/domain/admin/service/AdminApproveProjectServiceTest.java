package team.themoment.everygsm.server.v2.domain.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.Status;
import team.themoment.everygsm.server.v2.domain.project.mapper.ProjectMapper;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.global.exception.error.ExpectedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("어드민 프로젝트 승인 서비스 테스트")
class AdminApproveProjectServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long ORIGINAL_PROJECT_ID = 2L;
    private static final long EXTERNAL_ID = 100L;
    private static final String TITLE = "에브리지즘";
    private static final int START_YEAR = 2026;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private DatagsmProjectSyncSupport datagsmProjectSyncSupport;

    @InjectMocks
    private AdminApproveProjectService adminApproveProjectService;

    private ProjectJpaEntity project;

    @BeforeEach
    void setUp() {
        project = ProjectJpaEntity.builder().title(TITLE).description("설명").logo("logo.png").prodUrl("https://a.b")
                .startYear(START_YEAR).status(Status.PENDING).build();
    }

    @Nested
    @DisplayName("execute 메서드는")
    class Describe_execute {

        @Nested
        @DisplayName("존재하지 않는 프로젝트 ID가 주어진 경우")
        class Context_with_nonexistent_id {

            @Test
            @DisplayName("ExpectedException을 던진다")
            void it_throws_expected_exception() {
                given(projectRepository.findProjectWithCollectionsById(99L)).willReturn(Optional.empty());

                assertThrows(ExpectedException.class, () -> adminApproveProjectService.execute(99L));
            }
        }

        @Nested
        @DisplayName("externalProjectId가 없는 프로젝트인 경우")
        class Context_without_external_project_id {

            @Test
            @DisplayName("datagsm 등록을 수행하고 상태를 APPROVED로 바꾼다")
            void it_registers_and_approves() {
                given(projectRepository.findProjectWithCollectionsById(PROJECT_ID)).willReturn(Optional.of(project));

                adminApproveProjectService.execute(PROJECT_ID);

                verify(datagsmProjectSyncSupport).registerToDatagsm(project);
                verify(datagsmProjectSyncSupport, never()).updateInDatagsm(project);
                assertEquals(Status.APPROVED, project.getStatus());
            }
        }

        @Nested
        @DisplayName("이미 externalProjectId를 가진 프로젝트인 경우")
        class Context_with_external_project_id {

            @Test
            @DisplayName("datagsm 수정을 수행하고 상태를 APPROVED로 바꾼다")
            void it_updates_and_approves() {
                project.assignExternalProjectId(EXTERNAL_ID);
                given(projectRepository.findProjectWithCollectionsById(PROJECT_ID)).willReturn(Optional.of(project));

                adminApproveProjectService.execute(PROJECT_ID);

                verify(datagsmProjectSyncSupport).updateInDatagsm(project);
                verify(datagsmProjectSyncSupport, never()).registerToDatagsm(project);
                assertEquals(Status.APPROVED, project.getStatus());
            }
        }

        @Nested
        @DisplayName("승인된 프로젝트의 수정 카피본(originalProjectId 보유)인 경우")
        class Context_with_original_project {

            @Test
            @DisplayName("원본에 내용을 반영하고 원본을 승인 처리한 뒤 카피본은 비활성화한다")
            void it_applies_to_original_and_approves_original() {
                ProjectJpaEntity original = ProjectJpaEntity.builder().title("원본 제목").description("원본 설명")
                        .logo("logo.png").prodUrl("https://a.b").startYear(START_YEAR).status(Status.APPROVED)
                        .externalProjectId(EXTERNAL_ID).build();
                ProjectJpaEntity copy = ProjectJpaEntity.builder().title(TITLE).description("설명").logo("logo.png")
                        .prodUrl("https://a.b").startYear(START_YEAR).status(Status.PENDING)
                        .originalProjectId(ORIGINAL_PROJECT_ID).build();
                given(projectRepository.findProjectWithCollectionsById(PROJECT_ID)).willReturn(Optional.of(copy));
                given(projectRepository.findProjectWithCollectionsById(ORIGINAL_PROJECT_ID))
                        .willReturn(Optional.of(original));

                adminApproveProjectService.execute(PROJECT_ID);

                verify(datagsmProjectSyncSupport).updateInDatagsm(original);
                assertEquals(TITLE, original.getTitle());
                assertEquals(Status.APPROVED, original.getStatus());
                assertEquals(Status.INACTIVE, copy.getStatus());
            }
        }
    }
}
