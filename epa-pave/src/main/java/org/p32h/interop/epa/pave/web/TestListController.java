package org.p32h.interop.epa.pave.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.p32h.interop.epa.pave.service.lists.TestListService;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ImportRequest;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ImportResult;
import org.p32h.interop.epa.pave.service.lists.TestListViews.MemberList;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ProviderList;
import org.p32h.interop.epa.pave.service.lists.TestMember;
import org.p32h.interop.epa.pave.service.lists.TestMemberInput;
import org.p32h.interop.epa.pave.service.lists.TestProvider;
import org.p32h.interop.epa.pave.service.lists.TestProviderInput;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved test members and providers. A CSV file is read in the browser and posted as JSON text, so
 * no upload reaches the multipart path; the template is a download of placeholders only.
 */
@RestController
@RequestMapping("/api/lists")
public class TestListController {

    private final TestListService lists;

    public TestListController(TestListService lists) {
        this.lists = lists;
    }

    @GetMapping("/members")
    public MemberList members() {
        return lists.members();
    }

    @PostMapping("/members")
    public TestMember addMember(@RequestBody TestMemberInput member) {
        return lists.addMember(member);
    }

    @PutMapping("/members/{id}")
    public TestMember replaceMember(@PathVariable("id") String id, @RequestBody TestMemberInput member) {
        return lists.replaceMember(id, member);
    }

    @DeleteMapping("/members/{id}")
    public Map<String, Boolean> removeMember(@PathVariable("id") String id) {
        return Map.of("removed", lists.removeMember(id));
    }

    @GetMapping("/members/template.csv")
    public ResponseEntity<String> memberTemplate() {
        return csv(TestListService.memberTemplate(), "test-members-template.csv");
    }

    @PostMapping("/members/import")
    public ImportResult importMembers(@RequestBody ImportRequest request) {
        return lists.importMembers(request);
    }

    @GetMapping("/providers")
    public ProviderList providers() {
        return lists.providers();
    }

    @PostMapping("/providers")
    public TestProvider addProvider(@RequestBody TestProviderInput provider) {
        return lists.addProvider(provider);
    }

    @PutMapping("/providers/{id}")
    public TestProvider replaceProvider(@PathVariable("id") String id, @RequestBody TestProviderInput provider) {
        return lists.replaceProvider(id, provider);
    }

    @DeleteMapping("/providers/{id}")
    public Map<String, Boolean> removeProvider(@PathVariable("id") String id) {
        return Map.of("removed", lists.removeProvider(id));
    }

    @GetMapping("/providers/template.csv")
    public ResponseEntity<String> providerTemplate() {
        return csv(TestListService.providerTemplate(), "test-providers-template.csv");
    }

    @PostMapping("/providers/import")
    public ImportResult importProviders(@RequestBody ImportRequest request) {
        return lists.importProviders(request);
    }

    private static ResponseEntity<String> csv(String text, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        return new ResponseEntity<>(text, headers, HttpStatus.OK);
    }
}
