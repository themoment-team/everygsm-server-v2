package team.themoment.everygsm.server.v2.domain.project.dto.webhook;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DatagsmProjectEventObjectDto(Long id, String name, String description, Integer startYear, Integer endYear,
        String status, DatagsmProjectEventClubDto club, List<DatagsmProjectEventParticipantDto> participants,
        List<String> repositories, @JsonProperty("tech_stacks") List<String> techStacks) {
}
