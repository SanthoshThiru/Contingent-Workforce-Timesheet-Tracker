package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.dto.notification.NotificationResponse;
import com.contingentworkforce.entity.Notification;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.enums.NotificationType;
import com.contingentworkforce.exception.ResourceNotFoundException;
import com.contingentworkforce.repository.NotificationRepository;
import com.contingentworkforce.repository.UserRepository;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void createNotification(User user, String title, String message, NotificationType type) {
        if (user == null) return;
        Notification notification = Notification.builder()
                .user(user)
                .title(title)
                .message(message)
                .type(type)
                .isRead(false)
                .build();
        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void notifyUser(UUID userId, String title, String message, NotificationType type) {
        userRepository.findById(userId).ifPresent(user -> createNotification(user, title, message, type));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getCurrentUserNotifications(Pageable pageable) {
        UUID userId = SecurityUtils.getCurrentUserId();
        Page<Notification> page = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return PageResponse.from(page.map(this::mapToNotificationResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getCurrentUserNotificationsList() {
        UUID userId = SecurityUtils.getCurrentUserId();
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::mapToNotificationResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public NotificationResponse markAsRead(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + id));

        notification.setIsRead(true);
        Notification updated = notificationRepository.save(notification);
        return mapToNotificationResponse(updated);
    }

    @Override
    @Transactional
    public void markAllAsRead() {
        UUID userId = SecurityUtils.getCurrentUserId();
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (Notification n : notifications) {
            n.setIsRead(true);
        }
        notificationRepository.saveAll(notifications);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount() {
        UUID userId = SecurityUtils.getCurrentUserId();
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    public NotificationResponse mapToNotificationResponse(Notification notification) {
        if (notification == null) return null;
        return NotificationResponse.builder()
                .id(notification.getId())
                .userId(notification.getUser().getId())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .type(notification.getType())
                .isRead(Boolean.TRUE.equals(notification.getIsRead()))
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
