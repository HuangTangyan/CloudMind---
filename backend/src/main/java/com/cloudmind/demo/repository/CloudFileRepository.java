package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.CloudFile;
import com.cloudmind.demo.entity.FileKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CloudFileRepository extends JpaRepository<CloudFile, Long> {
    List<CloudFile> findByOwnerId(Long ownerId);
    List<CloudFile> findByOwnerIdAndParentIdIsNullAndDeletedFalseOrderByKindAscNameAsc(Long ownerId);
    List<CloudFile> findByOwnerIdAndParentIdAndDeletedFalseOrderByKindAscNameAsc(Long ownerId, Long parentId);
    List<CloudFile> findByOwnerIdAndParentIdIsNullAndDeletedFalse(Long ownerId);
    List<CloudFile> findByOwnerIdAndParentIdAndDeletedFalse(Long ownerId, Long parentId);
    List<CloudFile> findByOwnerIdAndParentIdAndDeletedTrue(Long ownerId, Long parentId);
    List<CloudFile> findByOwnerIdAndDeletedFalseOrderByKindAscNameAsc(Long ownerId);
    Optional<CloudFile> findByOwnerIdAndIdAndDeletedFalse(Long ownerId, Long id);
    Optional<CloudFile> findByOwnerIdAndId(Long ownerId, Long id);
    List<CloudFile> findByOwnerIdAndDeletedTrueOrderByDeletedAtDesc(Long ownerId);
    List<CloudFile> findByOwnerIdAndKindAndDeletedFalse(Long ownerId, FileKind kind);
    List<CloudFile> findByOwnerIdAndParentIdIsNullAndDeletedFalseOrderByNameAsc(Long ownerId);
    List<CloudFile> findByOwnerIdAndParentIdAndDeletedFalseOrderByNameAsc(Long ownerId, Long parentId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from CloudFile f where f.owner.id = :ownerId and f.kind = com.cloudmind.demo.entity.FileKind.FILE and f.deleted = false")
    long sumUsedBytes(@Param("ownerId") Long ownerId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from CloudFile f where f.kind = com.cloudmind.demo.entity.FileKind.FILE and f.deleted = false")
    long sumAllActiveFileBytes();

    @Query("select coalesce(sum(f.sizeBytes), 0) from CloudFile f where f.kind = com.cloudmind.demo.entity.FileKind.FILE and f.deleted = true")
    long sumAllDeletedFileBytes();

    long countByKindAndDeletedFalse(FileKind kind);

    long countByKindAndDeletedTrue(FileKind kind);

    @Query(value = """
            select * from cloud_file f
            where f.owner_id = :ownerId
              and f.deleted = 0
              and (
                    lower(f.name) like lower(concat('%', :keyword, '%'))
                 or lower(coalesce(f.summary, '')) like lower(concat('%', :keyword, '%'))
                 or lower(coalesce(f.tags, '')) like lower(concat('%', :keyword, '%'))
                 or coalesce(f.extracted_text, '') like concat('%', :keyword, '%')
              )
            order by case when f.kind = 'FOLDER' then 0 else 1 end, f.updated_at desc
            """, nativeQuery = true)
    List<CloudFile> search(@Param("ownerId") Long ownerId, @Param("keyword") String keyword);
}
