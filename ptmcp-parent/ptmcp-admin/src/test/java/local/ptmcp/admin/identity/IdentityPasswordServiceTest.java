package local.ptmcp.admin.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.security.UserSecurityStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import javax.sql.DataSource;

/** Separate identity/metadata transactions; synthetic data only. */
class IdentityPasswordServiceTest {
    private static MySQLContainer mysql;
    private static DataSource primary;
    private static JdbcTemplate jdbc;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String OLD = secret();
    private static final String NEXT = secret();

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @BeforeAll static void start() {
        mysql = new MySQLContainer(System.getProperty("ptmcp.test.mysqlImage",
                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                .withDatabaseName("identity_change_test").withUsername("identity_test").withPassword(secret());
        mysql.start();
        primary = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(primary);
        Flyway.configure().dataSource(primary).locations("classpath:db/ptmcp-migration").load().migrate();
        jdbc.execute("CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, user_name VARCHAR(64), nick_name VARCHAR(64), password VARCHAR(100), status CHAR(1), del_flag CHAR(1))");
        jdbc.update("INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (42,0,'','Test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,version,create_time,update_time) VALUES (42,42,'Synthetic',?,'ACTIVE',5,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", ENCODER.encode(OLD));
        jdbc.update("INSERT INTO sys_user_security (user_id,auth_version,identity_version,version,create_time,update_time) VALUES (42,7,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO sys_user VALUES (42,'synthetic','Synthetic',?,'0','0')", ENCODER.encode(OLD));
    }

    @AfterAll static void stop() { if (mysql != null) mysql.stop(); }

    @BeforeEach void seed() {
        jdbc.update("UPDATE sys_user_security SET auth_version=7,identity_version=1,identity_state='READY',identity_change_id=NULL WHERE user_id=42");
        jdbc.update("UPDATE sys_user_profile SET password_hash=?,version=5 WHERE user_id=42", ENCODER.encode(OLD));
        jdbc.update("UPDATE sys_user SET password=?,status='0',del_flag='0' WHERE user_id=42", ENCODER.encode(OLD));
        jdbc.update("UPDATE sys_security_state SET policy_epoch=20 WHERE singleton_id=1");
        jdbc.update("DELETE FROM web_session_index");
    }

    @Test void hostFailureKeepsTheUserPendingAndRevokesBothSessionKinds() {
        for (String kind : new String[]{"ADMIN", "AS"}) {
            jdbc.update("INSERT INTO web_session_index (session_hash,kind,user_id,auth_version,issued_at,expires_at,version,create_time,update_time) VALUES (UNHEX(SHA2(?,256)),?,42,7,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR),1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", kind, kind);
        }
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        java.sql.Connection real = primary.getConnection();
                        return Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                            new Class<?>[]{java.sql.Connection.class}, (connection, call, values) -> {
                                if (call.getName().equals("prepareStatement") && ((String) values[0]).startsWith("UPDATE sys_user ")) {
                                    throw new SQLException("synthetic host write outage");
                                }
                                try { return call.invoke(real, values); }
                                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            });
                    }
                    try { return method.invoke(primary, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        assertThrows(DomainException.class, () -> new IdentityPasswordService(primary, new IdentityPasswordWriter(unavailable))
                .reset(42, 5, NEXT, "Synthetic recovery test", 99));
        assertFalse(new UserSecurityStore(primary).read(42).ready());
        assertEquals(8, new UserSecurityStore(primary).read(42).authVersion());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM web_session_index WHERE revoked_at IS NULL", Integer.class));
    }

    @Test void resetReturnsTheProfileVersionAndAdvancesTheAuthority() {
        long version = new IdentityPasswordService(primary, new IdentityPasswordWriter(primary))
                .reset(42, 5, NEXT, "Synthetic reset test", 99);
        assertEquals(6, version);
        assertEquals(8, new UserSecurityStore(primary).read(42).authVersion());
        assertTrue(new UserSecurityStore(primary).read(42).ready());
        assertTrue(ENCODER.matches(NEXT, jdbc.queryForObject("SELECT password FROM sys_user WHERE user_id=42", String.class)));
    }
}
