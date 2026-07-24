package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AiConfigRepository;
import com.cloudmind.demo.repository.CloudFileRepository;
import com.cloudmind.demo.repository.FileVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceOwnershipSecurityTest {
    @Mock
    private CloudFileRepository fileRepository;
    @Mock
    private FileVersionRepository versionRepository;
    @Mock
    private AiConfigRepository aiConfigRepository;

    private FileService fileService;
    private KnowledgeQaService knowledgeQaService;
    private AppUser alice;

    @BeforeEach
    void setUp() {
        fileService = new FileService(
                fileRepository,
                versionRepository,
                null,
                null,
                null,
                null
        );
        knowledgeQaService = new KnowledgeQaService(
                fileRepository,
                aiConfigRepository,
                new ObjectMapper()
        );
        alice = user(1L, "alice");
    }

    @Test
    void fileDetailNeverFallsBackToUnscopedIdLookup() {
        when(fileRepository.findByOwnerIdAndIdAndDeletedFalse(1L, 99L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> fileService.detail(alice, 99L)
        );

        verify(fileRepository).findByOwnerIdAndIdAndDeletedFalse(1L, 99L);
        verify(fileRepository, never()).findById(99L);
    }

    @Test
    void versionListingRequiresOwnershipBeforeReadingVersions() {
        when(fileRepository.findByOwnerIdAndIdAndDeletedFalse(1L, 77L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> fileService.versions(alice, 77L)
        );

        verify(versionRepository, never()).findVersions(1L, 77L);
    }

    @Test
    void knowledgeScopeRejectsFilesOwnedByAnotherUser() {
        when(fileRepository.findByOwnerIdAndIdAndDeletedFalse(1L, 55L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> knowledgeQaService.ask(
                        alice,
                        "summarize",
                        5,
                        "FILE",
                        55L,
                        List.of(55L)
                )
        );

        verify(fileRepository).findByOwnerIdAndIdAndDeletedFalse(1L, 55L);
        verify(fileRepository, never()).findById(55L);
    }

    private AppUser user(Long id, String username) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole("USER");
        user.setEnabled(true);
        user.setPasswordChangedAt(java.time.Instant.now());
        user.setQuotaBytes(1024L);
        return user;
    }

}
