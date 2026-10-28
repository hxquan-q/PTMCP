package local.ptmcp.datasource.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

class DestinationPolicyTest {
    @Test
    void deniesUnapprovedPortsBeforeDnsResolution() throws Exception {
        DestinationPolicy policy =
                new DestinationPolicy(
                        "127.0.0.0/8",
                        "3306,5432",
                        host -> {
                            throw new AssertionError("unapproved port must never resolve DNS");
                        });
        DomainException failure =
                assertThrows(
                        DomainException.class, () -> policy.resolve("db.synthetic.invalid", 22));
        assertEquals(ErrorCode.INVALID_ARGUMENT, failure.errorCode());
    }

    @Test
    void rejectsMixedAnswersAndIpv6MetadataEvenWhenCoveredByTheAllowlist() throws Exception {
        DestinationPolicy policy =
                new DestinationPolicy(
                        "127.0.0.0/8,::/0",
                        "5432",
                        host ->
                                new InetAddress[] {
                                    InetAddress.getByName("127.0.0.1"),
                                    InetAddress.getByName("fe80::1")
                                });
        assertThrows(DomainException.class, () -> policy.resolve("db.synthetic.invalid", 5432));
        DestinationPolicy metadata =
                new DestinationPolicy(
                        "::/0",
                        "5432",
                        host -> new InetAddress[] {InetAddress.getByName("fd00:ec2::254")});
        assertThrows(DomainException.class, () -> metadata.resolve("db.synthetic.invalid", 5432));
    }

    @Test
    void emptyPortsAndCidrsFailClosed() {
        assertThrows(
                DomainException.class,
                () -> new DestinationPolicy("127.0.0.0/8", "").resolve("127.0.0.1", 3306));
        assertThrows(
                DomainException.class,
                () -> new DestinationPolicy("", "3306").resolve("127.0.0.1", 3306));
    }

    @Test
    void rejectsMalformedPortConfiguration() {
        assertThrows(
                IllegalArgumentException.class, () -> new DestinationPolicy("127.0.0.0/8", "0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DestinationPolicy("127.0.0.0/8", "3306,65536"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DestinationPolicy("127.0.0.0/8", "3306-5432"));
    }
}
