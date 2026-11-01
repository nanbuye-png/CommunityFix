package com.communityfix;

import com.communityfix.common.Db;
import com.communityfix.module.system.Bootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BootstrapTest {
    private Db db;
    private PasswordEncoder encoder;
    private final DefaultApplicationArguments args=new DefaultApplicationArguments(new String[0]);
    private static final String PASSWORD="UnitTestOnly_Password_0!";

    @BeforeEach void setup(){
        db=mock(Db.class);encoder=mock(PasswordEncoder.class);
        when(db.row(anyString(),any())).thenReturn(null);
        when(db.count("SELECT COUNT(*) FROM community")).thenReturn(1L);
        when(encoder.encode(anyString())).thenReturn("encoded");
    }
    private Bootstrap bootstrap(String password,boolean demo,boolean sync){
        return new Bootstrap(db,encoder,"admin",password,demo,sync,"","","");
    }
    private void existingAdmin(){
        when(db.row("SELECT id,password FROM sys_user WHERE username=? FOR UPDATE","admin"))
            .thenReturn(Map.of("id",1L,"password","old-hash"));
    }
    @Test void demoRejectsShortPasswordBeforeDatabaseChanges(){
        assertThrows(IllegalStateException.class,()->bootstrap("admin123",true,true).run(args));
        verifyNoInteractions(db,encoder);
    }
    @Test void productionRejectsShortPassword(){
        assertThrows(IllegalStateException.class,()->bootstrap("admin123",false,true).run(args));
        verifyNoInteractions(db,encoder);
    }
    @Test void blankPasswordIsRejected(){
        assertThrows(IllegalStateException.class,()->bootstrap(" ".repeat(12),true,true).run(args));
        verifyNoInteractions(db,encoder);
    }
    @Test void twelveCharacterPasswordIsAccepted(){
        when(db.insert(eq("sys_user"),anyMap())).thenReturn(1L);
        bootstrap("123456789012",true,false).run(args);
        verify(encoder).encode("123456789012");
        verify(db).execute("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)",1L,4);
    }
    @Test void optedInChangeRevokesPreviousSessions(){
        existingAdmin();
        when(db.count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",1L,4)).thenReturn(1L);
        bootstrap(PASSWORD,true,true).run(args);
        verify(db).execute("UPDATE sys_user SET password=?,token_version=token_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?","encoded",1L);
    }
    @Test void unchangedPasswordDoesNotRevokeSessions(){
        existingAdmin();
        when(db.count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",1L,4)).thenReturn(1L);
        when(encoder.matches(PASSWORD,"old-hash")).thenReturn(true);
        bootstrap(PASSWORD,true,true).run(args);
        verify(encoder,never()).encode(anyString());
        verify(db,never()).execute(anyString(),any(),any());
    }
    @Test void productionNeverResetsExistingPasswordEvenWithFlag(){
        existingAdmin();bootstrap(PASSWORD,false,true).run(args);verifyNoInteractions(encoder);
    }
    @Test void optOutPreservesExistingDemoPassword(){
        existingAdmin();bootstrap(PASSWORD,true,false).run(args);verifyNoInteractions(encoder);
    }
    @Test void roleMismatchIsNotReset(){
        existingAdmin();bootstrap(PASSWORD,true,true).run(args);verifyNoInteractions(encoder);
    }
    @Test void utf8LimitIsRejectedBeforeEncoding(){
        assertThrows(IllegalStateException.class,()->bootstrap("密".repeat(25),true,true).run(args));
        verifyNoInteractions(db,encoder);
    }
    @Test void independentRolePasswordIsUsed(){
        existingAdmin();
        when(db.row("SELECT id,password FROM sys_user WHERE username=? FOR UPDATE","worker"))
            .thenReturn(Map.of("id",2L,"password","worker-old-hash"));
        when(db.count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",2L,2)).thenReturn(1L);
        new Bootstrap(db,encoder,"admin",PASSWORD,true,true,"UnitTestOnly_Password_1!","UnitTestOnly_Password_2!","UnitTestOnly_Password_3!").run(args);
        verify(encoder).matches("UnitTestOnly_Password_3!","worker-old-hash");
        verify(encoder).encode("UnitTestOnly_Password_3!");
    }
    @Test void invalidRolePasswordFailsBeforeAnyChanges(){
        var application=new Bootstrap(db,encoder,"admin",PASSWORD,true,true,"UnitTestOnly_Password_1!","UnitTestOnly_Password_2!","short");
        assertThrows(IllegalStateException.class,()->application.run(args));
        verifyNoInteractions(db,encoder);
    }
}
