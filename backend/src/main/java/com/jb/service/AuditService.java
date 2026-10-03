package com.jb.service;

import com.jb.domain.AuditLog;
import com.jb.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String actorLabel, Long actorId, String role, Map<String, Object> details, String ip, String requestId) {
        AuditLog entry = AuditLog.builder()
                .action(action)
                .actorLabel(actorLabel)
                .actorId(actorId)
                .role(role)
                .detailsJson(toJson(details))
                .ip(ip)
                .requestId(requestId)
                .build();
        auditLogRepository.save(entry);
    }

    @Transactional
    public void recordOutsideTx(String action, String actorLabel, Long actorId, String role, Map<String, Object> details, String ip, String requestId) {
        record(action, actorLabel, actorId, role, details, ip, requestId);
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (Exception e) {
            return "{\"error\":\"serialize\"}";
        }
    }
}
