package team.themoment.everygsm.server.v2.domain.project.entity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.DatagsmProjectStatus;
import team.themoment.everygsm.server.v2.domain.project.entity.constant.Status;
import team.themoment.everygsm.server.v2.domain.user.entity.UserJpaEntity;

@Entity
@Table(name = "projects")
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Getter
public class ProjectJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserJpaEntity user;

    @Column(nullable = false, length = 512)
    private String logo;

    @Column(nullable = false)
    private String title;

    @Column(length = 512)
    private String affiliation;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "prod_url", nullable = false, length = 512)
    private String prodUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ElementCollection
    @CollectionTable(name = "repo_urls", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "repo_url", nullable = false, length = 512)
    private Set<String> repoUrls;

    @ElementCollection
    @CollectionTable(name = "stack_names", joinColumns = @JoinColumn(name = "project_id"), uniqueConstraints = @UniqueConstraint(columnNames = {
            "project_id", "stack_name"}))
    @Column(name = "stack_name", nullable = false)
    private Set<String> stackNames;

    @Column(name = "start_year", nullable = false)
    private Integer startYear;

    @Column(name = "external_project_id", unique = true)
    private Long externalProjectId;

    @Column(name = "original_project_id")
    private Long originalProjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "datagsm_status", length = 20)
    private DatagsmProjectStatus datagsmStatus;

    @Column(name = "datagsm_end_year")
    private Integer datagsmEndYear;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "project_participants", joinColumns = @JoinColumn(name = "project_id"), inverseJoinColumns = @JoinColumn(name = "user_id"), uniqueConstraints = @UniqueConstraint(columnNames = {
            "project_id", "user_id"}))
    @BatchSize(size = 100)
    private Set<UserJpaEntity> participants;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @Builder
    public ProjectJpaEntity(UserJpaEntity user,
            String logo,
            String title,
            String affiliation,
            String description,
            String prodUrl,
            Status status,
            String reason,
            Integer startYear,
            Set<String> repoUrls,
            Set<String> stackNames,
            Long externalProjectId,
            Long originalProjectId,
            DatagsmProjectStatus datagsmStatus,
            Integer datagsmEndYear,
            Set<UserJpaEntity> participants) {
        this.user = user;
        this.logo = logo;
        this.title = title;
        this.affiliation = affiliation;
        this.description = description;
        this.prodUrl = prodUrl;
        this.status = status;
        this.reason = reason;
        this.startYear = startYear;
        this.repoUrls = repoUrls != null ? repoUrls : new HashSet<>();
        this.stackNames = stackNames != null ? stackNames : new HashSet<>();
        this.externalProjectId = externalProjectId;
        this.originalProjectId = originalProjectId;
        this.datagsmStatus = datagsmStatus;
        this.datagsmEndYear = datagsmEndYear;
        this.participants = participants != null ? participants : new HashSet<>();
    }

    public void replaceParticipants(Set<UserJpaEntity> newParticipants) {
        this.participants.clear();
        if (newParticipants != null) {
            this.participants.addAll(newParticipants);
        }
    }

    public void updateStatus(Status status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public void syncUpdate(String title,
            String description,
            String affiliation,
            Integer startYear,
            UserJpaEntity user) {
        this.title = title;
        this.description = description;
        this.affiliation = affiliation;
        this.startYear = startYear;
        if (user != null) {
            this.user = user;
        }
    }

    public void applyFrom(ProjectJpaEntity copy) {
        this.logo = copy.logo;
        this.title = copy.title;
        this.affiliation = copy.affiliation;
        this.description = copy.description;
        this.prodUrl = copy.prodUrl;
        this.startYear = copy.startYear;
        this.repoUrls.clear();
        this.repoUrls.addAll(copy.repoUrls);
        this.stackNames.clear();
        this.stackNames.addAll(copy.stackNames);
        this.participants.clear();
        this.participants.addAll(copy.participants);
    }

    public void updateContent(String logo,
            String title,
            String affiliation,
            String description,
            String prodUrl,
            int startYear,
            Set<String> stackNames,
            Set<String> repoUrls,
            Set<UserJpaEntity> participants) {
        this.logo = logo;
        this.title = title;
        this.affiliation = affiliation;
        this.description = description;
        this.prodUrl = prodUrl;
        this.startYear = startYear;
        this.stackNames.clear();
        this.stackNames.addAll(stackNames);
        this.repoUrls.clear();
        this.repoUrls.addAll(repoUrls);
        this.participants.clear();
        this.participants.addAll(participants);
    }

    public void markInactive() {
        this.status = Status.INACTIVE;
    }

    public void assignExternalProjectId(Long externalProjectId) {
        this.externalProjectId = externalProjectId;
    }

    public void updateDatagsmState(DatagsmProjectStatus datagsmStatus, Integer datagsmEndYear) {
        this.datagsmStatus = datagsmStatus;
        this.datagsmEndYear = datagsmEndYear;
    }

    public void replaceStackNames(Set<String> newStackNames) {
        this.stackNames.clear();
        if (newStackNames != null) {
            this.stackNames.addAll(newStackNames);
        }
    }

    public void replaceRepoUrls(Set<String> newRepoUrls) {
        this.repoUrls.clear();
        if (newRepoUrls != null) {
            this.repoUrls.addAll(newRepoUrls);
        }
    }
}
