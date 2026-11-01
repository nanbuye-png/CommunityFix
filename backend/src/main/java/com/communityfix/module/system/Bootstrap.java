package com.communityfix.module.system;

import com.communityfix.common.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class Bootstrap implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(Bootstrap.class);
    private final Db db;private final PasswordEncoder passwords;private final String username,password,propertyPassword,residentPassword,workerPassword;private final boolean demo,syncPasswords;
    public Bootstrap(Db db,PasswordEncoder passwords,@Value("${communityfix.bootstrap-username}") String username,@Value("${communityfix.bootstrap-password}") String password,@Value("${communityfix.demo-enabled}") boolean demo,@Value("${communityfix.demo-sync-passwords:false}") boolean syncPasswords,@Value("${communityfix.demo-passwords.property:}") String propertyPassword,@Value("${communityfix.demo-passwords.resident:}") String residentPassword,@Value("${communityfix.demo-passwords.worker:}") String workerPassword) {this.db=db;this.passwords=passwords;this.username=username;this.password=password;this.demo=demo;this.syncPasswords=syncPasswords;this.propertyPassword=propertyPassword;this.residentPassword=residentPassword;this.workerPassword=workerPassword;}
    @Transactional public void run(ApplicationArguments args) {
        if(username.isBlank()) return;
        validatePassword(password,"BOOTSTRAP_PASSWORD");
        if(demo) {
            validatePassword(configuredPassword("property"),"DEMO_PROPERTY_PASSWORD");
            validatePassword(configuredPassword("resident"),"DEMO_RESIDENT_PASSWORD");
            validatePassword(configuredPassword("worker"),"DEMO_WORKER_PASSWORD");
        }
        account(username,"系统管理员",4);
        if(!demo) return;
        if(db.count("SELECT COUNT(*) FROM community")>0) {
            if(syncPasswords) {
                synchronize("resident",1);synchronize("property",3);synchronize("worker",2);
                synchronize("resident2",1);synchronize("property2",3);
            }
            return;
        }
        long resident=account("resident","陈女士",1),admin=account("property","林管家",3),worker=account("worker","张师傅",2),other=account("resident2","李先生",1);
        long cid=db.insert("community",Db.fields("name","青禾社区","address","青禾路18号","description","让每一次报修都有回应"));
        long bid=db.insert("building",Db.fields("community_id",cid,"building_no","3"));
        long uid=db.insert("unit",Db.fields("building_id",bid,"unit_no","1"));
        long room=db.insert("room",Db.fields("unit_id",uid,"room_no","602","floor",6));
        db.insert("room",Db.fields("unit_id",uid,"room_no","603","floor",6));
        db.execute("INSERT INTO admin_community(user_id,community_id) VALUES (?,?)",admin,cid);
        db.insert("resident_room_binding",Db.fields("user_id",resident,"room_id",room,"status","APPROVED","reviewer_id",admin));
        long wid=db.insert("worker_profile",Db.fields("user_id",worker,"real_name","张师傅","max_active_orders",5,"owner_community_id",cid));
        db.execute("INSERT INTO worker_service_community(worker_id,community_id) VALUES (?,?)",wid,cid);
        for(long category=1;category<=4;category++) db.insert("worker_skill",Db.fields("worker_id",wid,"category_id",category,"proficiency",0.9));
        long second=db.insert("community",Db.fields("name","湖畔社区","address","湖畔路8号"));
        long secondAdmin=account("property2","周管家",3);db.execute("INSERT INTO admin_community(user_id,community_id) VALUES (?,?)",secondAdmin,second);
    }
    private long account(String name,String nickname,int role) {
        var existing=db.row("SELECT id,password FROM sys_user WHERE username=? FOR UPDATE",name);
        if(existing!=null){synchronize(name,role,existing);return Db.id(existing,"id");}
        long id=db.insert("sys_user",Db.fields("username",name,"password",passwords.encode(configuredPassword(name)),"nickname",nickname));
        db.execute("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)",id,role);return id;
    }
    private void synchronize(String name,int role) {
        var existing=db.row("SELECT id,password FROM sys_user WHERE username=? FOR UPDATE",name);
        if(existing!=null)synchronize(name,role,existing);
    }
    private void synchronize(String name,int role,Map<String,Object> existing) {
        if(!demo||!syncPasswords)return;
        long id=Db.id(existing,"id");
        if(db.count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",id,role)!=1) {
            log.warn("Skipping demo password synchronization for {}: expected role does not match",name);return;
        }
        String current=(String)existing.get("password");
        String configured=configuredPassword(name);
        if(current!=null&&passwords.matches(configured,current))return;
        db.execute("UPDATE sys_user SET password=?,token_version=token_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",passwords.encode(configured),id);
        log.info("Synchronized configured demo password for {}; previous sessions revoked",name);
    }
    private String configuredPassword(String name) {
        if(!demo||name.equals(username))return password;
        String configured=switch(name){case "property"->propertyPassword;case "resident"->residentPassword;case "worker"->workerPassword;default->"";};
        return configured.isEmpty()?password:configured;
    }
    private static void validatePassword(String value,String key) {
        if(value.isBlank()||value.codePointCount(0,value.length())<12||value.getBytes(StandardCharsets.UTF_8).length>72)
            throw new IllegalStateException(key+" must contain at least 12 characters and at most 72 UTF-8 bytes");
    }
}
