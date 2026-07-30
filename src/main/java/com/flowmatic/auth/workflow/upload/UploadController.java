package com.flowmatic.auth.workflow.upload;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Accepts a direct CSV upload and returns an {@code uploadId} that a DATA_SOURCE node's config can
 * reference (e.g. {@code {"uploadId": "..."}}).
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

  private final UploadStorage storage;

  public UploadController(UploadStorage storage) {
    this.storage = storage;
  }

  @PostMapping
  public ResponseEntity<?> upload(
      @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "file is required (multipart form-data field 'file')"));
    }
    String uploadId = storage.store(file.getOriginalFilename(), file.getBytes());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "uploadId", uploadId,
                "filename", String.valueOf(file.getOriginalFilename()),
                "size", file.getSize()));
  }
}
