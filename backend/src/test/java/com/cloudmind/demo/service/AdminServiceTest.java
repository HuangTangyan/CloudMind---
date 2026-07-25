package com.cloudmind.demo.service;

import com.cloudmind.demo.dto.BatchCreateUsersRequest;
import com.cloudmind.demo.dto.BatchDeleteUsersRequest;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AppUserRepository;
import com.cloudmind.demo.repository.CloudFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private CloudFileRepository fileRepository;
    @Mock
    private AuthService authService;
    @Mock
    private FileService fileService;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(
                userRepository,
                fileRepository,
                authService,
                fileService
        );
    }

    @Test
    void batchCreateReturnsTemporaryCredentialsOnlyOnce() {
        AppUser admin = user(1L, "admin", "ADMIN");
        AppUser first = user(2L, "campus_001", "USER");
        AppUser second = user(3L, "campus_002", "USER");
        when(authService.createTemporaryUserByAdmin(
                eq("campus_001"),
                eq("USER"),
                eq(10L)
        )).thenReturn(new AuthService.CreatedUserCredential(first, "CM-first"));
        when(authService.createTemporaryUserByAdmin(
                eq("campus_002"),
                eq("USER"),
                eq(10L)
        )).thenReturn(new AuthService.CreatedUserCredential(second, "CM-second"));

        BatchCreateUsersRequest request = new BatchCreateUsersRequest();
        request.setUsernames(List.of("campus_001", "campus_002"));
        request.setRole("USER");
        request.setQuotaBytes(10L);
        request.setCurrentPassword("admin-password");

        Map<String, Object> result = adminService.createUsersBatch(admin, request);

        assertEquals(2, result.get("createdCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> credentials =
                (List<Map<String, Object>>) result.get("credentials");
        assertEquals("CM-first", credentials.get(0).get("temporaryPassword"));
        assertEquals("CM-second", credentials.get(1).get("temporaryPassword"));
        verify(authService).reauthenticateAdmin(admin, "admin-password");
    }

    @Test
    void batchCreateRejectsDuplicateTemporaryUsernamesBeforeWriting() {
        AppUser admin = user(1L, "admin", "ADMIN");
        BatchCreateUsersRequest request = new BatchCreateUsersRequest();
        request.setUsernames(List.of("Campus_001", "campus_001"));
        request.setRole("USER");
        request.setQuotaBytes(10L);
        request.setCurrentPassword("admin-password");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.createUsersBatch(admin, request)
        );

        assertEquals("批次中存在重复的临时用户名", error.getMessage());
        verify(authService).reauthenticateAdmin(admin, "admin-password");
    }

    @Test
    void batchDeletePurgesFilesAndSessionsBeforeDeletingUsers() {
        AppUser admin = user(1L, "admin", "ADMIN");
        AppUser first = user(2L, "student_001", "USER");
        AppUser second = user(3L, "student_002", "VIP");
        when(userRepository.findAllById(any())).thenReturn(List.of(first, second));
        when(authService.isAdmin(first)).thenReturn(false);
        when(authService.isAdmin(second)).thenReturn(false);

        BatchDeleteUsersRequest request = new BatchDeleteUsersRequest();
        request.setUserIds(List.of(2L, 3L));
        request.setCurrentPassword("admin-password");
        request.setConfirmText("DELETE");

        Map<String, Object> result = adminService.deleteUsersBatch(admin, request);

        assertEquals(2, result.get("deletedCount"));
        assertEquals(
                List.of("student_001", "student_002"),
                result.get("deletedUsernames")
        );
        verify(authService).reauthenticateAdmin(admin, "admin-password");
        verify(fileService).adminPurgeAllFilesOfUser(2L);
        verify(fileService).adminPurgeAllFilesOfUser(3L);
        verify(authService).deleteTokensForUser(2L);
        verify(authService).deleteTokensForUser(3L);
        verify(userRepository).deleteAll(List.of(first, second));
    }

    @Test
    void batchDeleteRefusesAdministratorAccounts() {
        AppUser admin = user(1L, "admin", "ADMIN");
        AppUser anotherAdmin = user(2L, "another_admin", "ADMIN");
        when(userRepository.findAllById(any())).thenReturn(List.of(anotherAdmin));
        when(authService.isAdmin(anotherAdmin)).thenReturn(true);

        BatchDeleteUsersRequest request = new BatchDeleteUsersRequest();
        request.setUserIds(List.of(2L));
        request.setCurrentPassword("admin-password");
        request.setConfirmText("DELETE");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.deleteUsersBatch(admin, request)
        );

        assertEquals("为避免锁死后台，管理员账号不能批量删除", error.getMessage());
    }

    private AppUser user(Long id, String username, String role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setEnabled(true);
        user.setQuotaBytes(10L);
        return user;
    }
}
