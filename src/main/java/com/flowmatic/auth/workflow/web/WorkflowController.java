package com.flowmatic.auth.workflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmatic.auth.entity.User;
import com.flowmatic.auth.repository.UserRepository;
import com.flowmatic.auth.workflow.entity.Workflow;
import com.flowmatic.auth.workflow.repository.WorkflowRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** CRUD for workflows owned by the authenticated user. */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WorkflowRepository workflowRepository;
  private final UserRepository userRepository;
  private final CurrentUser currentUser;

  public WorkflowController(
      WorkflowRepository workflowRepository,
      UserRepository userRepository,
      CurrentUser currentUser) {
    this.workflowRepository = workflowRepository;
    this.userRepository = userRepository;
    this.currentUser = currentUser;
  }

  @PostMapping
  public ResponseEntity<?> create(
      @RequestBody CreateWorkflowRequest request, Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    if (request.name() == null || request.name().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    }
    if (request.graph() == null) {
      return ResponseEntity.badRequest().body(Map.of("error", "graph is required"));
    }
    User owner = userRepository.getReferenceById(userId);
    Workflow saved =
        workflowRepository.save(
            Workflow.builder()
                .user(owner)
                .name(request.name())
                .graphJson(toJson(request.graph()))
                .build());
    return ResponseEntity.status(HttpStatus.CREATED).body(summary(saved));
  }

  @GetMapping
  public ResponseEntity<?> list(Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    List<Map<String, Object>> workflows =
        workflowRepository.findByUser_Id(userId).stream().map(this::summary).toList();
    return ResponseEntity.ok(workflows);
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> get(@PathVariable Long id, Authentication authentication) {
    Workflow workflow = requireOwned(id, authentication);
    return ResponseEntity.ok(detail(workflow));
  }

  @PutMapping("/{id}")
  public ResponseEntity<?> update(
      @PathVariable Long id,
      @RequestBody CreateWorkflowRequest request,
      Authentication authentication) {
    Workflow workflow = requireOwned(id, authentication);
    if (request.name() != null && !request.name().isBlank()) {
      workflow.setName(request.name());
    }
    if (request.graph() != null) {
      workflow.setGraphJson(toJson(request.graph()));
    }
    return ResponseEntity.ok(detail(workflowRepository.save(workflow)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
    Workflow workflow = requireOwned(id, authentication);
    workflowRepository.delete(workflow);
    return ResponseEntity.noContent().build();
  }

  /**
   * Loads a workflow and asserts the caller owns it (404 otherwise, to avoid leaking existence).
   */
  private Workflow requireOwned(Long id, Authentication authentication) {
    Long userId = currentUser.requireUserId(authentication);
    Workflow workflow =
        workflowRepository
            .findById(id)
            .filter(w -> w.getUser().getId().equals(userId))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
    return workflow;
  }

  private Map<String, Object> summary(Workflow w) {
    return Map.of(
        "id", w.getId(),
        "name", w.getName(),
        "createdAt", String.valueOf(w.getCreatedAt()),
        "updatedAt", String.valueOf(w.getUpdatedAt()));
  }

  private Map<String, Object> detail(Workflow w) {
    return Map.of(
        "id", w.getId(),
        "name", w.getName(),
        "graph", fromJson(w.getGraphJson()),
        "createdAt", String.valueOf(w.getCreatedAt()),
        "updatedAt", String.valueOf(w.getUpdatedAt()));
  }

  private static String toJson(Map<String, Object> graph) {
    try {
      return MAPPER.writeValueAsString(graph);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid graph JSON");
    }
  }

  private static Object fromJson(String json) {
    try {
      return MAPPER.readValue(json, Map.class);
    } catch (Exception e) {
      return json;
    }
  }
}
