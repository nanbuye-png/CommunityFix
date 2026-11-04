package com.communityfix.module.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class NotificationSocketConfiguration implements WebSocketConfigurer {
    private final NotificationSocket socket;private final String[] origins;
    public NotificationSocketConfiguration(NotificationSocket socket,@Value("${communityfix.cors-origins}") String origins){this.socket=socket;this.origins=origins.split(",");}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(socket,"/api/notifications/live").setAllowedOrigins(origins);
    }
}
