/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.timelock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.util.concurrent.Uninterruptibles;
import com.palantir.atlasdb.timelock.util.ExceptionMatchers;
import com.palantir.lock.LockDescriptor;
import com.palantir.lock.LockMode;
import com.palantir.lock.LockRefreshToken;
import com.palantir.lock.LockRequest;
import com.palantir.lock.StringLockDescriptor;
import com.palantir.lock.v2.LockRequestV2;
import com.palantir.lock.v2.LockResponseV2;
import com.palantir.lock.v2.LockTokenV2;

public class MultiNodePaxosTimeLockServerIntegrationTest {
    private static final String CLIENT_1 = "test";
    private static final String CLIENT_2 = "test2";
    private static final String CLIENT_3 = "test3";

    private static final TestableTimelockCluster CLUSTER = new TestableTimelockCluster(
            "http://localhost",
            CLIENT_1,
            "paxosMultiServer0.yml",
            "paxosMultiServer1.yml",
            "paxosMultiServer2.yml");

    private static final LockDescriptor LOCK = StringLockDescriptor.of("foo");
    private static final Set<LockDescriptor> LOCKS = ImmutableSet.of(LOCK);

    private static final LockRequest BLOCKING_LOCK_REQUEST = LockRequest.builder(
            ImmutableSortedMap.of(
                    StringLockDescriptor.of("foo"),
                    LockMode.WRITE))
            .build();
    private static final int DEFAULT_LOCK_TIMEOUT_MS = 10_000;

    @ClassRule
    public static final RuleChain ruleChain = CLUSTER.getRuleChain();

    @BeforeClass
    public static void waitForClusterToStabilize() {
        CLUSTER.waitUntilLeaderIsElected();
    }

    @Before
    public void bringAllNodesOnline() {
        CLUSTER.waitUntillAllSeversAreOnlineAndLeaderIsElected();
    }

    @Test
    public void blockedLockRequestThrows503OnLeaderElectionForRemoteLock() throws InterruptedException {
        LockRefreshToken lock = CLUSTER.remoteLock(CLIENT_1, BLOCKING_LOCK_REQUEST);
        assertThat(lock).isNotNull();

        TestableTimelockServer leader = CLUSTER.currentLeader();

        CompletableFuture<LockRefreshToken> lockRefreshTokenCompletableFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return leader.remoteLock(CLIENT_2, BLOCKING_LOCK_REQUEST);
            } catch (InterruptedException e) {
                return null;
            }
        });
        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
        CLUSTER.nonLeaders().forEach(TestableTimelockServer::kill);
        // Lock on leader so that AwaitingLeadershipProxy notices leadership loss.
        assertThatThrownBy(() -> leader.remoteLock(CLIENT_3, BLOCKING_LOCK_REQUEST))
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);

        assertThat(catchThrowable(lockRefreshTokenCompletableFuture::get).getCause())
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
    }

    @Test
    public void blockedLockRequestThrows503OnLeaderElectionForAsyncLock() throws InterruptedException {
        CLUSTER.lock(LockRequestV2.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();

        TestableTimelockServer leader = CLUSTER.currentLeader();

        CompletableFuture<LockResponseV2> token2 = CompletableFuture.supplyAsync(
                () -> leader.lock(LockRequestV2.of(LOCKS, 60_000)));

        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
        CLUSTER.nonLeaders().forEach(TestableTimelockServer::kill);
        // Lock on leader so that AwaitingLeadershipProxy notices leadership loss.
        assertThatThrownBy(() -> leader.lock(LockRequestV2.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)))
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);

        assertThat(catchThrowable(token2::get).getCause())
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
    }

    @Test
    public void nonLeadersReturn503() {
        CLUSTER.nonLeaders().forEach(server -> {
            assertThatThrownBy(() -> server.getFreshTimestamp())
                    .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
            assertThatThrownBy(() -> server.lock(LockRequestV2.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)))
                    .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
        });
    }

    @Test
    public void leaderRespondsToRequests() throws InterruptedException {
        CLUSTER.currentLeader().getFreshTimestamp();

        LockTokenV2 token = CLUSTER.currentLeader().lock(LockRequestV2.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();
        CLUSTER.unlock(token);
    }

    @Test
    public void newLeaderTakesOverIfCurrentLeaderDies() {
        CLUSTER.currentLeader().kill();

        CLUSTER.getFreshTimestamp();
    }

    @Test
    public void leaderLosesLeadersipIfQuorumIsNotAlive() {
        TestableTimelockServer leader = CLUSTER.currentLeader();
        CLUSTER.nonLeaders().forEach(TestableTimelockServer::kill);

        assertThatThrownBy(() -> leader.getFreshTimestamp())
                .satisfies(ExceptionMatchers::isRetryableExceptionWhereLeaderCannotBeFound);
    }

    @Test
    public void someoneBecomesLeaderAgainAfterQuorumIsRestored() {
        CLUSTER.nonLeaders().forEach(TestableTimelockServer::kill);
        CLUSTER.nonLeaders().forEach(TestableTimelockServer::start);

        CLUSTER.getFreshTimestamp();
    }

    @Test
    public void canPerformRollingRestart() {
        bringAllNodesOnline();
        for (TestableTimelockServer server : CLUSTER.servers()) {
            server.kill();
            CLUSTER.getFreshTimestamp();
            server.start();
        }
    }

    @Test
    public void timestampsAreIncreasingAcrossFailovers() {
        long lastTimestamp = CLUSTER.getFreshTimestamp();

        for (int i = 0; i < 3; i++) {
            CLUSTER.failoverToNewLeader();

            long timestamp = CLUSTER.getFreshTimestamp();
            assertThat(timestamp).isGreaterThan(lastTimestamp);
            lastTimestamp = timestamp;
        }
    }

    @Test
    public void locksAreInvalidatedAcrossFailures() throws InterruptedException {
        LockTokenV2 token = CLUSTER.lock(LockRequestV2.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();

        for (int i = 0; i < 3; i++) {
            CLUSTER.failoverToNewLeader();

            assertThat(CLUSTER.unlock(token)).isFalse();
            token = CLUSTER.lock(LockRequestV2.of(LOCKS, DEFAULT_LOCK_TIMEOUT_MS)).getToken();
        }
    }
}