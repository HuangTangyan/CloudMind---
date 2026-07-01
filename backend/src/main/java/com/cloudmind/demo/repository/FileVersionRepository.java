package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.FileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {
    @Query("select v from FileVersion v where v.file.owner.id = :ownerId and v.file.id = :fileId order by v.createdAt desc")
    List<FileVersion> findVersions(@Param("ownerId") Long ownerId, @Param("fileId") Long fileId);

    @Query("select v from FileVersion v where v.file.owner.id = :ownerId and v.file.id = :fileId and v.id = :versionId")
    Optional<FileVersion> findVersion(@Param("ownerId") Long ownerId, @Param("fileId") Long fileId, @Param("versionId") Long versionId);

    long countByFileId(Long fileId);
    List<FileVersion> findByFileId(Long fileId);
    void deleteByFileId(Long fileId);
}
