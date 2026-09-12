package com.intellidesk.infrastructure.storage;

import com.intellidesk.infrastructure.config.MinioProperties;
import io.minio.*;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioObjectStorageService(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public String putObject(String bucketName, String objectKey, InputStream inputStream,
                            String contentType, long size) throws StorageException {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, size, -1)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build()
            );
            return objectKey;
        } catch (InsufficientDataException | InternalException | InvalidKeyException | NoSuchAlgorithmException |
                 XmlParserException e) {
            throw new StorageException("MinIO internal or configuration error", false, e);
        } catch (ErrorResponseException e) {
            log.warn("MinIO error response while putting object: {}", e.getMessage());
            throw new StorageException("MinIO rejected the object", false, e);
        } catch (InvalidResponseException | IOException e) {
            log.warn("MinIO network/IO error while putting object: {}", e.getMessage());
            throw new StorageException("MinIO network/IO error", true, e);
        } catch (ServerException e) {
            log.warn("MinIO server error while putting object: {}", e.getMessage());
            throw new StorageException("MinIO server error", true, e);
        }
    }

    @Override
    public InputStream getObject(String bucketName, String objectKey) throws StorageException {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
        } catch (InsufficientDataException | InternalException | InvalidKeyException | NoSuchAlgorithmException |
                 XmlParserException e) {
            throw new StorageException("MinIO internal or configuration error", false, e);
        } catch (ErrorResponseException e) {
            log.warn("MinIO error response while getting object: {}", e.getMessage());
            throw new StorageException("MinIO rejected the request", false, e);
        } catch (InvalidResponseException | IOException e) {
            log.warn("MinIO network/IO error while getting object: {}", e.getMessage());
            throw new StorageException("MinIO network/IO error", true, e);
        } catch (ServerException e) {
            log.warn("MinIO server error while getting object: {}", e.getMessage());
            throw new StorageException("MinIO server error", true, e);
        }
    }

    @Override
    public void deleteObject(String bucketName, String objectKey) throws StorageException {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
        } catch (ErrorResponseException e) {
            if (isNoSuchKey(e)) {
                log.debug("Object already absent in MinIO: {}/{}", bucketName, objectKey);
                return;
            }
            log.warn("MinIO error response while deleting object: {}", e.getMessage());
            throw new StorageException("MinIO rejected the delete request", false, e);
        } catch (InsufficientDataException | InternalException | InvalidKeyException | NoSuchAlgorithmException |
                 XmlParserException e) {
            throw new StorageException("MinIO internal or configuration error", false, e);
        } catch (InvalidResponseException | IOException e) {
            log.warn("MinIO network/IO error while deleting object: {}", e.getMessage());
            throw new StorageException("MinIO network/IO error", true, e);
        } catch (ServerException e) {
            log.warn("MinIO server error while deleting object: {}", e.getMessage());
            throw new StorageException("MinIO server error", true, e);
        }
    }

    @Override
    public boolean objectExists(String bucketName, String objectKey) throws StorageException {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if (isNoSuchKey(e)) {
                return false;
            }
            log.warn("MinIO error response while stating object: {}", e.getMessage());
            throw new StorageException("MinIO rejected the stat request", false, e);
        } catch (InsufficientDataException | InternalException | InvalidKeyException | NoSuchAlgorithmException |
                 XmlParserException e) {
            throw new StorageException("MinIO internal or configuration error", false, e);
        } catch (InvalidResponseException | IOException e) {
            log.warn("MinIO network/IO error while stating object: {}", e.getMessage());
            throw new StorageException("MinIO network/IO error", true, e);
        } catch (ServerException e) {
            log.warn("MinIO server error while stating object: {}", e.getMessage());
            throw new StorageException("MinIO server error", true, e);
        }
    }

    private boolean isNoSuchKey(ErrorResponseException e) {
        return e.errorResponse() != null
                && "NoSuchKey".equals(e.errorResponse().code());
    }
}
