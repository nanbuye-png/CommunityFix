package com.communityfix.module.ai;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Map;

@Component
public class ModelRoutes {
    public enum Scene { REPAIR, SUPPORT }
    // Intentionally not a record: generated toString must never expose credentials.
    public static final class Route {
        private final String endpoint, key, model;
        private int maxTokens=1024;
        public int maxTokens(){return maxTokens;}
        public Route(String endpoint, String key, String model) {
            this.endpoint=endpoint;this.key=key;this.model=model;
            if(!endpoint.isBlank()) {
                try {
                    URI uri=URI.create(endpoint);
                    if(!("http".equals(uri.getScheme())||"https".equals(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
                        throw new IllegalArgumentException();
                } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Invalid AI endpoint configuration"); }
            }
        }
        public String endpoint(){return endpoint;}
        public String key(){return key;}
        public String model(){return model;}
        public boolean configured(){return !endpoint.isBlank()&&!key.isBlank()&&!model.isBlank();}
    }
    private final Map<Scene,Route> routes;
    public ModelRoutes(Environment env) {
        Route legacy=read(env,"communityfix.ai");
        routes=Map.of(Scene.REPAIR,configuredRoute(env,"repair",legacy,128),Scene.SUPPORT,configuredRoute(env,"support",legacy,1024));
    }
    private static Route configuredRoute(Environment env,String scene,Route legacy,int defaultTokens){
        Route source=override(env,scene,legacy);Route route=new Route(source.endpoint(),source.key(),source.model());
        route.maxTokens=env.getProperty("communityfix.ai."+scene+".max-output-tokens",Integer.class,defaultTokens);
        if(route.maxTokens<64||route.maxTokens>4096)throw new IllegalArgumentException("AI output token limit must be 64 to 4096");
        return route;
    }
    private static Route read(Environment env,String prefix){
        return new Route(env.getProperty(prefix+".endpoint",""),env.getProperty(prefix+".api-key",""),env.getProperty(prefix+".model",""));
    }
    private static Route override(Environment env,String name,Route legacy){
        Route r=read(env,"communityfix.ai."+name);
        if(r.endpoint().isBlank()&&r.key().isBlank()&&r.model().isBlank())return legacy;
        if(!r.configured())throw new IllegalArgumentException("AI "+name+" override requires endpoint, api-key and model together");
        return r;
    }
    public Route forScene(Scene scene){return routes.get(scene);}
}
