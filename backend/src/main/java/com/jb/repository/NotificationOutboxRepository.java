package com.jb.repository;

import com.jb.domain.NotificationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {
    @Query("select n from NotificationOutbox n where n.status = 'pending' and n.nextAttemptAt <= :now order by n.nextAttemptAt asc")
    List<NotificationOutbox> findDue(@Param("now") Instant now);
}
