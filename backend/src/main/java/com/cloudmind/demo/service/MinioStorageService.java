package com.cloudmind.demo.service;

import com.cloudmind.demo.config.MinioProperties;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Service
public class MinioStorageService {
    private final MinioProperties properties;
    private final MinioClient minioClient;

    public MinioStorageService(MinioProperties properties) {
        this.properties = properties;
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @PostConstruct
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.getBucket())
                        .build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 连接失败，请确认 Docker 中的 MinIO 已启动，endpoint/accessKey/secretKey/bucket 配置正确", e);
        }
    }

    public void upload(String objectName, MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("上传到 MinIO 失败：" + e.getMessage(), e);
        }
    }

    public InputStream stream(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("从 MinIO 读取失败：" + e.getMessage(), e);
        }
    }

    public InputStreamResource download(String objectName) {
        return new InputStreamResource(stream(objectName));
    }

    public void copy(String sourceObjectName, String targetObjectName) {
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(targetObjectName)
                    .source(CopySource.builder()
                            .bucket(properties.getBucket())
                            .object(sourceObjectName)
                            .build())
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("复制 MinIO 文件失败：" + e.getMessage(), e);
        }
    }

    public void delete(String objectName) {
        if (objectName == null || objectName.isBlank()) return;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build());
        } catch (ErrorResponseException ignored) {
            // 对象已不存在时，不影响业务。
        } catch (Exception e) {
            throw new IllegalStateException("删除 MinIO 文件失败：" + e.getMessage(), e);
        }
    }
}
