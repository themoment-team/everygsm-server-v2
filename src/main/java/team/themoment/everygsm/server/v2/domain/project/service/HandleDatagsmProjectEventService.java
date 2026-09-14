package team.themoment.everygsm.server.v2.domain.project.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import team.themoment.datagsm.sdk.openapi.DataGsmOpenApiClient;
import team.themoment.datagsm.sdk.openapi.model.ClubDetail;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmEventReqDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmProjectEventClubDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmProjectEventObjectDto;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmProjectEventParticipantDto;
import team.themoment.everygsm.server.v2.domain.project.entity.ProjectJpaEntity;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.DatagsmProjectStatus;
import team.themoment.everygsm.server.v2.domain.project.repository.ProjectRepository;
import team.themoment.everygsm.server.v2.domain.user.entity.UserJpaEntity;
import team.themoment.everygsm.server.v2.domain.user.entity.constant.Role;
import team.themoment.everygsm.server.v2.domain.user.repository.UserRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class HandleDatagsmProjectEventService {

    private static final String PROJECT_UPDATED_EVENT = "project.updated";

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DataGsmOpenApiClient dataGsmOpenApiClient;

    @Transactional
    public void execute(DatagsmEventReqDto event) {
        if (!PROJECT_UPDATED_EVENT.equals(event.event())) {
            log.info("처리 대상이 아닌 datagsm 이벤트를 무시합니다. event={}", event.event());
            return;
        }

        if (event.data() == null) {
            log.warn("project.updated 이벤트에 data 필드가 없어 무시합니다. eventId={}", event.id());
            return;
        }

        DatagsmProjectEventObjectDto newState = event.data().newSnapshot() != null
                ? event.data().newSnapshot().object()
                : null;
        if (newState == null || newState.id() == null) {
            log.warn("project.updated 이벤트에 유효한 new 스냅샷이 없어 무시합니다. eventId={}", event.id());
            return;
        }

        ProjectJpaEntity project = projectRepository.findByExternalProjectId(newState.id()).orElse(null);
        if (project == null) {
            log.info("EveryGSM에 매핑되지 않은 datagsm 프로젝트 이벤트라 무시합니다. externalProjectId={}", newState.id());
            return;
        }

        if (isSameAsCurrent(project, newState)) {
            log.info("EveryGSM이 유발한 변경과 동일해 이벤트를 스킵합니다. externalProjectId={}", newState.id());
            return;
        }

        Set<UserJpaEntity> participants = resolveParticipants(newState);
        UserJpaEntity owner = resolveOwner(newState, participants, project.getUser());
        project.syncUpdate(newState.name(),
                newState.description(),
                newState.club() != null ? newState.club().name() : null,
                newState.startYear(),
                owner);
        project.replaceParticipants(participants);
        project.replaceRepoUrls(toSet(newState.repositories()));
        project.replaceStackNames(toSet(newState.techStacks()));
        project.updateDatagsmState(parseStatus(newState.status()), newState.endYear());
        log.info("datagsm project.updated 이벤트를 반영했습니다. externalProjectId={}", newState.id());
    }

    private boolean isSameAsCurrent(ProjectJpaEntity project, DatagsmProjectEventObjectDto newState) {
        String currentAffiliation = project.getAffiliation();
        String newAffiliation = newState.club() != null ? newState.club().name() : null;

        Set<String> currentParticipantEmails = project.getParticipants().stream().map(UserJpaEntity::getEmail)
                .collect(Collectors.toSet());
        Set<String> newParticipantEmails = newState.participants() == null
                ? Set.of()
                : newState.participants().stream().map(DatagsmProjectEventParticipantDto::email)
                        .collect(Collectors.toSet());

        return Objects.equals(project.getTitle(), newState.name())
                && Objects.equals(project.getDescription(), newState.description())
                && Objects.equals(currentAffiliation, newAffiliation)
                && Objects.equals(project.getStartYear(), newState.startYear())
                && Objects.equals(currentParticipantEmails, newParticipantEmails)
                && Objects.equals(project.getDatagsmStatus(), parseStatus(newState.status()))
                && Objects.equals(project.getDatagsmEndYear(), newState.endYear())
                && Objects.equals(project.getRepoUrls(), toSet(newState.repositories()))
                && Objects.equals(project.getStackNames(), toSet(newState.techStacks()));
    }

    private Set<String> toSet(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private DatagsmProjectStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return DatagsmProjectStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 datagsm status 값이라 무시합니다. status={}", status);
            return null;
        }
    }

    private Set<UserJpaEntity> resolveParticipants(DatagsmProjectEventObjectDto newState) {
        if (newState.participants() == null) {
            return new LinkedHashSet<>();
        }

        Set<UserJpaEntity> participants = new LinkedHashSet<>();
        for (DatagsmProjectEventParticipantDto participant : newState.participants()) {
            if (participant.email() == null || participant.email().isBlank()) {
                continue;
            }
            participants.add(userRepository.findByEmail(participant.email())
                    .orElseGet(() -> userRepository
                            .save(UserJpaEntity.builder().email(participant.email()).name(participant.name())
                                    .studentNumber(participant.studentNumber()).role(Role.USER).build())));
        }
        return participants;
    }

    /**
     * SyncProjectService.findOrCreateOwner()와 동일하게 동아리 리더를 우선 owner로 지정하고, 리더를 확인할
     * 수 없을 때만 첫 참여자로 폴백한다. 이 우선순위가 어긋나면 cron 동기화 경로와 웹훅 이벤트 경로 중 어느 쪽을 타느냐에 따라 같은
     * 프로젝트의 owner가 달라질 수 있다.
     */
    private UserJpaEntity resolveOwner(DatagsmProjectEventObjectDto newState,
            Set<UserJpaEntity> participants,
            UserJpaEntity fallback) {
        DatagsmProjectEventClubDto club = newState.club();
        if (club != null && club.id() != null) {
            try {
                ClubDetail clubDetail = dataGsmOpenApiClient.clubs().getClub(club.id());
                if (clubDetail.getLeader().isPresent()) {
                    String leaderEmail = clubDetail.getLeader().get().getEmail();
                    UserJpaEntity leader = participants.stream()
                            .filter(participant -> participant.getEmail().equals(leaderEmail)).findFirst()
                            .orElseGet(() -> userRepository.findByEmail(leaderEmail).orElse(null));
                    if (leader != null) {
                        return leader;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("동아리 리더 조회에 실패해 첫 참여자로 대체합니다. clubId={}", club.id(), e);
            }
        }

        return participants.isEmpty() ? fallback : participants.iterator().next();
    }
}
