package com.thehiddenbrain.interop.memberprofile.service;

import com.thehiddenbrain.interop.memberprofile.api.MemberProfileResponse;
import com.thehiddenbrain.interop.memberprofile.memberdomain.MemberDomainClient;
import com.thehiddenbrain.interop.memberprofile.memberdomain.MemberDomainMember;
import com.thehiddenbrain.interop.memberprofile.permission.ConsentRepository;
import com.thehiddenbrain.interop.memberprofile.permission.PermissionEvaluator;
import com.thehiddenbrain.interop.memberprofile.permission.PermissionResult;
import com.thehiddenbrain.interop.memberprofile.permission.PermissionRule;
import com.thehiddenbrain.interop.memberprofile.permission.PermissionRuleRepository;
import com.thehiddenbrain.interop.memberprofile.permission.ReferenceDataRepository;
import com.thehiddenbrain.interop.memberprofile.permission.RelationshipResolver;
import com.thehiddenbrain.interop.memberprofile.segmentation.SegmentRule;
import com.thehiddenbrain.interop.memberprofile.segmentation.SegmentRuleRepository;
import com.thehiddenbrain.interop.memberprofile.segmentation.SegmentationEvaluator;
import com.thehiddenbrain.interop.memberprofile.segmentation.SegmentationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the profile on demand. Per call:
 * <ol>
 *   <li>one MemberDomain call for the member, their facts and their family roster;</li>
 *   <li>segmentation rules for the member's company, evaluated against the facts;</li>
 *   <li>permission rules for the member's actor relationship, evaluated for self and each family member;</li>
 *   <li>consent on file and reference data.</li>
 * </ol>
 * All rule reads happen inside one read-only transaction so a rule change applied mid-request is
 * seen either entirely or not at all.
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
    private final Clock clock;

    public MemberProfileService(MemberDomainClient memberDomain,
                                SegmentRuleRepository segmentRules,
                                SegmentationEvaluator segmentation,
                                PermissionRuleRepository permissionRules,
                                PermissionEvaluator permissions,
                                ReferenceDataRepository referenceData,
                                ConsentRepository consents,
                                RelationshipResolver relationships,
                                Clock clock) {
        this.memberDomain = memberDomain;
        this.segmentRules = segmentRules;
        this.segmentation = segmentation;
        this.permissionRules = permissionRules;
        this.permissions = permissions;
        this.referenceData = referenceData;
        this.consents = consents;
        this.relationships = relationships;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MemberProfileResponse profile(String memberId, boolean impersonating, boolean explain) {
        MemberDomainMember member = memberDomain.findMember(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));
        String company = requireCompany(member);
        int actorAge = ageOf(member.age(), member.dateOfBirth());

        // Segmentation: logged-in member only.
        SegmentationResult segments = segmentation.evaluate(
                segmentRules.activeSegments(),
                segmentRules.activeRulesFor(company),
                member.attributes());

        // Family permissions: self plus each family member.
        Map<String, String> relationshipCodes = referenceData.relationshipCodes();
        String actor = relationships.actorRelationship(member.relationshipCode(), actorAge, relationshipCodes);
        List<PermissionRule> actorRules = permissionRules.activeRulesForActor(actor);
        Set<String> consentsOnFile = consents.activeConsentsFor(memberId);

        PermissionResult self = permissions.evaluate(actorRules, "Self", actorAge, memberId, consentsOnFile);

        List<MemberProfileResponse.FamilyPermission> family = new ArrayList<>();
        Map<String, List<PermissionResult.RuleTrace>> permissionTrace = new LinkedHashMap<>();
        permissionTrace.put(memberId, self.trace());
        for (MemberDomainMember.FamilyMember fm : member.familyMembers()) {
            if (memberId.equals(fm.memberId())) continue;
            int viewedAge = ageOf(fm.age(), fm.dateOfBirth());
            String viewing = relationships.viewingRelationship(
                    memberId, fm.memberId(), fm.relationshipCode(), viewedAge, relationshipCodes);
            PermissionResult result = permissions.evaluate(actorRules, viewing, viewedAge, fm.memberId(), consentsOnFile);
            family.add(new MemberProfileResponse.FamilyPermission(
                    fm.memberId(), fm.fullName(), fm.relationshipCode(), viewedAge,
                    result.permissions(), result.consentRequired(), result.masked()));
            permissionTrace.put(fm.memberId(), result.trace());
        }

        log.info("profile memberId={} company={} actor={} segmentsTrue={} familyMembers={}",
                memberId, company, actor, segments.flags().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList(),
                family.size());

        return new MemberProfileResponse(
                new MemberProfileResponse.Member(
                        member.memberId(), member.fullName(), member.firstName(), member.lastName(), member.planName(),
                        member.relationshipCode(), member.memberTypeCode(), member.userTypeCode(), company, impersonating,
                        actorAge, member.activePolicy(), member.accountNumber(), member.policyStatus()),
                segments.flags(),
                self.permissions(),
                family,
                referenceData.actionCodes(),
                new MemberProfileResponse.Meta(Instant.now(clock)),
                explain ? new MemberProfileResponse.Explain(actor, member.attributes(), segments.trace(), permissionTrace) : null);
    }

    private static String requireCompany(MemberDomainMember member) {
        String company = member.company() != null ? member.company() : member.memberTypeCode();
        if (company == null || company.isBlank()) {
            throw new IllegalStateException("MemberDomain returned no company for member " + member.memberId());
        }
        return company.trim().toUpperCase();
    }

    private int ageOf(Integer age, LocalDate dateOfBirth) {
        if (age != null) return age;
        if (dateOfBirth != null) return Period.between(dateOfBirth, LocalDate.now(clock)).getYears();
        throw new IllegalStateException("MemberDomain returned neither age nor dateOfBirth");
    }
}
