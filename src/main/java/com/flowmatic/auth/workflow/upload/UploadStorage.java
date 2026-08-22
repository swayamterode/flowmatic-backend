package com.flowmatic.auth.workflow.upload;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stores uploaded source files (currently CSVs) and hands back an opaque reference id that a
 * DATA_SOURCE node's config can point to. Kept behind an interface so the backing store can be
 * swapped without touching executors.
 */
public interface UploadStorage {

  /** Persist {@code content} and return a reference id. */
  String store(String originalFilename, byte[] content) throws IOException;

  /** Open a previously stored upload for reading. */
  InputStream open(String uploadId) throws IOException;

  boolean exists(String uploadId);
}
