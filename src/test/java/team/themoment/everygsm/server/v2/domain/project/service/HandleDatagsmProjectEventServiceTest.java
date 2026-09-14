package team.themoment.everygsm.server.v2.domain.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import team.themoment.datagsm.sdk.openapi.DataGsmOpenApiClient;
import team.themoment.datagsm.sdk.openapi.client.ClubApi;
import team.themoment.datagsm.sdk.openapi.model.ClubDetail;
import team.themoment.datagsm.sdk.openapi.model.ParticipantInfo;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmEventDataDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmEventReqDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmEventSnapshotDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmProjectEventClubDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmProjectEventObjectDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmProjectEventParticipantDto;
import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.DatagsmProjectStatus;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.Status;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.domain.user.entity.UserJpaEntity;
import team.themoment.everygsm.server.v2.domain.user.entity.constant.Role;
import team.themoment.everygsm.server.v2.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataGSM 프로젝트 이벤트 처리 서비스 테스트")
class HandleDatagsmProjectEventServiceTest {

    private static final long EXTERNAL_ID = 100L;
    private static final String TITLE = "에브리지즘";
    private static final int START_YEAR = 2026;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DataGsmOpenApiClient dataGsmOpenApiClient;

    @Mock
    private ClubApi clubApi;

    @InjectMocks
    private HandleDatagsmProjectEventService handleDatagsmProjectEventService;

    private ProjectJpaEntity localProject() {
        return ProjectJpaEntity.builder().title(TITLE).description("설명").logo("logo.png").prodUrl("https://a.b")
                .affiliation("동아리A").startYear(START_YEAR).status(Status.APPROVED).externalProjectId(EXTERNAL_ID)
                .datagsmStatus(DatagsmProjectStatus.ACTIVE).build();
    }

    private DatagsmEventReqDto eventOf(DatagsmProjectEventObjectDto newObject) {
        DatagsmEventSnapshotDto newSnapshot = new DatagsmEventSnapshotDto("project.updated:" + EXTERNAL_ID, newObject);
        DatagsmEventDataDto data = new DatagsmEventDataDto(null, newSnapshot);
        return new DatagsmEventReqDto("evt-1", "project.updated", "2026-08-27T02:00:00", data);
    }

    @Nested
    @DisplayName("execute 메서드는")
    class Describe_execute {

        @Nested
        @DisplayName("project.updated가 아닌 이벤트인 경우")
        class Context_with_other_event_type {

            @Test
            @DisplayName("아무 처리도 하지 않는다")
            void it_ignores_event() {
                DatagsmEventReqDto event = new DatagsmEventReqDto("evt-1",
                        "student.updated",
                        "2026-08-27T02:00:00",
                        null);

                handleDatagsmProjectEventService.execute(event);

                verify(projectRepository, never()).findByExternalProjectId(any());
            }
        }

        @Nested
        @DisplayName("data 필드가 없는 경우")
        class Context_with_missing_data {

            @Test
            @DisplayName("아무 처리도 하지 않는다")
            void it_ignores_event() {
                DatagsmEventReqDto event = new DatagsmEventReqDto("evt-1",
                        "project.updated",
                        "2026-08-27T02:00:00",
                        null);

                handleDatagsmProjectEventService.execute(event);

                verify(projectRepository, never()).findByExternalProjectId(any());
            }
        }

        @Nested
        @DisplayName("EveryGSM에 매핑된 external_project_id가 없는 경우")
        class Context_with_unmapped_external_id {

            @Test
            @DisplayName("아무 처리도 하지 않는다")
            void it_ignores_event() {
                DatagsmProjectEventObjectDto newObject = new DatagsmProjectEventObjectDto(EXTERNAL_ID,
                        TITLE,
                        "설명",
                        START_YEAR,
                        null,
                        "ACTIVE",
                        null,
                        List.of(),
                        List.of(),
                        List.of());
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.empty());

                handleDatagsmProjectEventService.execute(eventOf(newObject));

                verify(projectRepository).findByExternalProjectId(EXTERNAL_ID);
            }
        }

        @Nested
        @DisplayName("이벤트 내용이 EveryGSM 현재 값과 동일한 경우 (자기 발신 루프)")
        class Context_with_same_content_as_current {

            @Test
            @DisplayName("갱신하지 않고 스킵한다")
            void it_skips_update() {
                ProjectJpaEntity project = localProject();
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.of(project));

                DatagsmProjectEventClubDto sameClub = new DatagsmProjectEventClubDto(1L, "동아리A");
                DatagsmProjectEventObjectDto sameState = new DatagsmProjectEventObjectDto(EXTERNAL_ID,
                        TITLE,
                        "설명",
                        START_YEAR,
                        null,
                        "ACTIVE",
                        sameClub,
                        List.of(),
                        List.of(),
                        List.of());

                handleDatagsmProjectEventService.execute(eventOf(sameState));

                assertEquals(TITLE, project.getTitle());
                assertEquals("동아리A", project.getAffiliation());
            }
        }

        @Nested
        @DisplayName("이벤트 내용이 EveryGSM 현재 값과 다른 경우 (외부 변경)")
        class Context_with_different_content {

            @Test
            @DisplayName("EveryGSM 데이터를 갱신한다")
            void it_applies_update() {
                ProjectJpaEntity project = localProject();
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.of(project));

                DatagsmProjectEventClubDto changedClub = new DatagsmProjectEventClubDto(1L, "동아리B");
                DatagsmProjectEventObjectDto changedState = new DatagsmProjectEventObjectDto(EXTERNAL_ID,
                        "새 제목",
                        "새 설명",
                        START_YEAR,
                        null,
                        "ACTIVE",
                        changedClub,
                        List.of(),
                        List.of("https://github.com/team/repo"),
                        List.of("Kotlin", "Spring Boot"));

                handleDatagsmProjectEventService.execute(eventOf(changedState));

                assertEquals("새 제목", project.getTitle());
                assertEquals("새 설명", project.getDescription());
                assertEquals("동아리B", project.getAffiliation());
                assertEquals(Set.of("https://github.com/team/repo"), project.getRepoUrls());
                assertEquals(Set.of("Kotlin", "Spring Boot"), project.getStackNames());
            }
        }

        @Nested
        @DisplayName("참여자 구성만 다른 경우")
        class Context_with_different_participants {

            @Test
            @DisplayName("동일 이벤트로 판단하지 않고 참여자 목록을 갱신한다")
            void it_updates_participants() {
                ProjectJpaEntity project = localProject();
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.of(project));

                UserJpaEntity newParticipant = UserJpaEntity.builder().email("new@gsm.hs.kr").name("새참여자")
                        .studentNumber("3021").role(Role.USER).build();
                given(userRepository.findByEmail("new@gsm.hs.kr")).willReturn(Optional.of(newParticipant));

                DatagsmProjectEventClubDto sameClub = new DatagsmProjectEventClubDto(1L, "동아리A");
                DatagsmProjectEventParticipantDto participant = new DatagsmProjectEventParticipantDto("3021",
                        "새참여자",
                        "new@gsm.hs.kr");
                DatagsmProjectEventObjectDto changedParticipants = new DatagsmProjectEventObjectDto(EXTERNAL_ID,
                        TITLE,
                        "설명",
                        START_YEAR,
                        null,
                        "ACTIVE",
                        sameClub,
                        List.of(participant),
                        List.of(),
                        List.of());

                handleDatagsmProjectEventService.execute(eventOf(changedParticipants));

                Set<String> emails = project.getParticipants().stream().map(UserJpaEntity::getEmail)
                        .collect(Collectors.toSet());
                assertTrue(emails.contains("new@gsm.hs.kr"));
            }
        }

        @Nested
        @DisplayName("datagsm 운영 상태가 ENDED로 바뀐 경우")
        class Context_with_status_changed_to_ended {

            @Test
            @DisplayName("동일 이벤트로 판단하지 않고 datagsmStatus/datagsmEndYear를 갱신한다")
            void it_updates_datagsm_state() {
                ProjectJpaEntity project = localProject();
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.of(project));

                DatagsmProjectEventClubDto sameClub = new DatagsmProjectEventClubDto(1L, "동아리A");
                DatagsmProjectEventObjectDto endedState = new DatagsmProjectEventObjectDto(EXTERNAL_ID,
                        TITLE,
                        "설명",
                        START_YEAR,
                        2026,
                        "ENDED",
                        sameClub,
                        List.of(),
                        List.of(),
                        List.of());

                handleDatagsmProjectEventService.execute(eventOf(endedState));

                assertEquals(DatagsmProjectStatus.ENDED, project.getDatagsmStatus());
                assertEquals(2026, project.getDatagsmEndYear());
            }
        }

        @Nested
        @DisplayName("동아리 리더가 첫 참여자가 아닌 경우")
        class Context_with_leader_not_first_participant {

            @Test
            @DisplayName("동아리 리더를 owner로 지정한다")
            void it_assigns_leader_as_owner() {
                ProjectJpaEntity project = localProject();
                given(projectRepository.findByExternalProjectId(EXTERNAL_ID)).willReturn(Optional.of(project));

                UserJpaEntity firstParticipant = UserJpaEntity.builder().email("first@gsm.hs.kr").name("첫참여자")
                        .studentNumber("3011").role(Role.USER).build();
                UserJpaEntity leader = UserJpaEntity.builder().email("leader@gsm.hs.kr").name("리더")
                        .studentNumber("3022").role(Role.USER).build();
                given(userRepository.findByEmail("first@gsm.hs.kr")).willReturn(Optional.of(firstParticipant));
                given(userRepository.findByEmail("leader@gsm.hs.kr")).willReturn(Optional.of(leader));

                given(dataGsmOpenApiClient.clubs()).willReturn(clubApi);
                ClubDetail clubDetail = mock(ClubDetail.class);
                ParticipantInfo leaderInfo = mock(ParticipantInfo.class);
                given(leaderInfo.getEmail()).willReturn("leader@gsm.hs.kr");
                given(clubDetail.getLeader()).willReturn(Optional.of(leaderInfo));
                given(clubApi.getClub(1L)).willReturn(clubDetail);

                DatagsmProjectEventClubDto sameClub = new DatagsmProjectEventClubDto(1L, "동아리A");
                DatagsmProjectEventParticipantDto first = new DatagsmProjectEventParticipantDto("3011",
                        "첫참여자",
                        "first@gsm.hs.kr");
                DatagsmProjectEventParticipantDto leaderParticipant = new DatagsmProjectEventParticipantDto("3022",
                        "리더",
                        "leader@gsm.hs.kr");
                DatagsmProjectEventObjectDto changedState = new DatagsmProjectEventObjectDto(EXTERNAL_ID,
                        "새 제목",
                        "설명",
                        START_YEAR,
                        null,
                        "ACTIVE",
                        sameClub,
                        List.of(first, leaderParticipant),
                        List.of(),
                        List.of());

                handleDatagsmProjectEventService.execute(eventOf(changedState));

                assertEquals("leader@gsm.hs.kr", project.getUser().getEmail());
            }
        }
    }
}
