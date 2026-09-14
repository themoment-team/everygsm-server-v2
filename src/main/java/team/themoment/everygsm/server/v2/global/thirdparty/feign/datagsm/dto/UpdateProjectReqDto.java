package team.themoment.everygsm.server.v2.global.thirdparty.feign.datagsm.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;
import team.themoment.datagsm.sdk.openapi.model.ProjectStatus;

@Getter
@Builder
public class UpdateProjectReqDto {

    private final String name;
    private final String description;
    private final int startYear;
    private final Long clubId;
    private final List<Long> participantIds;
    private final ProjectStatus status;
    private final Integer endYear;
    private final List<String> repositories;
    private final List<String> techStacks;
}
