package team.themoment.everygsm.server.v2.domain.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import team.themoment.datagsm.sdk.openapi.DataGsmOpenApiClient;
import team.themoment.datagsm.sdk.openapi.client.ProjectApi;
import team.themoment.datagsm.sdk.openapi.model.Project;
import team.themoment.datagsm.sdk.openapi.model.ProjectResponse;
import team.themoment.datagsm.sdk.openapi.model.ProjectStatus;
import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.DatagsmProjectStatus;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.Status;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.global.exception.error.ExpectedException;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.DatagsmApiClient;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.DatagsmApiResponse;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.DatagsmProjectResDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.ProjectReqDto;
import team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto.UpdateProjectReqDto;

@ExtendWith(MockitoExtension.class)
@DisplayName("datagsm 프로젝트 동기화 공용 로직 테스트")
class DatagsmProjectSyncSupportTest {

    private static final long EXTERNAL_ID = 100L;
    private static final String TITLE = "에브리지즘";
    private static final int START_YEAR = 2026;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DatagsmApiClient datagsmApiClient;

    @Mock
    private DataGsmOpenApiClient dataGsmOpenApiClient;

    @Mock
    private ProjectApi projectApi;

    @InjectMocks
    private DatagsmProjectSyncSupport datagsmProjectSyncSupport;

    private ProjectJpaEntity project;

    @BeforeEach
    void setUp() {
        project = ProjectJpaEntity.builder().title(TITLE).description("설명").logo("logo.png").prodUrl("https://a.b")
                .startYear(START_YEAR).status(Status.PENDING).build();
    }

    /** datagsm 프로젝트 검색 결과를 흉내낸다. */
    private void givenDatagsmSearchReturns(Project... found) {
        ProjectResponse response = new ProjectResponse();
        response.setProjects(List.of(found));
        response.setTotalPages(1);
        given(dataGsmOpenApiClient.projects()).willReturn(projectApi);
        given(projectApi.getProjects(any(ProjectApi.ProjectRequest.class))).willReturn(response);
    }

    private Project datagsmProject() {
        Project external = new Project();
        external.setId(EXTERNAL_ID);
        external.setName(TITLE);
        external.setStartYear(START_YEAR);
        return external;
    }

    @Nested
    @DisplayName("registerToDatagsm 메서드는")
    class Describe_registerToDatagsm {

        @Nested
        @DisplayName("datagsm 등록 응답을 읽지 못했지만 같은 이름의 프로젝트가 datagsm에 이미 있는 경우")
        class Context_with_unreadable_response_but_existing_project {

            @Test
            @DisplayName("externalProjectId를 회수해 유실을 막는다")
            void it_recovers_external_project_id() {
                // 최초 조회는 미등록, 생성 응답은 읽지 못하고, 재조회에서 발견되는 상황
                ProjectResponse empty = new ProjectResponse();
                empty.setProjects(List.of());
                empty.setTotalPages(1);

                ProjectResponse found = new ProjectResponse();
                found.setProjects(List.of(datagsmProject()));
                found.setTotalPages(1);

                given(dataGsmOpenApiClient.projects()).willReturn(projectApi);
                given(projectApi.getProjects(any(ProjectApi.ProjectRequest.class))).willReturn(empty, found);
                given(datagsmApiClient.createProject(any(ProjectReqDto.class))).willReturn(null);
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.empty());

                datagsmProjectSyncSupport.registerToDatagsm(project);

                assertEquals(EXTERNAL_ID, project.getExternalProjectId());
            }
        }

        @Nested
        @DisplayName("datagsm에 같은 이름과 startYear의 프로젝트가 이미 있는 경우")
        class Context_with_existing_datagsm_project {

            @Test
            @DisplayName("새로 생성하지 않고 기존 id에 매핑한다")
            void it_maps_without_creating() {
                givenDatagsmSearchReturns(datagsmProject());
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.empty());

                datagsmProjectSyncSupport.registerToDatagsm(project);

                assertEquals(EXTERNAL_ID, project.getExternalProjectId());
                verify(datagsmApiClient, never()).createProject(any(ProjectReqDto.class));
            }
        }

        @Nested
        @DisplayName("매핑하려는 externalProjectId를 다른 프로젝트가 이미 점유한 경우")
        class Context_with_occupied_external_id {

            @Test
            @DisplayName("ExpectedException을 던진다")
            void it_throws_expected_exception() {
                givenDatagsmSearchReturns(datagsmProject());
                ProjectJpaEntity occupier = mock(ProjectJpaEntity.class);
                given(occupier.getId()).willReturn(999L);
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.of(occupier));

                assertThrows(ExpectedException.class, () -> datagsmProjectSyncSupport.registerToDatagsm(project));
            }
        }
    }

    @Nested
    @DisplayName("updateInDatagsm 메서드는")
    class Describe_updateInDatagsm {

        @Test
        @DisplayName("수정 응답에 id가 없으면 ExpectedException을 던진다")
        void it_throws_when_update_response_is_invalid() {
            project.assignExternalProjectId(EXTERNAL_ID);

            Project currentDatagsmProject = new Project();
            currentDatagsmProject.setId(EXTERNAL_ID);
            currentDatagsmProject.setStatus(ProjectStatus.ACTIVE);
            given(dataGsmOpenApiClient.projects()).willReturn(projectApi);
            given(projectApi.getProject(EXTERNAL_ID)).willReturn(currentDatagsmProject);

            given(datagsmApiClient.updateProject(eq(EXTERNAL_ID), any(UpdateProjectReqDto.class))).willReturn(null);

            assertThrows(ExpectedException.class, () -> datagsmProjectSyncSupport.updateInDatagsm(project));
        }

        @Test
        @DisplayName("datagsm 현재 상태 조회에 실패하면 ExpectedException을 던지고 수정 API를 호출하지 않는다")
        void it_throws_when_current_state_fetch_fails() {
            project.assignExternalProjectId(EXTERNAL_ID);

            given(dataGsmOpenApiClient.projects()).willReturn(projectApi);
            given(projectApi.getProject(EXTERNAL_ID)).willThrow(new RuntimeException("network error"));

            assertThrows(ExpectedException.class, () -> datagsmProjectSyncSupport.updateInDatagsm(project));
            verify(datagsmApiClient, never()).updateProject(any(Long.class), any(UpdateProjectReqDto.class));
        }

        @Test
        @DisplayName("datagsm에 이미 ENDED 상태로 등록된 경우 그 상태를 유지한 채 수정한다")
        void it_preserves_existing_status() {
            project.assignExternalProjectId(EXTERNAL_ID);

            Project currentDatagsmProject = new Project();
            currentDatagsmProject.setId(EXTERNAL_ID);
            currentDatagsmProject.setStatus(ProjectStatus.ENDED);
            currentDatagsmProject.setEndYear(2025);
            given(dataGsmOpenApiClient.projects()).willReturn(projectApi);
            given(projectApi.getProject(EXTERNAL_ID)).willReturn(currentDatagsmProject);

            DatagsmApiResponse<DatagsmProjectResDto> response = mock(DatagsmApiResponse.class);
            DatagsmProjectResDto data = mock(DatagsmProjectResDto.class);
            given(data.getId()).willReturn(EXTERNAL_ID);
            given(response.getData()).willReturn(data);
            ArgumentMatcher<UpdateProjectReqDto> preservesEndedStatus = reqDto -> reqDto
                    .getStatus() == ProjectStatus.ENDED && Integer.valueOf(2025).equals(reqDto.getEndYear());
            given(datagsmApiClient.updateProject(eq(EXTERNAL_ID), argThat(preservesEndedStatus))).willReturn(response);

            datagsmProjectSyncSupport.updateInDatagsm(project);

            verify(datagsmApiClient).updateProject(eq(EXTERNAL_ID), argThat(preservesEndedStatus));
            assertEquals(DatagsmProjectStatus.ENDED, project.getDatagsmStatus());
            assertEquals(2025, project.getDatagsmEndYear());
        }

        @Test
        @DisplayName("EveryGSM의 리포지토리 링크와 기술스택을 수정 요청에 함께 실어 보낸다")
        void it_sends_repositories_and_tech_stacks() {
            project = ProjectJpaEntity.builder().title(TITLE).description("설명").logo("logo.png").prodUrl("https://a.b")
                    .startYear(START_YEAR).status(Status.PENDING).repoUrls(Set.of("https://github.com/team/repo"))
                    .stackNames(Set.of("Kotlin", "Spring Boot")).build();
            project.assignExternalProjectId(EXTERNAL_ID);

            Project currentDatagsmProject = new Project();
            currentDatagsmProject.setId(EXTERNAL_ID);
            currentDatagsmProject.setStatus(ProjectStatus.ACTIVE);
            given(dataGsmOpenApiClient.projects()).willReturn(projectApi);
            given(projectApi.getProject(EXTERNAL_ID)).willReturn(currentDatagsmProject);

            DatagsmApiResponse<DatagsmProjectResDto> response = mock(DatagsmApiResponse.class);
            DatagsmProjectResDto data = mock(DatagsmProjectResDto.class);
            given(data.getId()).willReturn(EXTERNAL_ID);
            given(response.getData()).willReturn(data);
            ArgumentMatcher<UpdateProjectReqDto> carriesRepoAndTechStack = reqDto -> Set
                    .copyOf(reqDto.getRepositories()).equals(Set.of("https://github.com/team/repo"))
                    && Set.copyOf(reqDto.getTechStacks()).equals(Set.of("Kotlin", "Spring Boot"));
            given(datagsmApiClient.updateProject(eq(EXTERNAL_ID), argThat(carriesRepoAndTechStack)))
                    .willReturn(response);

            datagsmProjectSyncSupport.updateInDatagsm(project);

            verify(datagsmApiClient).updateProject(eq(EXTERNAL_ID), argThat(carriesRepoAndTechStack));
        }
    }
}
