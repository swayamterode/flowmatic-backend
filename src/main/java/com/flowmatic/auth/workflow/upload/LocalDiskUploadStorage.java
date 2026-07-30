package com.flowmatic.auth.workflow.upload;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Simple local-disk {@link UploadStorage}. Each upload is written to {baseDir}/{uuid}.csv. */
@Component
public class LocalDiskUploadStorage implements UploadStorage {

  private final Path baseDir;

  public LocalDiskUploadStorage(@Value("${app.uploads.dir}") String baseDir) {
    this.baseDir = Path.of(baseDir);
  }

  @PostConstruct
  void ensureDir() throws IOException {
    Files.createDirectories(baseDir);
  }

  @Override
  public String store(String originalFilename, byte[] content) throws IOException {
    Files.createDirectories(baseDir);
    String uploadId = UUID.randomUUID().toString();
    Files.write(pathFor(uploadId), content);
    return uploadId;
  }

  @Override
  public InputStream open(String uploadId) throws IOException {
    return Files.newInputStream(pathFor(uploadId));
  }

  @Override
  public boolean exists(String uploadId) {
    return Files.exists(pathFor(uploadId));
  }

  private Path pathFor(String uploadId) {
    // uploadId is a server-generated UUID, so it is safe to use directly as a filename stem.
    return baseDir.resolve(uploadId + ".csv");
  }
}
