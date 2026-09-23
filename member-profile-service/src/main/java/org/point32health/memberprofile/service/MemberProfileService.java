package org.point32health.memberprofile.service;

import org.point32health.memberprofile.api.MemberProfileRequest;
import org.point32health.memberprofile.api.MemberProfileResponse;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.memberdomain.MemberDomainClient;
import org.point32health.memberprofile.memberdomain.MemberDomainMember;
import org.point32health.memberprofile.permission.ActorRelationship;
import org.point32health.memberprofile.permission.ConsentRepository;
import org.point32health.memberprofile.permission.FamilyRelationship;
import org.point32health.memberprofile.permission.PermissionEvaluator;
import org.point32health.memberprofile.permission.PermissionResult;
import org.point32health.memberprofile.permission.PermissionRule;
import org.point32health.memberprofile.permission.PermissionRuleRepository;
import org.point32health.memberprofile.permission.ReferenceData;
import org.point32health.memberprofile.permission.ReferenceDataRepository;
import org.point32health.memberprofile.permission.RelationshipResolver;
import org.point32health.memberprofile.permission.ViewingRelationship;
import org.point32health.memberprofile.segmentation.MemberFacts;
import org.point32health.memberprofile.segmentation.SegmentRule;
import org.point32health.memberprofile.segmentation.SegmentRuleRepository;
import org.point32health.memberprofile.segmentation.SegmentationEvaluator;
import org.point32health.memberprofile.segmentation.SegmentationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Builds the profile on demand, once per login. Nothing is cached; every call reads the current rules.
 * <p>
 * The work is arranged so the database is never on the critical path behind MemberDomain:
 * <ol>
 *   <li><b>Wave 1</b>, concurrently: the MemberDomain call (member, rule facts, family roster); the member's
 *       consents on file (needs only the member id); the two reference tables (need nothing).</li>
 *   <li><b>Wave 2</b>, concurrently once the member is known: the segmentation rules for the member's company
 *       and the permission rules for the member's actor relationship and the viewing relationships the
 *       family contains.</li>
 *   <li>Evaluate in memory: segmentation once, permissions for self and for each family member.</li>
 * </ol>
 * Latency is therefore the MemberDomain call plus one round trip of two small indexed queries. The
 * fan-out runs on Boot's application task executor, which is virtual threads on JDK 21+.
 */
@Service
public class MemberProfileService {

    private static final Logger log = LoggerFactory.getLogger(MemberProfileService.class);

    private final MemberDomainClient memberDomain;
    private final SegmentRuleRepository segmentRules;
    private final SegmentationEvaluator segmentation;
    private final PermissionRuleRepository permissionRules;
    private final PermissionEvaluator permissions;
    private final ReferenceDataRepository referenceData;
    private final ConsentRepository consents;
    private final RelationshipResolver relationships;
    private final AsyncTaskExecutor executor;
    private final Clock clock;

    public MemberProfileService(MemberDomainClient memberDomain,
                                SegmentRuleRepository segmentRules,
                                SegmentationEvaluator segmentation,
                                PermissionRuleRepository permissionRules,
                                PermissionEvaluator permissions,
                                ReferenceDataRepository referenceData,
                                ConsentRepository consents,
                                RelationshipResolver relationships,
                                @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor,
                                Clock clock) {
        this.memberDomain = memberDomain;
        this.segmentRules = segmentRules;
        this.segmentation = segmentation;
        this.permissionRules = permissionRules;
        this.permissions = permissions;
        this.referenceData = referenceData;
        this.consents = consents;
        this.relationships = relationships;
        this.executor = executor;
        this.clock = clock;
    }

    public MemberProfileResponse profile(MemberProfileRequest request) {
        final String memberId = request.memberId();
        final boolean explain = request.isExplain();
        final long start = System.nanoTime();

        // Wave 1: everything that does not need the member record.
        CompletableFuture<MemberDomainMember> memberFuture = CompletableFuture.supplyAsync(
                () -> memberDomain.findMember(memberId).orElseThrow(() -> MemberProfileException.memberNotFound(memberId)), executor);
        CompletableFuture<Set<String>> consentsFuture = CompletableFuture.supplyAsync(() -> consents.activeConsentsFor(memberId), executor);
        CompletableFuture<ReferenceData> referenceFuture = CompletableFuture.supplyAsync(referenceData::load, executor);

        MemberDomainMember member = join(memberFuture);
        final long memberDomainNanos = System.nanoTime() - start;

        String company = companyOf(member);
        int actorAge = ageOf(memberId, member.age(), member.dateOfBirth());
        ReferenceData reference = join(referenceFuture);
        FamilyRelationship actorBase = reference.relationship(member.relationshipCode())
                .orElseThrow(() -> MemberProfileException.memberDataIncomplete(memberId,
                        "relationship code '" + member.relationshipCode() + "' is not in family_permission.relationship_code"));
        ActorRelationship actor = relationships.actor(actorBase, actorAge);

        // Resolve every family member once: viewing relationship and age drive both the query and the evaluation.
        List<ViewedMember> family = new ArrayList<>(member.familyMembers().size());
        Set<ViewingRelationship> viewings = EnumSet.of(ViewingRelationship.SELF);
        for (MemberDomainMember.FamilyMember fm : member.familyMembers()) {
            if (memberId.equals(fm.memberId())) continue;                 // the roster may list the member themselves
            int age = ageOf(fm.memberId(), fm.age(), fm.dateOfBirth());
            ViewingRelationship viewing = relationships.viewing(reference.relationship(fm.relationshipCode()).orElse(null), age);
            viewings.add(viewing);
            family.add(new ViewedMember(fm, viewing, age));
        }

        // Wave 2: the two rule reads, in parallel.
        final long rulesStart = System.nanoTime();
        CompletableFuture<List<SegmentRule>> segmentRulesFuture = CompletableFuture.supplyAsync(() -> segmentRules.activeRulesFor(company), executor);
        CompletableFuture<List<PermissionRule>> permissionRulesFuture = CompletableFuture.supplyAsync(() -> permissionRules.activeRulesFor(actor, viewings), executor);
        List<SegmentRule> segmentRuleRows = join(segmentRulesFuture);
        List<PermissionRule> permissionRuleRows = join(permissionRulesFuture);
        Set<String> consentsOnFile = join(consentsFuture);
        final long rulesNanos = System.nanoTime() - rulesStart;

        // Evaluate.
        SegmentationResult segments = segmentation.evaluate(segmentRuleRows, MemberFacts.of(member.attributes()), explain);
        PermissionEvaluator.Index index = permissions.index(permissionRuleRows);
        PermissionResult self = index.evaluate(ViewingRelationship.SELF, actorAge, memberId, consentsOnFile, explain);

        List<MemberProfileResponse.FamilyPermission> familyPermissions = new ArrayList<>(family.size());
        Map<String, List<PermissionResult.RuleTrace>> permissionTrace = explain ? new LinkedHashMap<>() : null;
        if (explain) permissionTrace.put(memberId, self.trace());
        for (ViewedMember viewed : family) {
            PermissionResult result = index.evaluate(viewed.viewing(), viewed.age(), viewed.member().memberId(), consentsOnFile, explain);
            familyPermissions.add(new MemberProfileResponse.FamilyPermission(
                    viewed.member().memberId(), viewed.member().fullName(), viewed.member().relationshipCode(), viewed.age(),
                    result.permissions(), result.consentRequired(), result.masked()));
            if (explain) permissionTrace.put(viewed.member().memberId(), result.trace());
        }

        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (log.isInfoEnabled()) {
            log.info("profile memberId={} company={} actor={} segmentsTrue={} family={} memberDomainMs={} rulesMs={} totalMs={}",
                    memberId, company, actor, trueSegments(segments), family.size(),
                    TimeUnit.NANOSECONDS.toMillis(memberDomainNanos), TimeUnit.NANOSECONDS.toMillis(rulesNanos), totalMs);
        }

        MemberProfileResponse.Member out = new MemberProfileResponse.Member(
                member.memberId(), member.fullName(), member.firstName(), member.lastName(), member.planName(),
                member.relationshipCode(), member.memberTypeCode(), member.userTypeCode(), request.isImpersonating(),
                actorAge, member.activePolicy(), member.accountNumber(), member.policyStatus(),
                segments.flags(), self.permissions(), familyPermissions);
        MemberProfileResponse.Explain explainOut = explain
                ? new MemberProfileResponse.Explain(actor.label(), member.attributes(), segments.trace(), permissionTrace,
                        new MemberProfileResponse.Timings(TimeUnit.NANOSECONDS.toMillis(memberDomainNanos),
                                TimeUnit.NANOSECONDS.toMillis(rulesNanos), totalMs))
                : null;
        return new MemberProfileResponse(out, reference.actionCodes(), explainOut);
    }

    private record ViewedMember(MemberDomainMember.FamilyMember member, ViewingRelationship viewing, int age) {
    }

    /** The company whose rules apply: {@code memberTypeCode} is HPHC or THP in the member payload. */
    private static String companyOf(MemberDomainMember member) {
        String code = member.memberTypeCode();
        if (code == null || code.isBlank()) {
            throw MemberProfileException.memberDataIncomplete(member.memberId(), "memberTypeCode (company) is missing");
        }
        return code.trim().toUpperCase();
    }

    private int ageOf(String memberId, Integer age, LocalDate dateOfBirth) {
        if (age != null) return age;
        if (dateOfBirth != null) return Period.between(dateOfBirth, LocalDate.now(clock)).getYears();
        throw MemberProfileException.memberDataIncomplete(memberId, "neither age nor dateOfBirth is present");
    }

    private static List<String> trueSegments(SegmentationResult segments) {
        return segments.flags().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    }

    /** Waits for a wave-1 or wave-2 task and re-throws its failure as the original exception, not the executor's wrapper. */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MemberProfileException mpe) throw mpe;
            if (cause instanceof RuntimeException re) throw re;
            throw new MemberProfileException(ErrorCode.INTERNAL_ERROR, "profile evaluation failed: " + cause.getMessage(), cause);
        }
    }
}
