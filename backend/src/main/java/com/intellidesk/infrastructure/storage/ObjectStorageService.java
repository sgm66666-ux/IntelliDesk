package com.intellidesk.infrastructure.storage;

import java.io.InputStream;

public interface ObjectStorageService {

    String putObject(String bucketName, String objectKey, InputStream inputStream,
                     String contentType, long size) throws StorageException;

    InputStream getObject(String bucketName, String objectKey) throws StorageException;

    void deleteObject(String bucketName, String objectKey) throws StorageException;

    boolean objectExists(String bucketName, String objectKey) throws StorageException;
}
