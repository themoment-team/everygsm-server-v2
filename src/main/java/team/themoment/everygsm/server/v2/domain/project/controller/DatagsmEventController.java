package team.themoment.everygsm.server.v2.domain.project.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import team.themoment.everygsm.server.v2.domain.project.dto.webhook.DatagsmEventReqDto;
import team.themoment.everygsm.server.v2.domain.project.service.HandleDatagsmProjectEventService;
import team.themoment.everygsm.server.v2.global.exception.error.ExpectedException;
import team.themoment.everygsm.server.v2.global.security.datagsm.DatagsmSignatureVerifier;
import tools.jackson.databind.ObjectMapper;

@Tag(name = "DatagsmEvent", description = "DataGSM 이벤트 수신 API")
@RestController
@RequestMapping("/api/v2/projects/datagsm-events")
@RequiredArgsConstructor
public class DatagsmEventController {

    private static final String SIGNATURE_HEADER = "X-DataGSM-Signature";

    private final DatagsmSignatureVerifier signatureVerifier;
    private final HandleDatagsmProjectEventService handleDatagsmProjectEventService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "DataGSM 이벤트 수신", description = "DataGSM에서 발행하는 project.updated 등 이벤트를 수신해 반영합니다")
    @PostMapping
    public void handle(@RequestHeader(SIGNATURE_HEADER) String signature, @RequestBody String rawBody) {
        if (!signatureVerifier.isValid(rawBody, signature)) {
            throw new ExpectedException("유효하지 않은 datagsm 이벤트 서명입니다.", HttpStatus.UNAUTHORIZED);
        }

        DatagsmEventReqDto event = parse(rawBody);
        handleDatagsmProjectEventService.execute(event);
    }

    private DatagsmEventReqDto parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, DatagsmEventReqDto.class);
        } catch (Exception e) {
            throw new ExpectedException("datagsm 이벤트 페이로드를 읽을 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
    }
}
