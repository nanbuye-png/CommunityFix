package com.communityfix.module.ai;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ModelRoutesTest {
    private MockEnvironment legacy(){return new MockEnvironment()
        .withProperty("communityfix.ai.endpoint","https://legacy.example/chat/completions")
        .withProperty("communityfix.ai.api-key","legacy-secret")
        .withProperty("communityfix.ai.model","legacy-model");}

    @Test void existingConfigurationIsSharedWithoutOverrides(){
        var routes=new ModelRoutes(legacy());
        assertNotSame(routes.forScene(ModelRoutes.Scene.REPAIR),routes.forScene(ModelRoutes.Scene.SUPPORT));
        assertEquals(routes.forScene(ModelRoutes.Scene.REPAIR).endpoint(),routes.forScene(ModelRoutes.Scene.SUPPORT).endpoint());
        assertEquals(routes.forScene(ModelRoutes.Scene.REPAIR).key(),routes.forScene(ModelRoutes.Scene.SUPPORT).key());
        assertEquals(128,routes.forScene(ModelRoutes.Scene.REPAIR).maxTokens());
        assertEquals(1024,routes.forScene(ModelRoutes.Scene.SUPPORT).maxTokens());
        assertTrue(routes.forScene(ModelRoutes.Scene.REPAIR).configured());
        assertEquals("legacy-model",routes.forScene(ModelRoutes.Scene.SUPPORT).model());
        assertFalse(routes.forScene(ModelRoutes.Scene.REPAIR).toString().contains("legacy-secret"));
    }
    @Test void completeSceneOverrideIsIsolated(){
        var env=legacy().withProperty("communityfix.ai.support.endpoint","https://new.example/chat/completions")
            .withProperty("communityfix.ai.support.api-key","support-secret").withProperty("communityfix.ai.support.model","support-model");
        var routes=new ModelRoutes(env);
        assertEquals("support-secret",routes.forScene(ModelRoutes.Scene.SUPPORT).key());
        assertEquals("support-model",routes.forScene(ModelRoutes.Scene.SUPPORT).model());
        assertEquals("https://new.example/chat/completions",routes.forScene(ModelRoutes.Scene.SUPPORT).endpoint());
        assertEquals("legacy-secret",routes.forScene(ModelRoutes.Scene.REPAIR).key());
    }
    @Test void partialOverrideCannotSendLegacyKeyToNewVendor(){
        var error=assertThrows(IllegalArgumentException.class,()->new ModelRoutes(legacy()
            .withProperty("communityfix.ai.repair.endpoint","https://new.example/chat/completions")));
        assertTrue(error.getMessage().contains("together"));assertFalse(error.getMessage().contains("secret"));
    }
    @Test void unconfiguredLegacyRouteRemainsDisabled(){
        assertFalse(new ModelRoutes(new MockEnvironment()).forScene(ModelRoutes.Scene.REPAIR).configured());
        assertFalse(new ModelRoutes(new MockEnvironment().withProperty("communityfix.ai.model","model")).forScene(ModelRoutes.Scene.SUPPORT).configured());
    }
    @Test void invalidUrlsFailWithoutLeakingCredentials(){
        for(String endpoint:new String[]{"relative/path","ftp://example.com/chat","https://secret@example.com/chat","https://example.com/chat#secret"}){
            var error=assertThrows(IllegalArgumentException.class,()->new ModelRoutes.Route(endpoint,"secret","model"));
            assertEquals("Invalid AI endpoint configuration",error.getMessage());
        }
    }
}
