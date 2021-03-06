/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.replication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper.DigestType;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.LedgerMetadata;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.meta.LedgerManager;
import org.apache.bookkeeper.meta.LedgerManagerFactory;
import org.apache.bookkeeper.meta.ZkLedgerUnderreplicationManager;
import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks;
import org.apache.bookkeeper.proto.DataFormats.UnderreplicatedLedgerFormat;
import org.apache.bookkeeper.replication.ReplicationException.CompatibilityException;
import org.apache.bookkeeper.replication.ReplicationException.UnavailableException;
import org.apache.bookkeeper.test.BookKeeperClusterTestCase;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests publishing of under replicated ledgers by the Auditor bookie node when
 * corresponding bookies identifes as not running
 */
public class AuditorLedgerCheckerTest extends BookKeeperClusterTestCase {

    // Depending on the taste, select the amount of logging
    // by decommenting one of the two lines below
    // private final static Logger LOG = Logger.getRootLogger();
    private final static Logger LOG = LoggerFactory
            .getLogger(AuditorLedgerCheckerTest.class);

    private static final byte[] ledgerPassword = "aaa".getBytes();
    private Random rng; // Random Number Generator

    private DigestType digestType;

    private final String UNDERREPLICATED_PATH = baseClientConf
            .getZkLedgersRootPath()
            + "/underreplication/ledgers";
    private Map<String, AuditorElector> auditorElectors = new ConcurrentHashMap<>();
    private ZkLedgerUnderreplicationManager urLedgerMgr;
    private Set<Long> urLedgerList;
    private String electionPath;

    private List<Long> ledgerList;

    public AuditorLedgerCheckerTest()
        throws IOException, KeeperException, InterruptedException,
        CompatibilityException {
        this("org.apache.bookkeeper.meta.HierarchicalLedgerManagerFactory");
    }

    AuditorLedgerCheckerTest(String ledgerManagerFactoryClass)
            throws IOException, KeeperException, InterruptedException,
            CompatibilityException {
        super(3);
        LOG.info("Running test case using ledger manager : "
                + ledgerManagerFactoryClass);
        this.digestType = DigestType.CRC32;
        // set ledger manager name
        baseConf.setLedgerManagerFactoryClassName(ledgerManagerFactoryClass);
        baseClientConf
                .setLedgerManagerFactoryClassName(ledgerManagerFactoryClass);
        electionPath = baseConf.getZkLedgersRootPath()
                + "/underreplication/auditorelection";
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        urLedgerMgr = new ZkLedgerUnderreplicationManager(baseClientConf, zkc);
        startAuditorElectors();
        rng = new Random(System.currentTimeMillis()); // Initialize the Random
        urLedgerList = new HashSet<Long>();
        ledgerList = new ArrayList<Long>(2);
    }

    @Override
    public void tearDown() throws Exception {
        stopAuditorElectors();
        super.tearDown();
    }

    private void startAuditorElectors() throws Exception {
        for (BookieServer bserver : bs) {
            String addr = bserver.getLocalAddress().toString();
            AuditorElector auditorElector = new AuditorElector(addr,
                    baseConf, zkc);
            auditorElectors.put(addr, auditorElector);
            auditorElector.start();
            LOG.debug("Starting Auditor Elector");
        }
    }

    private void stopAuditorElectors() throws Exception {
        for (AuditorElector auditorElector : auditorElectors.values()) {
            auditorElector.shutdown();
            LOG.debug("Stopping Auditor Elector!");
        }
    }

    /**
     * Test publishing of under replicated ledgers by the auditor bookie
     */
    @Test
    public void testSimpleLedger() throws Exception {
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList
                .size());

        int bkShutdownIndex = bs.size() - 1;
        String shutdownBookie = shutdownBookie(bkShutdownIndex);

        // grace period for publishing the bk-ledger
        LOG.debug("Waiting for ledgers to be marked as under replicated");
        waitForAuditToComplete();
        underReplicaLatch.await(5, TimeUnit.SECONDS);
        Map<Long, String> urLedgerData = getUrLedgerData(urLedgerList);
        assertEquals("Missed identifying under replicated ledgers", 1,
                urLedgerList.size());

        /*
         * Sample data format present in the under replicated ledger path
         *
         * {4=replica: "10.18.89.153:5002"}
         */
        assertTrue("Ledger is not marked as underreplicated:" + ledgerId,
                urLedgerList.contains(ledgerId));
        String data = urLedgerData.get(ledgerId);
        assertTrue("Bookie " + shutdownBookie
                + "is not listed in the ledger as missing replica :" + data,
                data.contains(shutdownBookie));
    }

    /**
     * Test once published under replicated ledger should exists even after
     * restarting respective bookie
     */
    @Test
    public void testRestartBookie() throws Exception {
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        LedgerHandle lh2 = createAndAddEntriesToLedger();

        LOG.debug("Created following ledgers : {}, {}", lh1, lh2);

        int bkShutdownIndex = bs.size() - 1;
        ServerConfiguration bookieConf1 = bsConfs.get(bkShutdownIndex);
        String shutdownBookie = shutdownBookie(bkShutdownIndex);

        // restart the failed bookie
        bs.add(startBookie(bookieConf1));

        waitForLedgerMissingReplicas(lh1.getId(), 10, shutdownBookie);
        waitForLedgerMissingReplicas(lh2.getId(), 10, shutdownBookie);
    }

    /**
     * Test publishing of under replicated ledgers when multiple bookie failures
     * one after another.
     */
    @Test
    public void testMultipleBookieFailures() throws Exception {
        LedgerHandle lh1 = createAndAddEntriesToLedger();

        // failing first bookie
        shutdownBookie(bs.size() - 1);

        // simulate re-replication
        doLedgerRereplication(lh1.getId());

        // failing another bookie
        String shutdownBookie = shutdownBookie(bs.size() - 1);

        // grace period for publishing the bk-ledger
        LOG.debug("Waiting for ledgers to be marked as under replicated");
        assertTrue("Ledger should be missing second replica",
                   waitForLedgerMissingReplicas(lh1.getId(), 10, shutdownBookie));
    }

    @Test
    public void testToggleLedgerReplication() throws Exception {
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        ledgerList.add(lh1.getId());
        LOG.debug("Created following ledgers : " + ledgerList);

        // failing another bookie
        CountDownLatch urReplicaLatch = registerUrLedgerWatcher(ledgerList
                .size());

        // disabling ledger replication
        urLedgerMgr.disableLedgerReplication();
        ArrayList<String> shutdownBookieList = new ArrayList<String>();
        shutdownBookieList.add(shutdownBookie(bs.size() - 1));
        shutdownBookieList.add(shutdownBookie(bs.size() - 1));

        assertFalse("Ledger replication is not disabled!", urReplicaLatch
                .await(1, TimeUnit.SECONDS));

        // enabling ledger replication
        urLedgerMgr.enableLedgerReplication();
        assertTrue("Ledger replication is not enabled!", urReplicaLatch.await(
                5, TimeUnit.SECONDS));
    }

    @Test
    public void testDuplicateEnDisableAutoRecovery() throws Exception {
        urLedgerMgr.disableLedgerReplication();
        try {
            urLedgerMgr.disableLedgerReplication();
            fail("Must throw exception, since AutoRecovery is already disabled");
        } catch (UnavailableException e) {
            assertTrue("AutoRecovery is not disabled previously!",
                    e.getCause() instanceof KeeperException.NodeExistsException);
        }
        urLedgerMgr.enableLedgerReplication();
        try {
            urLedgerMgr.enableLedgerReplication();
            fail("Must throw exception, since AutoRecovery is already enabled");
        } catch (UnavailableException e) {
            assertTrue("AutoRecovery is not enabled previously!",
                    e.getCause() instanceof KeeperException.NoNodeException);
        }
    }

    /**
     * Test Auditor should consider Readonly bookie as available bookie. Should not publish ur ledgers for
     * readonly bookies.
     */
    @Test
    public void testReadOnlyBookieExclusionFromURLedgersCheck() throws Exception {
        LedgerHandle lh = createAndAddEntriesToLedger();
        ledgerList.add(lh.getId());
        LOG.debug("Created following ledgers : " + ledgerList);

        int count = ledgerList.size();
        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(count);

        ServerConfiguration bookieConf = bsConfs.get(2);
        BookieServer bk = bs.get(2);
        bookieConf.setReadOnlyModeEnabled(true);
        bk.getBookie().doTransitionToReadOnlyMode();

        // grace period for publishing the bk-ledger
        LOG.debug("Waiting for Auditor to finish ledger check.");
        waitForAuditToComplete();
        assertFalse("latch should not have completed", underReplicaLatch.await(5, TimeUnit.SECONDS));
    }

    /**
     * Test Auditor should consider Readonly bookie fail and publish ur ledgers for readonly bookies.
     */
    @Test
    public void testReadOnlyBookieShutdown() throws Exception {
        LedgerHandle lh = createAndAddEntriesToLedger();
        long ledgerId = lh.getId();
        ledgerList.add(ledgerId);
        LOG.debug("Created following ledgers : " + ledgerList);

        int count = ledgerList.size();
        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(count);

        int bkIndex = bs.size() - 1;
        LOG.debug("Moving bookie {} {} to read only...", bkIndex, bs.get(bkIndex));
        ServerConfiguration bookieConf = bsConfs.get(bkIndex);
        BookieServer bk = bs.get(bkIndex);
        bookieConf.setReadOnlyModeEnabled(true);
        bk.getBookie().doTransitionToReadOnlyMode();

        // grace period for publishing the bk-ledger
        LOG.debug("Waiting for Auditor to finish ledger check.");
        waitForAuditToComplete();
        assertFalse("latch should not have completed", underReplicaLatch.await(1, TimeUnit.SECONDS));

        String shutdownBookie = shutdownBookie(bkIndex);

        // grace period for publishing the bk-ledger
        LOG.debug("Waiting for ledgers to be marked as under replicated");
        waitForAuditToComplete();
        underReplicaLatch.await(5, TimeUnit.SECONDS);
        Map<Long, String> urLedgerData = getUrLedgerData(urLedgerList);
        assertEquals("Missed identifying under replicated ledgers", 1, urLedgerList.size());

        /*
         * Sample data format present in the under replicated ledger path
         *
         * {4=replica: "10.18.89.153:5002"}
         */
        assertTrue("Ledger is not marked as underreplicated:" + ledgerId, urLedgerList.contains(ledgerId));
        String data = urLedgerData.get(ledgerId);
        assertTrue("Bookie " + shutdownBookie + "is not listed in the ledger as missing replica :" + data,
                data.contains(shutdownBookie));
    }

    public void _testDelayedAuditOfLostBookies() throws Exception {
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList
                .size());

        // wait for 5 seconds before starting the recovery work when a bookie fails
        urLedgerMgr.setLostBookieRecoveryDelay(5);

        // shutdown a non auditor bookie; choosing non-auditor to avoid another election
        String shutdownBookie = shutDownNonAuditorBookie();

        LOG.debug("Waiting for ledgers to be marked as under replicated");
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(4, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());

        // wait for another 5 seconds for the ledger to get reported as under replicated
        assertTrue("audit of lost bookie isn't delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));

        assertTrue("Ledger is not marked as underreplicated:" + ledgerId,
                urLedgerList.contains(ledgerId));
        Map<Long, String> urLedgerData = getUrLedgerData(urLedgerList);
        String data = urLedgerData.get(ledgerId);
        assertTrue("Bookie " + shutdownBookie
                + "is not listed in the ledger as missing replica :" + data,
                data.contains(shutdownBookie));
    }

    /**
     * Test publishing of under replicated ledgers by the auditor
     * bookie is delayed if LostBookieRecoveryDelay option is set
     */
    @Test
    public void testDelayedAuditOfLostBookies() throws Exception {
        // wait for a second so that the initial periodic check finishes
        Thread.sleep(1000);

        _testDelayedAuditOfLostBookies();
    }

    /**
     * Test publishing of under replicated ledgers by the auditor
     * bookie is delayed if LostBookieRecoveryDelay option is set
     * and it continues to be delayed even when periodic bookie check
     *  is set to run every 2 secs. I.e. periodic bookie check doesn't
     *  override the delay
     */
    @Test
    public void testDelayedAuditWithPeriodicBookieCheck() throws Exception {
        // enable periodic bookie check on a cadence of every 2 seconds.
        // this requires us to stop the auditor/auditorElectors, set the
        // periodic check interval and restart the auditorElectors
        stopAuditorElectors();
        baseConf.setAuditorPeriodicBookieCheckInterval(2);
        startAuditorElectors();

        // wait for a second so that the initial periodic check finishes
        Thread.sleep(1000);

        // the delaying of audit should just work despite the fact
        // we have enabled periodic bookie check
        _testDelayedAuditOfLostBookies();
    }

    @Test
    public void testRescheduleOfDelayedAuditOfLostBookiesToStartImmediately() throws Exception {
     // wait for a second so that the initial periodic check finishes
        Thread.sleep(1000);
        
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList
                .size());

        // wait for 50 seconds before starting the recovery work when a bookie fails
        urLedgerMgr.setLostBookieRecoveryDelay(50);

        // shutdown a non auditor bookie; choosing non-auditor to avoid another election
        String shutdownBookie = shutDownNonAuditorBookie();

        LOG.debug("Waiting for ledgers to be marked as under replicated");
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(4, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());

        // set lostBookieRecoveryDelay to 0, so that it triggers AuditTask immediately
        urLedgerMgr.setLostBookieRecoveryDelay(0);
        
        // wait for 1 second for the ledger to get reported as under replicated
        assertTrue("audit of lost bookie isn't delayed", underReplicaLatch.await(1, TimeUnit.SECONDS));

        assertTrue("Ledger is not marked as underreplicated:" + ledgerId,
                urLedgerList.contains(ledgerId));
        Map<Long, String> urLedgerData = getUrLedgerData(urLedgerList);
        String data = urLedgerData.get(ledgerId);
        assertTrue("Bookie " + shutdownBookie
                + "is not listed in the ledger as missing replica :" + data,
                data.contains(shutdownBookie));
    }
    
    @Test
    public void testRescheduleOfDelayedAuditOfLostBookiesToStartLater() throws Exception {
     // wait for a second so that the initial periodic check finishes
        Thread.sleep(1000);
        
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList
                .size());

        // wait for 3 seconds before starting the recovery work when a bookie fails
        urLedgerMgr.setLostBookieRecoveryDelay(3);

        // shutdown a non auditor bookie; choosing non-auditor to avoid another election
        String shutdownBookie = shutDownNonAuditorBookie();

        LOG.debug("Waiting for ledgers to be marked as under replicated");
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());
        
        // set lostBookieRecoveryDelay to 4, so the pending AuditTask is resheduled
        urLedgerMgr.setLostBookieRecoveryDelay(4);
        
        // since we changed the BookieRecoveryDelay period to 4, the audittask shouldn't have been executed
        LOG.debug("Waiting for ledgers to be marked as under replicated");
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());        
        
        // wait for 3 seconds (since we already waited for 2 secs) for the ledger to get reported as under replicated
        assertTrue("audit of lost bookie isn't delayed", underReplicaLatch.await(3, TimeUnit.SECONDS));
        assertTrue("Ledger is not marked as underreplicated:" + ledgerId,
                urLedgerList.contains(ledgerId));
        Map<Long, String> urLedgerData = getUrLedgerData(urLedgerList);
        String data = urLedgerData.get(ledgerId);
        assertTrue("Bookie " + shutdownBookie
                + "is not listed in the ledger as missing replica :" + data,
                data.contains(shutdownBookie));
    }
    
    @Test
    public void testTriggerAuditorWithNoPendingAuditTask() throws Exception {
        // wait for a second so that the initial periodic check finishes
        Thread.sleep(1000);
        int lostBookieRecoveryDelayConfValue = baseConf.getLostBookieRecoveryDelay();
        Auditor auditorBookiesAuditor = getAuditorBookiesAuditor();
        Future<?> auditTask = auditorBookiesAuditor.getAuditTask();
        int lostBookieRecoveryDelayBeforeChange = auditorBookiesAuditor.getLostBookieRecoveryDelayBeforeChange();
        Assert.assertEquals("auditTask is supposed to be null", null, auditTask);
        Assert.assertEquals(
                "lostBookieRecoveryDelayBeforeChange of Auditor should be equal to BaseConf's lostBookieRecoveryDelay",
                lostBookieRecoveryDelayConfValue, lostBookieRecoveryDelayBeforeChange);
        
        // there is no easy way to validate if the Auditor has executed Audit process (Auditor.startAudit), 
        // without shuttingdown Bookie. To test if by resetting LostBookieRecoveryDelay it does Auditing
        // even when there is no pending AuditTask, following approach is needed.
        
        // Here we are creating few ledgers ledgermetadata with non-existing bookies as its ensemble.
        // When Auditor does audit it recognizes these ledgers as underreplicated and mark them as 
        // under-replicated, since these bookies are not available. 
        int numofledgers = 5;
        Random rand = new Random();
        for (int i = 0; i < numofledgers; i++) {
            LedgerMetadata metadata = new LedgerMetadata(3, 2, 2, DigestType.CRC32, "passwd".getBytes(), null);
            ArrayList<BookieSocketAddress> ensemble = new ArrayList<BookieSocketAddress>();
            ensemble.add(new BookieSocketAddress("99.99.99.99:9999"));
            ensemble.add(new BookieSocketAddress("11.11.11.11:1111"));
            ensemble.add(new BookieSocketAddress("88.88.88.88:8888"));
            metadata.addEnsemble(0, ensemble);
            LedgerManager ledgerManager = LedgerManagerFactory.newLedgerManagerFactory(baseClientConf, zkc)
                    .newLedgerManager();
            MutableInt ledgerCreateRC = new MutableInt(-1);
            CountDownLatch latch = new CountDownLatch(1);
            long ledgerId = (Math.abs(rand.nextLong())) % 100000000;
            ledgerManager.createLedgerMetadata(ledgerId, metadata,
                    new BookkeeperInternalCallbacks.GenericCallback<Void>() {
                        @Override
                        public void operationComplete(int rc, Void result) {
                            ledgerCreateRC.setValue(rc);
                            latch.countDown();
                        }
                    });
            Assert.assertTrue("Ledger creation should complete within 2 secs",
                    latch.await(2000, TimeUnit.MILLISECONDS));
            Assert.assertEquals("LedgerCreate should succeed and return OK rc value", BKException.Code.OK,
                    ledgerCreateRC.getValue());
            ledgerList.add(ledgerId);
        }
        
        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList.size());
        urLedgerMgr.setLostBookieRecoveryDelay(lostBookieRecoveryDelayBeforeChange);
        assertTrue("Audit should be triggered and created ledgers should be marked as underreplicated",
                underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("All the ledgers should be marked as underreplicated", ledgerList.size(), urLedgerList.size());
        
        auditTask = auditorBookiesAuditor.getAuditTask();
        Assert.assertEquals("auditTask is supposed to be null", null, auditTask);
        Assert.assertEquals(
                "lostBookieRecoveryDelayBeforeChange of Auditor should be equal to BaseConf's lostBookieRecoveryDelay",
                lostBookieRecoveryDelayBeforeChange, auditorBookiesAuditor.getLostBookieRecoveryDelayBeforeChange());
    }
    
    @Test
    public void testTriggerAuditorWithPendingAuditTask() throws Exception {
     // wait for a second so that the initial periodic check finishes
        Thread.sleep(1000);
        
        Auditor auditorBookiesAuditor = getAuditorBookiesAuditor();
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList
                .size());

        int lostBookieRecoveryDelay = 5;
        // wait for 5 seconds before starting the recovery work when a bookie fails
        urLedgerMgr.setLostBookieRecoveryDelay(lostBookieRecoveryDelay);

        // shutdown a non auditor bookie; choosing non-auditor to avoid another election
        String shutdownBookie = shutDownNonAuditorBookie();

        LOG.debug("Waiting for ledgers to be marked as under replicated");
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());
        
        Future<?> auditTask = auditorBookiesAuditor.getAuditTask();
        Assert.assertNotEquals("auditTask is not supposed to be null", null, auditTask);
        Assert.assertEquals(
                "lostBookieRecoveryDelayBeforeChange of Auditor should be equal to what we set",
                lostBookieRecoveryDelay, auditorBookiesAuditor.getLostBookieRecoveryDelayBeforeChange());
        
        // set lostBookieRecoveryDelay to 5 (previous value), so that Auditor is triggered immediately 
        urLedgerMgr.setLostBookieRecoveryDelay(lostBookieRecoveryDelay);
        assertTrue("audit of lost bookie shouldn't be delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("all under replicated ledgers should be identified", ledgerList.size(),
                urLedgerList.size());        
        
        Thread.sleep(100);
        auditTask = auditorBookiesAuditor.getAuditTask();
        Assert.assertEquals("auditTask is supposed to be null", null, auditTask);
        Assert.assertEquals(
                "lostBookieRecoveryDelayBeforeChange of Auditor should be equal to previously set value",
                lostBookieRecoveryDelay, auditorBookiesAuditor.getLostBookieRecoveryDelayBeforeChange());
    }
    
    @Test
    public void testTriggerAuditorBySettingDelayToZeroWithPendingAuditTask() throws Exception {
     // wait for a second so that the initial periodic check finishes
        Thread.sleep(1000);
        
        Auditor auditorBookiesAuditor = getAuditorBookiesAuditor();
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        final CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList
                .size());

        int lostBookieRecoveryDelay = 5;
        // wait for 5 seconds before starting the recovery work when a bookie fails
        urLedgerMgr.setLostBookieRecoveryDelay(lostBookieRecoveryDelay);

        // shutdown a non auditor bookie; choosing non-auditor to avoid another election
        String shutdownBookie = shutDownNonAuditorBookie();

        LOG.debug("Waiting for ledgers to be marked as under replicated");
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());
        
        Future<?> auditTask = auditorBookiesAuditor.getAuditTask();
        Assert.assertNotEquals("auditTask is not supposed to be null", null, auditTask);
        Assert.assertEquals(
                "lostBookieRecoveryDelayBeforeChange of Auditor should be equal to what we set",
                lostBookieRecoveryDelay, auditorBookiesAuditor.getLostBookieRecoveryDelayBeforeChange());
        
        // set lostBookieRecoveryDelay to 0, so that Auditor is triggered immediately 
        urLedgerMgr.setLostBookieRecoveryDelay(0);
        assertTrue("audit of lost bookie shouldn't be delayed", underReplicaLatch.await(1, TimeUnit.SECONDS));
        assertEquals("all under replicated ledgers should be identified", ledgerList.size(),
                urLedgerList.size());        
        
        Thread.sleep(100);
        auditTask = auditorBookiesAuditor.getAuditTask();
        Assert.assertEquals("auditTask is supposed to be null", null, auditTask);
        Assert.assertEquals(
                "lostBookieRecoveryDelayBeforeChange of Auditor should be equal to previously set value",
                0, auditorBookiesAuditor.getLostBookieRecoveryDelayBeforeChange());
    }
    
    /**
     * Test audit of bookies is delayed when one bookie is down. But when
     * another one goes down, the audit is started immediately.
     */
    @Test
    public void testDelayedAuditWithMultipleBookieFailures() throws Exception {
        // wait for the periodic bookie check to finish
        Thread.sleep(1000);

        // create a ledger with a bunch of entries
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList.size());

        // wait for 10 seconds before starting the recovery work when a bookie fails
        urLedgerMgr.setLostBookieRecoveryDelay(10);

        // shutdown a non auditor bookie to avoid an election
        String shutdownBookie1 = shutDownNonAuditorBookie();

        // wait for 3 seconds and there shouldn't be any under replicated ledgers
        // because we have delayed the start of audit by 10 seconds
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(3, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());

        // Now shutdown the second non auditor bookie; We want to make sure that
        // the history about having delayed recovery remains. Hence we make sure
        // we bring down a non auditor bookie. This should cause the audit to take
        // place immediately and not wait for the remaining 7 seconds to elapse
        String shutdownBookie2 = shutDownNonAuditorBookie();

        // 2 second grace period for the ledgers to get reported as under replicated
        Thread.sleep(2000);

        // If the following checks pass, it means that audit happened
        // within 2 seconds of second bookie going down and it didn't
        // wait for 7 more seconds. Hence the second bookie failure doesn't
        // delay the audit
        assertTrue("Ledger is not marked as underreplicated:" + ledgerId,
                urLedgerList.contains(ledgerId));
        Map<Long, String> urLedgerData = getUrLedgerData(urLedgerList);
        String data = urLedgerData.get(ledgerId);
        assertTrue("Bookie " + shutdownBookie1 + shutdownBookie2
                + " are not listed in the ledger as missing replicas :" + data,
                data.contains(shutdownBookie1) && data.contains(shutdownBookie2));
    }

    /**
     * Test audit of bookies is delayed during rolling upgrade scenario:
     * a bookies goes down and comes up, the next bookie go down and up and so on.
     * At any time only one bookie is down.
     */
    @Test
    public void testDelayedAuditWithRollingUpgrade() throws Exception {
        // wait for the periodic bookie check to finish
        Thread.sleep(1000);

        // create a ledger with a bunch of entries
        LedgerHandle lh1 = createAndAddEntriesToLedger();
        Long ledgerId = lh1.getId();
        LOG.debug("Created ledger : " + ledgerId);
        ledgerList.add(ledgerId);
        lh1.close();

        CountDownLatch underReplicaLatch = registerUrLedgerWatcher(ledgerList.size());

        // wait for 5 seconds before starting the recovery work when a bookie fails
        urLedgerMgr.setLostBookieRecoveryDelay(5);

        // shutdown a non auditor bookie to avoid an election
        int idx1 = getShutDownNonAuditorBookieIdx("");
        ServerConfiguration conf1 = bsConfs.get(idx1);
        String shutdownBookie1 = shutdownBookie(idx1);

        // wait for 2 seconds and there shouldn't be any under replicated ledgers
        // because we have delayed the start of audit by 5 seconds
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());

        // restart the bookie we shut down above
        bs.add(startBookie(conf1));

        // Now to simulate the rolling upgrade, bring down a bookie different from
        // the one we brought down/up above.
        String shutdownBookie2 = shutDownNonAuditorBookie(shutdownBookie1);

        // since the first bookie that was brought down/up has come up, there is only
        // one bookie down at this time. Hence the lost bookie check shouldn't start
        // immediately; it will start 5 seconds after the second bookie went down
        assertFalse("audit of lost bookie isn't delayed", underReplicaLatch.await(2, TimeUnit.SECONDS));
        assertEquals("under replicated ledgers identified when it was not expected", 0,
                urLedgerList.size());

        // wait for a total of 6 seconds(2+4) for the ledgers to get reported as under replicated
        Thread.sleep(4000);

        // If the following checks pass, it means that auditing happened
        // after lostBookieRecoveryDelay during rolling upgrade as expected
        assertTrue("Ledger is not marked as underreplicated:" + ledgerId,
                urLedgerList.contains(ledgerId));
        Map<Long, String> urLedgerData = getUrLedgerData(urLedgerList);
        String data = urLedgerData.get(ledgerId);
        assertTrue("Bookie " + shutdownBookie1 + "wrongly listed as missing the ledger: " + data,
                   !data.contains(shutdownBookie1));
        assertTrue("Bookie " + shutdownBookie2
                   + " is not listed in the ledger as missing replicas :" + data,
                   data.contains(shutdownBookie2));
        LOG.info("*****************Test Complete");
    }

    private void waitForAuditToComplete() throws Exception {
        long endTime = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < endTime) {
            Auditor auditor = getAuditorBookiesAuditor();
            if (auditor != null) {
                Future<?> task = auditor.submitAuditTask();
                task.get(5, TimeUnit.SECONDS);
                return;
            }
            Thread.sleep(100);
        }
        throw new TimeoutException("Could not find an audit within 5 seconds");
    }

    /**
     * Wait for ledger to be underreplicated, and to be missing all replicas specified
     */
    private boolean waitForLedgerMissingReplicas(Long ledgerId, long secondsToWait, String... replicas)
            throws Exception {
        for (int i = 0; i < secondsToWait; i++) {
            try {
                UnderreplicatedLedgerFormat data = urLedgerMgr.getLedgerUnreplicationInfo(ledgerId);
                boolean all = true;
                for (String r : replicas) {
                    all = all && data.getReplicaList().contains(r);
                }
                if (all) {
                    return true;
                }
            } catch (Exception e) {
                // may not find node
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private CountDownLatch registerUrLedgerWatcher(int count)
            throws KeeperException, InterruptedException {
        final CountDownLatch underReplicaLatch = new CountDownLatch(count);
        for (Long ledgerId : ledgerList) {
            Watcher urLedgerWatcher = new ChildWatcher(underReplicaLatch);
            String znode = ZkLedgerUnderreplicationManager.getUrLedgerZnode(UNDERREPLICATED_PATH,
                                                                            ledgerId);
            zkc.exists(znode, urLedgerWatcher);
        }
        return underReplicaLatch;
    }

    private void doLedgerRereplication(Long... ledgerIds)
            throws UnavailableException {
        for (int i = 0; i < ledgerIds.length; i++) {
            long lid = urLedgerMgr.getLedgerToRereplicate();
            assertTrue("Received unexpected ledgerid", Arrays.asList(ledgerIds).contains(lid));
            urLedgerMgr.markLedgerReplicated(lid);
            urLedgerMgr.releaseUnderreplicatedLedger(lid);
        }
    }

    private String shutdownBookie(int bkShutdownIndex) throws Exception {
        BookieServer bkServer = bs.get(bkShutdownIndex);
        String bookieAddr = bkServer.getLocalAddress().toString();
        LOG.debug("Shutting down bookie:" + bookieAddr);
        killBookie(bkShutdownIndex);
        auditorElectors.get(bookieAddr).shutdown();
        auditorElectors.remove(bookieAddr);
        return bookieAddr;
    }

    private LedgerHandle createAndAddEntriesToLedger() throws BKException,
            InterruptedException {
        int numEntriesToWrite = 100;
        // Create a ledger
        LedgerHandle lh = bkc.createLedger(digestType, ledgerPassword);
        LOG.info("Ledger ID: " + lh.getId());
        addEntry(numEntriesToWrite, lh);
        return lh;
    }

    private void addEntry(int numEntriesToWrite, LedgerHandle lh)
            throws InterruptedException, BKException {
        final CountDownLatch completeLatch = new CountDownLatch(numEntriesToWrite);
        final AtomicInteger rc = new AtomicInteger(BKException.Code.OK);

        for (int i = 0; i < numEntriesToWrite; i++) {
            ByteBuffer entry = ByteBuffer.allocate(4);
            entry.putInt(rng.nextInt(Integer.MAX_VALUE));
            entry.position(0);
            lh.asyncAddEntry(entry.array(), new AddCallback() {
                    public void addComplete(int rc2, LedgerHandle lh, long entryId, Object ctx) {
                        rc.compareAndSet(BKException.Code.OK, rc2);
                        completeLatch.countDown();
                    }
                }, null);
        }
        completeLatch.await();
        if (rc.get() != BKException.Code.OK) {
            throw BKException.create(rc.get());
        }

    }

    private Map<Long, String> getUrLedgerData(Set<Long> urLedgerList)
            throws KeeperException, InterruptedException {
        Map<Long, String> urLedgerData = new HashMap<Long, String>();
        for (Long ledgerId : urLedgerList) {
            String znode = ZkLedgerUnderreplicationManager.getUrLedgerZnode(UNDERREPLICATED_PATH,
                                                                            ledgerId);
            byte[] data = zkc.getData(znode, false, null);
            urLedgerData.put(ledgerId, new String(data));
        }
        return urLedgerData;
    }

    private class ChildWatcher implements Watcher {
        private final CountDownLatch underReplicaLatch;

        public ChildWatcher(CountDownLatch underReplicaLatch) {
            this.underReplicaLatch = underReplicaLatch;
        }

        @Override
        public void process(WatchedEvent event) {
            LOG.info("Received notification for the ledger path : "
                    + event.getPath());
            for (Long ledgerId : ledgerList) {
                if (event.getPath().contains(ledgerId + "")) {
                    urLedgerList.add(ledgerId);
                }
            }
            LOG.debug("Count down and waiting for next notification");
            // count down and waiting for next notification
            underReplicaLatch.countDown();
        }
    }

    private BookieServer getAuditorBookie() throws Exception {
        List<BookieServer> auditors = new LinkedList<BookieServer>();
        byte[] data = zkc.getData(electionPath, false, null);
        Assert.assertNotNull("Auditor election failed", data);
        for (BookieServer bks : bs) {
            if (new String(data).contains(bks.getLocalAddress().getPort() + "")) {
                auditors.add(bks);
            }
        }
        Assert.assertEquals("Multiple Bookies acting as Auditor!", 1, auditors
                .size());
        return auditors.get(0);
    }

    private Auditor getAuditorBookiesAuditor() throws Exception {
        BookieServer auditorBookieServer = getAuditorBookie();
        String bookieAddr = auditorBookieServer.getLocalAddress().toString();
        return auditorElectors.get(bookieAddr).auditor;
    }

    private String  shutDownNonAuditorBookie() throws Exception {
        // shutdown bookie which is not an auditor
        int indexOf = bs.indexOf(getAuditorBookie());
        int bkIndexDownBookie;
        if (indexOf < bs.size() - 1) {
            bkIndexDownBookie = indexOf + 1;
        } else {
            bkIndexDownBookie = indexOf - 1;
        }
        return shutdownBookie(bkIndexDownBookie);
    }

    private int getShutDownNonAuditorBookieIdx(String exclude) throws Exception {
        // shutdown bookie which is not an auditor
        int indexOf = bs.indexOf(getAuditorBookie());
        int bkIndexDownBookie = 0;
        for (int i = 0; i < bs.size(); i++) {
            if (i == indexOf || bs.get(i).getLocalAddress().toString().equals(exclude)) {
                continue;
            }
            bkIndexDownBookie = i;
            break;
        }
        return bkIndexDownBookie;
    }

    private String shutDownNonAuditorBookie(String exclude) throws Exception {
        return shutdownBookie(getShutDownNonAuditorBookieIdx(exclude));
    }
}
