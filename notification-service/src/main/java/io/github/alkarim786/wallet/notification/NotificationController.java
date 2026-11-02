package io.github.alkarim786.wallet.notification;

import io.github.alkarim786.wallet.common.Headers;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/notifications")
public class NotificationController {
    private final NotificationStore store;

    public NotificationController(NotificationStore store) {
        this.store = store;
    }

    @GetMapping
    public List<NotificationStore.Notification> list(@RequestHeader(Headers.USER_ID) long userId,
                                                     @RequestParam(defaultValue = "50") int limit) {
        return store.forUser(userId, Math.min(200, Math.max(1, limit)));
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll(@RequestHeader(Headers.USER_ID) long userId) {
        return Map.of("updated", store.markAllRead(userId));
    }
}
