package com.flowmatic.auth.workflow.upload;

import com.cloudinary.Cloudinary;
import com.cloudinary.api.exceptions.NotFound;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cloudinary-backed {@link UploadStorage}. Files are stored as "raw" resources under the
 * "authenticated" delivery type (not publicly reachable by a bare URL); {@link #open} generates a
 * fresh signed URL for every read. Uploads are never deleted — retention is intentionally
 * unbounded.
 */
@Component
public class CloudinaryUploadStorage implements UploadStorage {

  private static final String RESOURCE_TYPE = "raw";
  private static final String DELIVERY_TYPE = "authenticated";

  private final Cloudinary cloudinary;

  public CloudinaryUploadStorage(@Value("${cloudinary.url}") String cloudinaryUrl) {
    this.cloudinary = new Cloudinary(cloudinaryUrl);
  }

  @Override
  public String store(String originalFilename, byte[] content) throws IOException {
    String uploadId = UUID.randomUUID().toString();
    cloudinary
        .uploader()
        .upload(
            content,
            ObjectUtils.asMap(
                "resource_type",
                RESOURCE_TYPE,
                "type",
                DELIVERY_TYPE,
                "public_id",
                publicIdFor(uploadId),
                "overwrite",
                false));
    return uploadId;
  }

  @Override
  public InputStream open(String uploadId) throws IOException {
    try {
      return new URI(signedUrl(uploadId)).toURL().openStream();
    } catch (URISyntaxException e) {
      throw new IOException("Malformed Cloudinary URL for uploadId=" + uploadId, e);
    }
  }

  @Override
  public boolean exists(String uploadId) {
    try {
      cloudinary
          .api()
          .resource(
              publicIdFor(uploadId),
              ObjectUtils.asMap("resource_type", RESOURCE_TYPE, "type", DELIVERY_TYPE));
      return true;
    } catch (NotFound e) {
      return false;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to check Cloudinary upload " + uploadId, e);
    }
  }

  private String signedUrl(String uploadId) {
    return cloudinary
        .url()
        .resourceType(RESOURCE_TYPE)
        .type(DELIVERY_TYPE)
        .signed(true)
        .generate(publicIdFor(uploadId));
  }

  /** Raw resources require the extension as part of the public_id itself (unlike images/video). */
  private String publicIdFor(String uploadId) {
    return uploadId + ".csv";
  }
}
